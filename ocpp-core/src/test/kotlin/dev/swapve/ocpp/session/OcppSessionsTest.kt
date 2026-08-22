package dev.swapve.ocpp.session

import dev.swapve.ocpp.swap.BatterySwapWire
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 팩토리가 실제로 걷어내는 것은 **정답이 하나뿐인 배선**이다.
 *
 * 가장 중요한 것은 재접속이다. 손으로 조립할 때는 소비자가 원장을 세션 밖에서 만들어
 * 넘겨야 한다는 사실을 **알아야만** 멱등이 지켜졌다. 여기서는 같은 `stationId` 로 다시
 * 열기만 하면 된다.
 */
class OcppSessionsTest {

    private fun batterySwapCall(messageId: String) = callText(
        messageId,
        BatterySwapWire.BATTERY_SWAP,
        batterySwapPayload(requestId = 1234, eventType = BatterySwapWire.BATTERY_IN),
    )

    @Test
    fun `같은 스테이션으로 다시 열면 멱등이 이어진다`() = runTest {
        val log = InMemoryOcppEventLog()
        val sessions = OcppSessions(clock = TEST_CLOCK, eventSink = log, validator = sharedValidator)

        var handled = 0
        val firstSent = mutableListOf<String>()
        val first = sessions.open(
            stationId = "ST-1",
            transmit = { firstSent += it; TransmitOutcome.Delivered },
            onCall = { _, _ -> handled++; InboundResponse.Respond.empty() },
        )

        val messageId = "m-1"
        first.receive(batterySwapCall(messageId))
        assertEquals(1, handled)

        first.close()

        // 재접속 — 소비자가 넘기는 것은 stationId 뿐이다.
        val secondSent = mutableListOf<String>()
        val second = sessions.open(
            stationId = "ST-1",
            transmit = { secondSent += it; TransmitOutcome.Delivered },
            onCall = { _, _ -> handled++; InboundResponse.Respond.empty() },
        )
        second.receive(batterySwapCall(messageId))

        assertEquals(1, handled, "재전송으로 상위 계층이 다시 불렸다 — 장부가 두 번 계상된다")
        assertEquals(firstSent.single(), secondSent.single(), "저장된 응답을 원문 그대로 다시 보낸다")
    }

    @Test
    fun `다른 스테이션은 같은 messageId 를 써도 서로 막지 않는다`() = runTest {
        val sessions = OcppSessions(clock = TEST_CLOCK, eventSink = InMemoryOcppEventLog(), validator = sharedValidator)

        var handled = 0
        val sentA = mutableListOf<String>()
        val sentB = mutableListOf<String>()

        val a = sessions.open("ST-A", transmit = { sentA += it; TransmitOutcome.Delivered }, onCall = { _, _ -> handled++; InboundResponse.Respond.empty() })
        val b = sessions.open("ST-B", transmit = { sentB += it; TransmitOutcome.Delivered }, onCall = { _, _ -> handled++; InboundResponse.Respond.empty() })

        // 원장 하나를 공유해도 키에 stationId 가 들어가므로 섞이지 않는다.
        a.receive(batterySwapCall("same-id"))
        b.receive(batterySwapCall("same-id"))

        assertEquals(2, handled, "다른 스테이션의 같은 messageId 를 중복으로 오인했다")
        assertEquals(1, sentA.size)
        assertEquals(1, sentB.size)
    }

    @Test
    fun `팩토리가 연 세션도 손으로 조립한 것과 같은 성질을 갖는다`() = runTest {
        val sessions = OcppSessions(clock = TEST_CLOCK, eventSink = InMemoryOcppEventLog(), validator = sharedValidator)
        val sent = mutableListOf<String>()
        val session = sessions.open("ST-1", transmit = { sent += it; TransmitOutcome.Delivered }, onCall = { _, _ -> InboundResponse.Respond.empty() })

        // 스키마를 통과하지 못한 CALL 은 CALLERROR 로 답한다 — 세션의 판정이 그대로 산다.
        session.receive(callText("m-1", BatterySwapWire.BATTERY_SWAP, emptyObj()))

        assertEquals(4, typeNumberOf(sent.single()), "CALLERROR 로 답해야 한다")
        assertNotEquals("", errorCodeOf(sent.single()))
    }
}
