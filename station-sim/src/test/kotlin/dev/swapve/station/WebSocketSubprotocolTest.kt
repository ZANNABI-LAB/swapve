package dev.swapve.station

import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 핸드셰이크에서 **무엇을 제시하고 무엇을 받아들이는가**.
 *
 * ### 왜 진짜 소켓을 세우는가
 *
 * [FakeCsms] 는 전송 팩토리라 핸드셰이크가 없다 — 서브프로토콜을 값으로 정해 줄 뿐이다.
 * 그래서 "우리가 실제로 두 개를 제시하는가"는 그쪽에서 물을 수 없다. 여기서는 JDK
 * [ServerSocket] 으로 101 만 돌려주는 최소 서버를 세워 [WebSocketTransport.connect] 를
 * 그대로 태운다. 쓰는 것은 JDK 뿐이라 `checkNoForbiddenDependencies` 가 보는 의존성은
 * 하나도 늘지 않는다.
 *
 * ### 협상 결과는 아무것도 막지 않는다
 *
 * 서버가 `ocpp2.0.1` 을 골라도 우리가 하는 일은 그 값을 [StationSimulator.subprotocol] 로
 * 그대로 내보이는 것뿐이다. 그 위에서 2.1 짜리 조작이 여전히 나간다는 사실은
 * `StationRoundTripTest` 가 붙잡는다.
 */
class WebSocketSubprotocolTest {

    @Test
    fun `제시 목록은 2·1 이 먼저고 2·0·1 이 뒤다`() {
        assertEquals(
            listOf("ocpp2.1", "ocpp2.0.1"),
            WebSocketTransport.SUBPROTOCOLS,
            "순서가 곧 우선순위다 — 조용히 바뀌면 서버가 고르는 것이 달라진다",
        )
    }

    @Test
    fun `둘을 순서대로 제시하고 서버가 고른 것을 그대로 받는다`() {
        HandshakeServer(selects = "ocpp2.0.1").use { server ->
            val transport = runBlocking {
                WebSocketTransport.connect(server.url) { /* 프레임은 오지 않는다 */ }
            }

            try {
                val offered = server.awaitOfferedProtocols()
                assertEquals(
                    "ocpp2.1, ocpp2.0.1",
                    offered,
                    "Sec-WebSocket-Protocol 은 우선순위 순으로 나가야 한다",
                )
                assertEquals(
                    "ocpp2.0.1",
                    transport.subprotocol,
                    "서버가 고른 것을 그대로 받아들인다",
                )
            } finally {
                transport.close()
            }
        }
    }

    /**
     * 101 하나만 돌려주고 마는 서버.
     *
     * 프레임은 주지도 읽지도 않는다 — 이 시험이 묻는 것은 핸드셰이크 한 번뿐이다.
     */
    private class HandshakeServer(private val selects: String) : AutoCloseable {

        private val server = ServerSocket(0)

        /** 클라이언트가 보낸 `Sec-WebSocket-Protocol` 헤더 값. 받아들이는 스레드가 채운다. */
        private val offered = ArrayBlockingQueue<String>(1)

        /** 핸드셰이크가 끝난 뒤에도 소켓을 열어 둬야 클라이언트가 끊겼다고 보지 않는다. */
        private var accepted: Socket? = null

        val url: String get() = "ws://localhost:${server.localPort}/ocpp/CS-HANDSHAKE"

        private val acceptor = thread(isDaemon = true, name = "handshake-server") {
            runCatching {
                val socket = server.accept().also { accepted = it }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

                var key: String? = null
                var protocols: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon < 0) continue
                    val name = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    when {
                        name.equals("Sec-WebSocket-Key", ignoreCase = true) -> key = value
                        name.equals("Sec-WebSocket-Protocol", ignoreCase = true) -> protocols = value
                    }
                }

                offered.offer(protocols ?: "")

                socket.getOutputStream().apply {
                    write(
                        buildString {
                            append("HTTP/1.1 101 Switching Protocols\r\n")
                            append("Upgrade: websocket\r\n")
                            append("Connection: Upgrade\r\n")
                            append("Sec-WebSocket-Accept: ${accept(key.orEmpty())}\r\n")
                            append("Sec-WebSocket-Protocol: $selects\r\n")
                            append("\r\n")
                        }.toByteArray(Charsets.UTF_8),
                    )
                    flush()
                }
            }
        }

        fun awaitOfferedProtocols(): String =
            assertNotNull(offered.poll(10, TimeUnit.SECONDS), "핸드셰이크 요청이 오지 않았다")

        override fun close() {
            runCatching { server.close() }
            runCatching { accepted?.close() }
            acceptor.join(TimeUnit.SECONDS.toMillis(5))
        }

        /** RFC 6455 §1.3 — 키에 고정 GUID 를 붙여 SHA-1 하고 Base64 한다. */
        private fun accept(key: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest((key + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII))
            return Base64.getEncoder().encodeToString(digest)
        }

        private companion object {
            const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        }
    }
}
