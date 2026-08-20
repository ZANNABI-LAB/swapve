package dev.swapve.ocpp.session

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.swap.BatteryData
import dev.swapve.swap.IdToken
import dev.swapve.swap.SlotId
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapEvent
import dev.swapve.swap.SwapKey
import dev.swapve.swap.SwapRequestId
import dev.swapve.swap.SwapStateMachine
import dev.swapve.swap.SwapTransaction
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 추가 전용 이벤트 로그.
 *
 * *"파생 상태(교환 트랜잭션 등)는 이 로그에서 계산될 수 있어야 한다. 그게 유일한 규칙이다."*
 *
 * 그래서 마지막 테스트가 이 마일스톤의 진짜 합격선이다 — 세션의 어떤 내부 상태도 보지 않고
 * **로그만으로** M3 상태머신을 돌려 교환 완료에 도달한다.
 */
class OcppEventLogTest {

    private val mapper = ObjectMapper()

    @Test
    fun `주고받은 메시지가 순서대로 남는다`() = runTest {
        val connection = TestConnection()

        val outgoing = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload(77))) }
        runCurrent()
        val outgoingId = messageIdOf(connection.sent[0])
        connection.session.receive(callResultText(outgoingId, requestBatterySwapAccepted()))
        connection.session.receive(callText("swap-1", "BatterySwap", batterySwapPayload(77, "BatteryIn")))
        advanceUntilIdle()
        outgoing.await()

        val records = connection.log.of(connection.stationId)
        assertEquals(listOf(1L, 2L, 3L, 4L), records.map { it.seq }, "스테이션 내 순번이 1 부터 이어져야 한다")
        assertEquals(
            listOf(
                MessageDirection.OUTBOUND to "RequestBatterySwap",
                MessageDirection.INBOUND to "RequestBatterySwap",
                MessageDirection.INBOUND to "BatterySwap",
                MessageDirection.OUTBOUND to "BatterySwap",
            ),
            records.map { it.direction to it.action },
        )
        // CALLRESULT 에는 action 이 없다. 짝이 되는 CALL 에서 채운다.
        assertEquals(outgoingId, records[1].messageId)
        assertEquals(TEST_CLOCK.instant(), records[0].occurredAt)
        // 원문 그대로다 — 프레임 한 줄 전체가 남는다.
        assertEquals(connection.sent[0], records[0].payload)
    }

    @Test
    fun `스테이션마다 순번이 따로 매겨진다`() = runTest {
        val log = InMemoryOcppEventLog()
        val serializer = StationSerializer()
        val ledger = InboundCallLedger()
        val first = TestConnection("ST-A", ledger, serializer, log)
        val second = TestConnection("ST-B", ledger, serializer, log)

        first.session.receive(callText("a1", "BatterySwap", batterySwapPayload(1, "BatteryIn")))
        second.session.receive(callText("b1", "BatterySwap", batterySwapPayload(1, "BatteryIn")))
        first.session.receive(callText("a2", "BatterySwap", batterySwapPayload(1, "BatteryOut")))
        advanceUntilIdle()

        // seq 는 "스테이션 내 순서"다. 스테이션을 가로지르는 전역 순서가 아니다.
        assertEquals(listOf(1L, 2L, 3L, 4L), log.of("ST-A").map { it.seq })
        assertEquals(listOf(1L, 2L), log.of("ST-B").map { it.seq })
        assertEquals(6, log.size())
    }

    @Test
    fun `이 로그만으로 교환 진행을 재구성할 수 있다`() = runTest {
        val connection = TestConnection()
        val requestId = 1234

        // TC_S_103_CSMS 를 줄인 시퀀스 — 원격 개시(S02) → 입고 → 출고.
        val remoteStart = async {
            connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload(requestId)))
        }
        runCurrent()
        connection.session.receive(callResultText(messageIdOf(connection.sent[0]), requestBatterySwapAccepted()))
        advanceUntilIdle()
        assertIs<OcppResult.Accepted>(remoteStart.await())

        connection.session.receive(callText("in-1", "BatterySwap", batterySwapPayload(requestId, "BatteryIn")))
        advanceUntilIdle()
        connection.session.receive(
            callText(
                "out-1",
                "BatterySwap",
                batterySwapPayload(requestId, "BatteryOut", firstSlot = 3, secondSlot = 4, firstSerial = "4321", secondSerial = "8765", firstSoC = 80, secondSoC = 85),
            ),
        )
        advanceUntilIdle()

        // 여기서부터는 세션도 상태머신도 모른다. 로그만 읽는다.
        val events = connection.log.of(connection.stationId).mapNotNull { toSwapEvent(it) }
        val reconstructed = SwapStateMachine.replay(SwapTransaction.Idle, events)

        val completed = assertIs<SwapTransaction.Completed>(reconstructed, "로그만으로 교환 완료를 재구성하지 못했다")
        assertEquals(SwapKey(StationId(connection.stationId), SwapRequestId(requestId)), completed.key)
        // 불변식 — 들어온 배터리 수 = 나간 배터리 수
        assertEquals(2, completed.batteriesIn.size)
        assertEquals(2, completed.batteriesOut.size)
        // 양쪽 SoC 가 다 남아 있으므로 과금이 나중에 순수 계산이 된다.
        assertEquals(listOf("1234", "5678"), completed.batteriesIn.map { it.serialNumber })
        assertEquals(listOf("4321", "8765"), completed.batteriesOut.map { it.serialNumber })
        assertTrue(completed.batteriesOut.sumOf { it.soC } > completed.batteriesIn.sumOf { it.soC })
    }

    /**
     * 이벤트 레코드 하나를 교환 사건으로 옮긴다.
     *
     * **로그에 남은 것만 쓴다** — 세션 내부 상태도, 원래 프레임 객체도 보지 않는다.
     * 이게 "이벤트 로그만으로 재구성 가능하다"의 뜻이다.
     */
    private fun toSwapEvent(record: OcppEventRecord): SwapEvent? {
        val frame = mapper.readTree(record.payload)
        if (frame.get(0).intValue() != 2) return null
        val payload = frame.get(3) as ObjectNode
        val stationId = StationId(record.stationId)

        return when (record.action) {
            // CSMS 가 보낸 원격 개시가 수락됐다는 것은 응답을 봐야 알 수 있으나, 여기서는
            // 인가 시점만 필요하므로 요청 자체를 인가 사건으로 옮긴다 (S02).
            "RequestBatterySwap" -> SwapEvent.Authorized(
                key = SwapKey(stationId, SwapRequestId(payload.get("requestId").intValue())),
                idToken = idTokenOf(payload),
                at = record.occurredAt,
            )

            "BatterySwap" -> {
                val key = SwapKey(stationId, SwapRequestId(payload.get("requestId").intValue()))
                when (payload.get("eventType").textValue()) {
                    "BatteryIn" -> SwapEvent.BatteryIn(key, idTokenOf(payload), batteriesOf(payload), record.occurredAt)
                    "BatteryOut" -> SwapEvent.BatteryOut(key, idTokenOf(payload), batteriesOf(payload), record.occurredAt)
                    "BatteryOutTimeout" -> SwapEvent.BatteryOutTimeout(key, record.occurredAt)
                    else -> null
                }
            }

            else -> null
        }
    }

    private fun idTokenOf(payload: ObjectNode) = IdToken(
        idToken = payload.get("idToken").get("idToken").textValue(),
        type = payload.get("idToken").get("type").textValue(),
    )

    private fun batteriesOf(payload: ObjectNode): List<BatteryData> =
        payload.get("batteryData").map {
            BatteryData(
                slotId = SlotId(it.get("evseId").intValue()),
                serialNumber = it.get("serialNumber").textValue(),
                soC = it.get("soC").doubleValue(),
                soH = it.get("soH").doubleValue(),
            )
        }
}
