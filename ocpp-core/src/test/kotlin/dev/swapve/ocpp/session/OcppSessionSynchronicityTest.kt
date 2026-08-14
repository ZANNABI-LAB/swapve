package dev.swapve.ocpp.session

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Part 4 §4.1.1 동기성.
 *
 * *"한쪽은 앞서 보낸 CALL 이 응답되거나 타임아웃되기 전에는 다음 CALL 을 같은 연결로 보내면
 * 안 된다(SHALL NOT)."* — 범위는 **연결당**이고, SEND 는 이 규칙에 걸리지 않는다.
 */
class OcppSessionSynchronicityTest {

    @Test
    fun `앞선 CALL 이 응답되기 전에는 다음 CALL 이 나가지 않는다`() = runTest {
        val connection = TestConnection()

        val first = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload(1))) }
        runCurrent()
        assertEquals(1, connection.sent.size, "첫 CALL 은 바로 나가야 한다")

        val second = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload(2))) }
        runCurrent()
        assertEquals(1, connection.sent.size, "앞선 CALL 이 끝나기 전에 두 번째 CALL 이 나갔다 (Part 4 §4.1.1 위반)")

        // 첫 응답이 오면 비로소 두 번째가 나간다.
        connection.session.receive(callResultText(messageIdOf(connection.sent[0]), requestBatterySwapAccepted()))
        runCurrent()
        assertEquals(2, connection.sent.size, "앞선 CALL 이 응답됐으니 두 번째가 나가야 한다")

        connection.session.receive(callResultText(messageIdOf(connection.sent[1]), requestBatterySwapAccepted()))
        advanceUntilIdle()

        assertIs<OcppResult.Accepted>(first.await())
        assertIs<OcppResult.Accepted>(second.await())
    }

    @Test
    fun `두 CALL 의 messageId 는 서로 다르다`() = runTest {
        val connection = TestConnection()

        val first = async { connection.session.call(OcppCall("Heartbeat", emptyObj())) }
        runCurrent()
        connection.session.receive(callResultText(messageIdOf(connection.sent[0]), heartbeatResponse()))
        runCurrent()

        val second = async { connection.session.call(OcppCall("Heartbeat", emptyObj())) }
        runCurrent()
        connection.session.receive(callResultText(messageIdOf(connection.sent[1]), heartbeatResponse()))
        advanceUntilIdle()

        first.await()
        second.await()
        // Part 4 §4.1.4 — 같은 스테이션에 대해 이전에 쓴 모든 값과 달라야 한다.
        assertTrue(messageIdOf(connection.sent[0]) != messageIdOf(connection.sent[1]))
    }

    @Test
    fun `SEND 는 pending CALL 이 있어도 즉시 나간다`() = runTest {
        val connection = TestConnection()

        val call = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload())) }
        runCurrent()
        assertEquals(1, connection.sent.size)

        // Part 4 §4.1.1 — SEND 는 응답을 기다리지 않으므로 동기성 제약에 걸리지 않는다.
        connection.session.send("NotifyPeriodicEventStream", emptyObj())
        runCurrent()

        assertEquals(2, connection.sent.size, "SEND 가 pending CALL 때문에 막혔다")
        assertEquals(6, typeNumberOf(connection.sent[1]))

        connection.session.receive(callResultText(messageIdOf(connection.sent[0]), requestBatterySwapAccepted()))
        advanceUntilIdle()
        assertIs<OcppResult.Accepted>(call.await())
    }

    @Test
    fun `우리가 응답을 기다리는 중에 도착한 상대 CALL 을 정상 처리한다`() = runTest {
        // Part 4 §4.1.1 — 양쪽 CALL 이 교차하는 것은 정상이다.
        val connection = TestConnection(onCall = { _, _ -> InboundResponse.Respond.empty() })

        val outgoing = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload(7))) }
        runCurrent()
        val outgoingId = messageIdOf(connection.sent[0])

        // 가상 시간을 흘리지 않는다 — 흘리면 우리 CALL 이 타임아웃돼서 교차 여부를 볼 수 없다.
        connection.session.receive(callText("station-1", "BatterySwap", batterySwapPayload(7, "BatteryIn")))
        runCurrent()

        assertEquals(2, connection.sent.size, "상대 CALL 에 대한 응답이 나가야 한다")
        assertEquals(3, typeNumberOf(connection.sent[1]))
        assertEquals("station-1", messageIdOf(connection.sent[1]))

        // 우리 CALL 은 여전히 응답을 기다리고 있다.
        assertTrue(outgoing.isActive)

        connection.session.receive(callResultText(outgoingId, requestBatterySwapAccepted()))
        advanceUntilIdle()
        assertIs<OcppResult.Accepted>(outgoing.await())
    }

    @Test
    fun `CALLERROR 로 응답되면 다음 CALL 이 풀린다`() = runTest {
        val connection = TestConnection()

        val first = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload(1))) }
        runCurrent()
        val second = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload(2))) }
        runCurrent()
        assertEquals(1, connection.sent.size)

        connection.session.receive(callErrorText(messageIdOf(connection.sent[0]), "NotSupported", "no"))
        runCurrent()

        assertEquals(2, connection.sent.size, "CALLERROR 도 '응답됨' 이다 (Part 4 §4.1.1)")
        val rejected = assertIs<OcppResult.Rejected>(first.await())
        assertEquals("NotSupported", rejected.errorCode)

        connection.session.receive(callResultText(messageIdOf(connection.sent[1]), requestBatterySwapAccepted()))
        advanceUntilIdle()
        assertIs<OcppResult.Accepted>(second.await())
    }

    private fun heartbeatResponse() = obj("""{"currentTime": "2026-08-14T09:00:00Z"}""")
}
