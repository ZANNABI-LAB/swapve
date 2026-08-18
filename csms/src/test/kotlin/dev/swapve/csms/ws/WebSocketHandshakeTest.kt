package dev.swapve.csms.ws

import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.support.HandshakeProbe
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 실제로 뜬 서버에 손으로 핸드셰이크를 보내 101 응답 헤더를 확인한다
 * (Part 4 Edition 2 §3.1.2, §3.3, §3.4).
 *
 * 판정 규칙 자체는 `StationIdentityTest` 가 순수 함수로 시험한다. 여기서 증명하는 것은
 * **그 판정이 실제 핸드셰이크에 배선돼 있는가**다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(FixedClockConfig::class)
class WebSocketHandshakeTest {

    private val log = LoggerFactory.getLogger(javaClass)

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `ocpp2_1 을 제시하면 101 과 함께 그 서브프로토콜이 실려 온다`() {
        val response = HandshakeProbe.handshake(port, "/ocpp/CS001")

        assertTrue(response.isSwitchingProtocols, "핸드셰이크가 101 이 아니다: ${response.statusLine}")
        assertEquals(listOf("ocpp2.1"), response.header("Sec-WebSocket-Protocol"))
    }

    @Test
    fun `여러 개를 제시해도 서버가 고른 하나만 실려 온다`() {
        // 클라이언트는 선호 순으로 여러 개를 보낼 수 있다 (§3.1.2). 서버는 하나로 답한다 (§3.3).
        val response = HandshakeProbe.handshake(port, "/ocpp/CS001", listOf("ocpp2.1", "ocpp2.0.1", "ocpp1.6"))

        assertTrue(response.isSwitchingProtocols)
        assertEquals(listOf("ocpp2.1"), response.header("Sec-WebSocket-Protocol"))
    }

    @Test
    fun `서브프로토콜을 제시하지 않으면 연결되지 않는다`() {
        val response = HandshakeProbe.handshake(port, "/ocpp/CS001", subprotocols = emptyList())

        assertFalse(response.isSwitchingProtocols, "협상 없이 연결이 열렸다: ${response.statusLine}")
    }

    @Test
    fun `ocpp2_1 이 아닌 것만 제시하면 연결되지 않는다`() {
        val response = HandshakeProbe.handshake(port, "/ocpp/CS001", listOf("ocpp1.6", "ocpp2.0.1"))

        assertFalse(response.isSwitchingProtocols, "지원하지 않는 버전으로 연결이 열렸다: ${response.statusLine}")
    }

    @Test
    fun `URL 에 버전이 들어 있어도 그것이 버전을 정하지 않는다`() {
        // §3.1.2 — 버전은 오직 서브프로토콜 협상으로 정해진다. 경로는 식별자일 뿐이다.
        val response = HandshakeProbe.handshake(port, "/ocpp/ocpp2.1", subprotocols = emptyList())

        assertFalse(response.isSwitchingProtocols)
    }

    @Test
    fun `48자를 넘는 식별자는 거절된다`() {
        val response = HandshakeProbe.handshake(port, "/ocpp/" + "C".repeat(49))

        assertFalse(response.isSwitchingProtocols, "49자 식별자로 연결이 열렸다: ${response.statusLine}")
    }

    @Test
    fun `48자 식별자는 통과한다`() {
        val response = HandshakeProbe.handshake(port, "/ocpp/" + "C".repeat(48))

        assertTrue(response.isSwitchingProtocols, "48자는 상한 이내다: ${response.statusLine}")
    }

    @Test
    fun `콜론이 든 식별자는 거절된다`() {
        val response = HandshakeProbe.handshake(port, "/ocpp/CS:001")

        assertFalse(response.isSwitchingProtocols, "콜론이 든 식별자로 연결이 열렸다: ${response.statusLine}")
    }

    @Test
    fun `퍼센트 인코딩된 콜론도 거절된다`() {
        val response = HandshakeProbe.handshake(port, "/ocpp/CS%3A001")

        assertFalse(response.isSwitchingProtocols, "%3A 가 콜론 검사를 통과했다: ${response.statusLine}")
    }

    @Test
    fun `퍼센트 인코딩된 식별자로 연결된다`() {
        val response = HandshakeProbe.handshake(port, "/ocpp/RDAM%7C123")

        assertTrue(response.isSwitchingProtocols, "인코딩된 식별자가 거절됐다: ${response.statusLine}")
        assertEquals(listOf("ocpp2.1"), response.header("Sec-WebSocket-Protocol"))
    }

    /**
     * ★ RFC 7692 압축 (§3.4) — **CSMS 는 지원해야 한다(SHALL).**
     *
     * 추측하지 않고 관측한다. 클라이언트가 `permessage-deflate` 를 제시했을 때 101 응답의
     * `Sec-WebSocket-Extensions` 에 그것이 실려 오는지가 전부다. 결론과 그 함의는 README 의
     * 적합성 절과 `WebSocketConfig` KDoc 에 적어 두었다 — 조용히 넘어가지 않는다.
     */
    @Test
    fun `permessage-deflate 협상 결과를 관측한다`() {
        val response = HandshakeProbe.handshake(
            port,
            "/ocpp/CS001",
            extensions = "permessage-deflate; client_max_window_bits",
        )

        assertTrue(response.isSwitchingProtocols, "확장을 제시했다고 연결이 거절되면 안 된다: ${response.statusLine}")

        val negotiated = response.header("Sec-WebSocket-Extensions")
        log.info("협상된 WebSocket 확장 (Part 4 §3.4): {}", negotiated.ifEmpty { "(없음)" })

        assertTrue(
            negotiated.any { "permessage-deflate" in it },
            "§3.4 는 CSMS 가 permessage-deflate 를 지원할 것을 SHALL 로 요구한다. " +
                "협상된 확장: ${negotiated.ifEmpty { "(없음)" }}",
        )
    }
}
