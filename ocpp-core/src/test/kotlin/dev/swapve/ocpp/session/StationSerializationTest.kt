package dev.swapve.ocpp.session

import dev.swapve.ocpp.rpc.RpcErrorCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * per-station 직렬화 (PLAN §11.5).
 *
 * **직렬화 키는 `stationId` 다. 세션 객체가 아니다.** 로컬에선 `stationId` 기반 락이고,
 * 분산되면 그대로 파티셔닝 키가 된다.
 */
class StationSerializationTest {

    @Test
    fun `같은 스테이션의 메시지는 도착 순서대로 처리된다`() = runTest {
        val handled = Collections.synchronizedList(mutableListOf<Int>())
        val connection = TestConnection(
            onCall = { _, call ->
                // 처리에 시간이 걸려도 순서가 흐트러지면 안 된다.
                delay(1.seconds)
                handled += call.payload.get("requestId").intValue()
                InboundResponse.Respond.empty()
            },
        )

        (1..5).forEach { requestId ->
            launch {
                connection.session.receive(
                    callText("swap-$requestId", "BatterySwap", batterySwapPayload(requestId, "BatteryIn")),
                )
            }
        }
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3, 4, 5), handled, "도착 순서가 처리 순서여야 한다")
        assertEquals(5, connection.sent.size)
        assertEquals(
            (1..5).map { "swap-$it" },
            connection.sent.map { messageIdOf(it) },
            "응답도 같은 순서로 나가야 한다",
        )
        // 겹치지 않고 하나씩 처리됐다.
        assertEquals(5.seconds.inWholeMilliseconds, currentTime)
    }

    @Test
    fun `한 스테이션이 느려도 다른 스테이션은 막히지 않는다`() = runTest {
        // 락은 세션이 아니라 stationId 단위이므로, 두 스테이션은 서로 다른 락을 쓴다.
        val serializer = StationSerializer()
        val ledger = InboundCallLedger()

        val slowHandler: OcppCallHandler = { _, _ ->
            delay(1.seconds)
            InboundResponse.Respond.empty()
        }
        val slow = TestConnection("ST-SLOW", ledger, serializer, onCall = slowHandler)
        val fast = TestConnection("ST-FAST", ledger, serializer)

        launch { slow.session.receive(callText("s1", "BatterySwap", batterySwapPayload(1, "BatteryIn"))) }
        launch { fast.session.receive(callText("f1", "BatterySwap", batterySwapPayload(1, "BatteryIn"))) }
        runCurrent()

        assertEquals(1, fast.sent.size, "느린 스테이션이 다른 스테이션을 막았다")
        assertTrue(slow.sent.isEmpty(), "느린 쪽은 아직 처리 중이어야 한다")
        assertEquals(0L, currentTime, "빠른 스테이션은 기다리지 않고 끝나야 한다")

        advanceUntilIdle()
        assertEquals(1, slow.sent.size)
    }

    @Test
    fun `같은 스테이션의 두 연결도 같은 락을 공유한다`() = runTest {
        // 재접속으로 세션이 바뀌어도 직렬화가 끊기면 안 된다 — 세션 객체로 락을 잡으면
        // 정확히 그 일이 벌어진다 (PLAN §11.5).
        val handled = Collections.synchronizedList(mutableListOf<Int>())
        val serializer = StationSerializer()
        val ledger = InboundCallLedger()
        val handler: OcppCallHandler = { _, call ->
            delay(1.seconds)
            handled += call.payload.get("requestId").intValue()
            InboundResponse.Respond.empty()
        }

        val first = TestConnection("ST-1", ledger, serializer, onCall = handler)
        val second = TestConnection("ST-1", ledger, serializer, onCall = handler)

        launch { first.session.receive(callText("a", "BatterySwap", batterySwapPayload(1, "BatteryIn"))) }
        launch { second.session.receive(callText("b", "BatterySwap", batterySwapPayload(2, "BatteryIn"))) }
        advanceUntilIdle()

        assertEquals(listOf(1, 2), handled)
        assertEquals(2.seconds.inWholeMilliseconds, currentTime, "두 연결이 동시에 처리됐다")
    }

    @Test
    fun `응답 수신은 스테이션 락을 기다리지 않는다`() = runTest {
        // 응답이 직렬화를 타면, 스테이션 락을 쥔 상위 계층이 스스로 CALL 을 보내고 응답을
        // 기다리는 순간 교착한다. 응답은 상위 계층을 부르지 않으므로 직렬화 대상이 아니다.
        lateinit var connection: TestConnection
        connection = TestConnection(
            onCall = { _, _ ->
                when (connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload(9)))) {
                    is OcppResult.Accepted -> InboundResponse.Respond.empty()
                    else -> InboundResponse.Fail(RpcErrorCode.InternalError, "응답이 오지 않았다")
                }
            },
        )

        launch { connection.session.receive(callText("swap-1", "BatterySwap", batterySwapPayload(1, "BatteryIn"))) }
        runCurrent()

        // 상위 계층이 스테이션 락을 쥔 채 자기 CALL 을 내보냈다.
        assertEquals(1, connection.sent.size)
        assertEquals(2, typeNumberOf(connection.sent[0]))

        // 그 응답이 스테이션 락을 기다리지 않고 도착해야 한다.
        connection.session.receive(callResultText(messageIdOf(connection.sent[0]), requestBatterySwapAccepted()))
        advanceUntilIdle()

        assertEquals(2, connection.sent.size)
        assertEquals(3, typeNumberOf(connection.sent[1]), "교착 없이 원래 CALL 에 응답해야 한다")
        assertEquals("swap-1", messageIdOf(connection.sent[1]))
    }
}
