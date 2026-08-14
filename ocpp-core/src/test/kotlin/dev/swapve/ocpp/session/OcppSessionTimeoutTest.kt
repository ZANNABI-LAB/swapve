package dev.swapve.ocpp.session

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Part 4 §4.1.1 타임아웃.
 *
 * 간격은 구현이 정한다. **여기 있는 테스트는 실제로 단 1 밀리초도 기다리지 않는다** —
 * 세션이 현재 시각을 직접 조회하지 않고 코루틴의 시간을 쓰기 때문에 가상 시간으로 즉시 끝난다.
 */
class OcppSessionTimeoutTest {

    private val timeout = 30.seconds

    @Test
    fun `응답이 없으면 정해진 시간 뒤 타임아웃으로 끝난다`() = runTest {
        val connection = TestConnection(callTimeout = timeout)

        val result = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload())) }
        advanceUntilIdle()

        val timedOut = assertIs<OcppResult.TimedOut>(result.await())
        assertEquals(messageIdOf(connection.sent[0]), timedOut.messageId)
        assertEquals(timeout.inWholeMilliseconds, currentTime, "정확히 정해진 시간만큼만 기다려야 한다")
    }

    @Test
    fun `타임아웃 전에 응답이 오면 기다림이 끝난다`() = runTest {
        val connection = TestConnection(callTimeout = timeout)

        val result = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload())) }
        runCurrent()
        connection.session.receive(callResultText(messageIdOf(connection.sent[0]), requestBatterySwapAccepted()))
        advanceUntilIdle()

        assertIs<OcppResult.Accepted>(result.await())
        assertEquals(0L, currentTime, "응답이 왔는데도 타임아웃까지 기다렸다")
    }

    @Test
    fun `타임아웃 뒤 도착한 늦은 CALLRESULT 가 아무것도 깨뜨리지 않는다`() = runTest {
        val connection = TestConnection(callTimeout = timeout)

        val first = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload(1))) }
        advanceUntilIdle()
        val abandonedId = messageIdOf(connection.sent[0])
        assertIs<OcppResult.TimedOut>(first.await())

        // 짝이 없는 응답. 회신도 예외도 없다.
        connection.session.receive(callResultText(abandonedId, requestBatterySwapAccepted()))
        advanceUntilIdle()
        assertEquals(1, connection.sent.size, "짝 없는 CALLRESULT 에 회신하면 안 된다")

        // 세션은 멀쩡하다 — 다음 CALL 이 정상으로 돈다.
        val second = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload(2))) }
        runCurrent()
        connection.session.receive(callResultText(messageIdOf(connection.sent[1]), requestBatterySwapAccepted()))
        advanceUntilIdle()
        assertIs<OcppResult.Accepted>(second.await())

        // 그리고 늦은 응답도 기록에는 남아 있다 (PLAN §11.1 — 정보를 버리지 않는다).
        val late = connection.log.of(connection.stationId)
            .filter { it.direction == MessageDirection.INBOUND && it.messageId == abandonedId }
        assertEquals(1, late.size)
    }

    @Test
    fun `타임아웃된 CALL 이 다음 CALL 을 막지 않는다`() = runTest {
        val connection = TestConnection(callTimeout = timeout)

        val first = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload(1))) }
        val second = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload(2))) }
        advanceUntilIdle()

        assertIs<OcppResult.TimedOut>(first.await())
        assertIs<OcppResult.TimedOut>(second.await())
        assertEquals(2, connection.sent.size)
        // 앞선 것이 타임아웃돼야 뒤가 나간다 — 직렬이므로 두 배다 (Part 4 §4.1.1).
        assertEquals(timeout.inWholeMilliseconds * 2, currentTime)
    }

    @Test
    fun `연결이 끊기면 기다리던 CALL 이 NotConnected 로 깨어난다`() = runTest {
        val connection = TestConnection(callTimeout = timeout)

        val result = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload())) }
        runCurrent()
        connection.session.close()
        advanceUntilIdle()

        assertIs<OcppResult.NotConnected>(result.await())
        assertEquals(0L, currentTime, "연결이 끊겼는데 타임아웃까지 기다렸다")
    }
}
