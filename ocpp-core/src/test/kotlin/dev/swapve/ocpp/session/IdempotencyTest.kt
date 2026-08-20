package dev.swapve.ocpp.session

import dev.swapve.ocpp.rpc.RpcErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 멱등 — F4·F6 — M4 의 핵심 위험이었던 부분.
 *
 * *"재접속 시 스테이션이 CALL 을 재전송하면 중복 BatterySwap 이 발생하고, 멱등 처리가 없으면
 * 장부가 깨진다."*
 */
class IdempotencyTest {

    @Test
    fun `같은 messageId 의 CALL 을 다시 받으면 상위 계층은 한 번만 불리고 응답은 두 번 나간다`() = runTest {
        val handlerCalls = AtomicInteger()
        val connection = TestConnection(
            onCall = { _, _ ->
                handlerCalls.incrementAndGet()
                InboundResponse.Respond.empty()
            },
        )

        val frame = callText("swap-1", "BatterySwap", batterySwapPayload(1, "BatteryIn"))
        connection.session.receive(frame)
        advanceUntilIdle()
        connection.session.receive(frame)
        advanceUntilIdle()

        assertEquals(1, handlerCalls.get(), "부수효과가 두 번 일어났다 — 장부가 깨진다 (F4)")
        assertEquals(2, connection.sent.size, "재전송에는 응답을 다시 보내야 한다")
        assertEquals(connection.sent[0], connection.sent[1], "저장해 둔 응답을 그대로 다시 보내야 한다")
    }

    @Test
    fun `연결이 끊겼다 새로 붙은 뒤 재전송돼도 상위 계층은 다시 불리지 않는다`() = runTest {
        // F6 — 이것이 M4 의 핵심 위험이다.
        val handlerCalls = AtomicInteger()
        val handler: OcppCallHandler = { _, _ ->
            handlerCalls.incrementAndGet()
            InboundResponse.Respond.empty()
        }

        // 원장·직렬화·로그는 스테이션의 수명을 따르고, 세션은 연결의 수명을 따른다.
        val ledger = InboundCallLedger()
        val serializer = StationSerializer()
        val log = InMemoryOcppEventLog()
        val frame = callText("swap-1", "BatterySwap", batterySwapPayload(1, "BatteryIn"))

        val before = TestConnection("ST-7", ledger, serializer, log, onCall = handler)
        before.session.receive(frame)
        advanceUntilIdle()
        assertEquals(1, before.sent.size)

        // 연결이 끊긴다.
        before.session.close()

        // 스테이션이 재접속해 같은 CALL 을 재전송한다 (Part 4 §4.1.4 — 재전송은 같은
        // messageId 를 써도 된다(MAY)).
        val after = TestConnection("ST-7", ledger, serializer, log, onCall = handler)
        after.session.receive(frame)
        advanceUntilIdle()

        assertEquals(1, handlerCalls.get(), "재접속 재전송이 중복 처리됐다 (F6)")
        assertEquals(1, after.sent.size, "새 연결로 응답이 다시 나가야 한다")
        assertEquals(before.sent[0], after.sent[0], "같은 응답이 나가야 한다")
    }

    @Test
    fun `아직 처리 중인 messageId 를 다시 받으면 CALLERROR 로 답한다`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val handlerCalls = AtomicInteger()
        val connection = TestConnection(
            onCall = { _, _ ->
                handlerCalls.incrementAndGet()
                gate.await()
                InboundResponse.Respond.empty()
            },
        )

        val frame = callText("swap-1", "BatterySwap", batterySwapPayload(1, "BatteryIn"))
        launch { connection.session.receive(frame) }
        runCurrent()
        assertTrue(connection.sent.isEmpty(), "아직 처리 중이라 응답이 없어야 한다")

        launch { connection.session.receive(frame) }
        runCurrent()

