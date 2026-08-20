package dev.swapve.ocpp.session

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [StationCommandBus] — 상위 계층이 스테이션에 닿는 유일한 통로.
 *
 * 여기 어디에도 세션 객체가 나오지 않는다. **항상 `stationId` 로 지시한다.**
 */
class StationCommandBusTest {

    @Test
    fun `연결 없는 stationId 로 보내면 예외가 아니라 결과로 알려준다`() = runTest {
        val bus: StationCommandBus = LocalStationCommandBus(SessionRegistry())

        val result = bus.send("ST-없음", OcppCall("RequestBatterySwap", requestBatterySwapPayload()))

        val notConnected = assertIs<OcppResult.NotConnected>(result, "예외를 던지면 안 된다")
        assertEquals("ST-없음", notConnected.stationId)
    }

    @Test
    fun `등록된 스테이션에 CALL 이 전달되고 응답이 돌아온다`() = runTest {
        val registry = SessionRegistry()
        val bus: StationCommandBus = LocalStationCommandBus(registry)
        val connection = TestConnection("ST-1")
        registry.register(connection.session)

        val result = async { bus.send("ST-1", OcppCall("RequestBatterySwap", requestBatterySwapPayload(42))) }
        runCurrent()

        assertEquals(1, connection.sent.size)
        assertEquals("RequestBatterySwap", actionOf(connection.sent[0]))
        assertEquals(42, payloadOf(connection.sent[0]).get("requestId").intValue())

        connection.session.receive(callResultText(messageIdOf(connection.sent[0]), requestBatterySwapRejected()))
        advanceUntilIdle()

        val accepted = assertIs<OcppResult.Accepted>(result.await())
        assertEquals("Rejected", accepted.payload.get("status").textValue())
    }

    @Test
    fun `연결이 끊기면 다시 NotConnected 다`() = runTest {
        val registry = SessionRegistry()
        val bus: StationCommandBus = LocalStationCommandBus(registry)
        val connection = TestConnection("ST-1")
        registry.register(connection.session)
        assertTrue(registry.isConnected("ST-1"))

        connection.session.close()
        assertTrue(registry.unregister(connection.session))
        assertFalse(registry.isConnected("ST-1"))

        assertIs<OcppResult.NotConnected>(bus.send("ST-1", OcppCall("Heartbeat", emptyObj())))
    }

    @Test
    fun `재접속하면 새 연결로 나간다`() = runTest {
        val registry = SessionRegistry()
        val bus: StationCommandBus = LocalStationCommandBus(registry)
        val ledger = InboundCallLedger()
        val serializer = StationSerializer()

        val before = TestConnection("ST-1", ledger, serializer)
        assertNull(registry.register(before.session))

        val after = TestConnection("ST-1", ledger, serializer)
        assertEquals(before.session, registry.register(after.session), "대체된 세션을 돌려줘야 닫을 수 있다")
        before.session.close()

        val result = async { bus.send("ST-1", OcppCall("Heartbeat", emptyObj())) }
        runCurrent()

        assertTrue(before.sent.isEmpty(), "닫힌 연결로 나가면 안 된다")
        assertEquals(1, after.sent.size)

        after.session.receive(callResultText(messageIdOf(after.sent[0]), obj("""{"currentTime": "2026-08-14T09:00:00Z"}""")))
        advanceUntilIdle()
        assertIs<OcppResult.Accepted>(result.await())
    }

    @Test
    fun `옛 연결의 종료 처리가 늦게 와도 새 연결을 지우지 않는다`() = runTest {
        val registry = SessionRegistry()
        val before = TestConnection("ST-1")
        val after = TestConnection("ST-1")
        registry.register(before.session)
        registry.register(after.session)

        // 옛 연결의 정리가 뒤늦게 도착한다.
        assertFalse(registry.unregister(before.session))
        assertTrue(registry.isConnected("ST-1"), "살아 있는 새 세션이 지워졌다")
        assertEquals(setOf("ST-1"), registry.connectedStationIds)
    }

    @Test
    fun `닫힌 세션은 보내지 않고 바로 NotConnected 다`() = runTest {
        val connection = TestConnection("ST-1")
        connection.session.close()

        val result = connection.session.call(OcppCall("Heartbeat", emptyObj()))

        assertIs<OcppResult.NotConnected>(result)
        assertTrue(connection.sent.isEmpty())
    }
}
