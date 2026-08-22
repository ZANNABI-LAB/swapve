package dev.swapve.station

import dev.swapve.swap.IdToken
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 전송 이음새의 계약 (A1).
 *
 * ### 여기서 확인하는 것은 프로토콜이 아니라 **연결**이다
 *
 * JDK 에 서버 쪽 WebSocket 이 없어 가짜 CSMS 를 세울 수 없다는 사실이 [StationTransport] 를
 * 만든 이유다 (그쪽 KDoc). 이 시험은 그 이음새가 실제로 이음새 노릇을 하는지만 본다 —
 * 주입한 전송이 시뮬레이터의 관측 가능한 상태에 그대로 비치는가, 다시 붙을 때 또 불리는가,
 * 닫을 때 함께 닫히는가.
 *
 * 부팅·교환 같은 프로토콜 왕복은 여기 없다. 그것은 가짜 CSMS 가 응답을 만들 수 있어야
 * 하는 이야기라 A2 의 몫이다.
 */
class StationTransportSeamTest {

    @Test
    fun `주입한 전송의 상태가 시뮬레이터에 그대로 비친다`() = runBlocking {
        val factory = RecordingFactory()

        simulator(factory).use { station ->
            assertFalse(station.isConnected, "연결 전에는 붙어 있지 않다")

            station.connect()

            assertTrue(station.isConnected)
            assertEquals(RecordingTransport.SUBPROTOCOL, station.subprotocol)
        }
    }

    @Test
    fun `다시 붙을 때 팩토리를 다시 부르고 주소는 config 에서 온다`() = runBlocking {
        val factory = RecordingFactory()

        simulator(factory).use { station ->
            station.connect()
            station.disconnect()
            station.reconnect()

            assertEquals(2, factory.opened.size, "connect 와 reconnect 가 각각 전송을 연다")
            factory.opened.forEach { opened ->
                assertEquals("ws://localhost:8080/ocpp/CS-SEAM", opened.url)
                assertEquals("CS-SEAM:s3cret", opened.authorization)
            }
        }
    }

    @Test
    fun `close 가 전송을 닫는다`() = runBlocking {
        val factory = RecordingFactory()
        val station = simulator(factory)

        station.connect()
        station.close()

        assertFalse(factory.opened.single().transport.isOpen)
        assertFalse(station.isConnected)
    }

    // ------------------------------------------------------------------ 가짜 전송

    /** 한 번 열린 전송. 어떤 인자로 열렸는지까지 남긴다. */
    private class Opened(
        val url: String,
        val authorization: String?,
        val transport: RecordingTransport,
    )

    /** 열린 전송을 순서대로 모아 두는 팩토리. */
    private class RecordingFactory : StationTransportFactory {

        val opened = mutableListOf<Opened>()

        override suspend fun open(
            url: String,
            authorization: String?,
            onText: suspend (String) -> Unit,
        ): StationTransport {
            val transport = RecordingTransport()
            opened += Opened(url, authorization, transport)
            return transport
        }
    }

    /** 보낸 텍스트를 모으기만 하는 전송. CSMS 가 없으므로 답은 오지 않는다. */
    private class RecordingTransport : StationTransport {

        val sent = mutableListOf<String>()

        override var isOpen: Boolean = true
            private set

        override val subprotocol: String get() = SUBPROTOCOL

        override suspend fun send(text: String) {
            sent += text
        }

        override fun close() {
            isOpen = false
        }

        companion object {
            /** 실제 협상값과 같은 것을 답한다 (Part 4 §3.1.2). */
            const val SUBPROTOCOL = WebSocketTransport.SUBPROTOCOL
        }
    }

    /**
     * ★ **닫은 시뮬레이터는 다시 붙지 않는다.**
     *
     * `close()` 는 `AutoCloseable` 이다 — "스테이션 전원을 내린다"가 아니라 "이 객체를 그만
     * 쓴다"이고, 세션까지 닫는다. 막지 않으면 `reconnect()` 의 검사가 `transport == null`
     * 하나뿐이라 **재접속이 성공하고**, 소켓은 열렸는데 아무것도 보내지 못하는 스테이션이
     * 된다. `isConnected` 는 참인데 벙어리다.
     *
     * 이 검사는 순서 게이트가 아니다. 순서 게이트였다면 `authorize()` 없는
     * `insertBatteries()`(=F5)도 막았을 것이고, 그건 이 시뮬레이터가 시험 도구인 이유를
     * 없앤다. 여기서 묻는 것은 프로토콜 순서가 아니라 **객체가 살아 있는가**다.
     */
    @Test
    fun `닫은 시뮬레이터는 다시 붙지 않는다`() = runBlocking {
        val factory = RecordingFactory()
        val station = simulator(factory)

        station.connect()
        station.close()

        assertFailsWith<IllegalStateException> { station.reconnect() }
        assertFailsWith<IllegalStateException> { station.connect() }
        assertFalse(station.isConnected, "닫혔는데 붙어 있다")
        assertEquals(1, factory.opened.size, "닫힌 뒤에 전송이 또 열렸다")
    }

    // ------------------------------------------------------------------ 공통

    private fun simulator(factory: StationTransportFactory) = StationSimulator(
        config = config(),
        openTransport = factory,
    )

    private fun config() = StationSimConfig(
        csmsUrl = "ws://localhost:8080/ocpp",
        stationId = "CS-SEAM",
        password = "s3cret",
        slots = listOf(SlotConfig(1), SlotConfig(2, SimBattery("BAT-OUT", soC = 95.0, soH = 98.0))),
        idToken = IdToken("RFID-SEAM", "ISO14443"),
        requestId = 42,
        insertSlots = listOf(1),
        dispenseSlots = listOf(2),
        incomingBatteries = listOf(SimBattery("BAT-IN", soC = 12.0, soH = 90.0)),
    )
}