        // Part 4 §4.2.3 — "이미 같은 고유 식별자의 메시지를 처리 중"은 CALLERROR 사유다.
        val reply = connection.lastSent()
        assertEquals(4, typeNumberOf(reply))
        assertEquals("swap-1", messageIdOf(reply))
        assertEquals(RpcErrorCode.GenericError.name, errorCodeOf(reply))
        assertEquals(1, handlerCalls.get(), "처리 중인 메시지가 두 번 처리됐다")

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, connection.sent.size)
        assertEquals(3, typeNumberOf(connection.lastSent()), "원래 처리는 정상으로 끝나야 한다")
    }

    @Test
    fun `상위 계층이 예외로 끝나면 재전송으로 다시 시도할 수 있다`() = runTest {
        val handlerCalls = AtomicInteger()
        val connection = TestConnection(
            onCall = { _, _ ->
                if (handlerCalls.incrementAndGet() == 1) error("일시적 장애") else InboundResponse.Respond.empty()
            },
        )

        val frame = callText("swap-1", "BatterySwap", batterySwapPayload(1, "BatteryIn"))
        connection.session.receive(frame)
        advanceUntilIdle()
        assertEquals(RpcErrorCode.InternalError.name, errorCodeOf(connection.sent[0]))

        connection.session.receive(frame)
        advanceUntilIdle()

        assertEquals(2, handlerCalls.get(), "실패한 처리는 재전송으로 다시 시도할 수 있어야 한다")
        assertEquals(3, typeNumberOf(connection.sent[1]))
    }

    @Test
    fun `서로 다른 스테이션의 같은 messageId 는 서로 다른 메시지다`() = runTest {
        // messageId 의 유일성 범위는 "같은 스테이션 식별자에 대해" 다 (Part 4 §4.1.4).
        val handled = mutableListOf<String>()
        val handler: OcppCallHandler = { stationId, _ ->
            handled += stationId
            InboundResponse.Respond.empty()
        }
        val ledger = InboundCallLedger()
        val frame = callText("swap-1", "BatterySwap", batterySwapPayload(1, "BatteryIn"))

        val first = TestConnection("ST-A", ledger, onCall = handler)
        val second = TestConnection("ST-B", ledger, onCall = handler)
        first.session.receive(frame)
        second.session.receive(frame)
        advanceUntilIdle()

        assertEquals(listOf("ST-A", "ST-B"), handled)
    }

    @Test
    fun `원장은 상한을 넘지 않고 오래된 것부터 버린다`() = runTest {
        val ledger = InboundCallLedger(maxEntries = 3)
        val connection = TestConnection(ledger = ledger)

        repeat(5) { index ->
            connection.session.receive(callText("swap-$index", "BatterySwap", batterySwapPayload(index, "BatteryIn")))
        }
        advanceUntilIdle()

        assertEquals(3, ledger.size(), "상한을 넘어 자라면 안 된다")
        // 가장 오래된 것은 밀려났으므로 재전송이 새 메시지로 처리된다 — 상한의 대가다.
        assertIs<CallClaim.Fresh>(ledger.claim(InboundCallKey(connection.stationId, "swap-0")))
        assertIs<CallClaim.AlreadyAnswered>(ledger.claim(InboundCallKey(connection.stationId, "swap-4")))
    }

    @Test
    fun `재전송이 반복되는 메시지는 새 메시지에 밀려나지 않는다`() = runTest {
        // accessOrder LRU — 조회도 "최근 쓰임"으로 친다.
        val ledger = InboundCallLedger(maxEntries = 3)
        val connection = TestConnection(ledger = ledger)

        val hot = callText("swap-hot", "BatterySwap", batterySwapPayload(0, "BatteryIn"))
        connection.session.receive(hot)
        advanceUntilIdle()

        repeat(4) { index ->
            connection.session.receive(callText("swap-$index", "BatterySwap", batterySwapPayload(index + 1, "BatteryIn")))
            connection.session.receive(hot)
            advanceUntilIdle()
        }

        assertIs<CallClaim.AlreadyAnswered>(ledger.claim(InboundCallKey(connection.stationId, "swap-hot")))
    }

    @Test
    fun `재전송 응답도 이벤트 로그에 남는다`() = runTest {
        val connection = TestConnection()
        val frame = callText("swap-1", "BatterySwap", batterySwapPayload(1, "BatteryIn"))

        connection.session.receive(frame)
        advanceUntilIdle()
        connection.session.receive(frame)
        advanceUntilIdle()

        val records = connection.log.of(connection.stationId)
        assertEquals(4, records.size, "주고받은 네 번이 모두 남아야 한다")
        assertEquals(
            listOf(
                MessageDirection.INBOUND,
                MessageDirection.OUTBOUND,
                MessageDirection.INBOUND,
                MessageDirection.OUTBOUND,
            ),
            records.map { it.direction },
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), records.map { it.seq })
        assertTrue(records.all { it.action == "BatterySwap" })
        assertNotEquals(records[0].seq, records[2].seq)
    }
}
