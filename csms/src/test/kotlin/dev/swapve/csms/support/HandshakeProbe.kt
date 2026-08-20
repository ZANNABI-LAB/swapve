package dev.swapve.csms.support

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * WebSocket 핸드셰이크를 **손으로** 보내고 응답 헤더를 그대로 읽는다.
 *
 * WebSocket 클라이언트 라이브러리를 쓰면 101 응답의 헤더를 볼 수 없다. 그런데 우리가
 * 증명해야 하는 것 중 상당수가 헤더 자체다 — 101 에 `Sec-WebSocket-Protocol: ocpp2.1` 이
 * 실렸는가(§3.3), `Sec-WebSocket-Extensions` 에 `permessage-deflate` 가 협상됐는가(§3.4),
 * 거절이 정말 업그레이드 전에 일어나는가.
 *
 * 그래서 소켓에 직접 쓴다. 핸드셰이크만 확인하고 닫으므로 프레이밍은 구현하지 않는다.
 */
object HandshakeProbe {

    data class Response(val statusLine: String, val headers: Map<String, List<String>>) {

        val status: Int get() = statusLine.split(' ')[1].toInt()

        val isSwitchingProtocols: Boolean get() = status == 101

        /** 헤더 이름은 대소문자를 가리지 않는다 (RFC 9110). */
        fun header(name: String): List<String> = headers[name.lowercase(Locale.ROOT)].orEmpty()
    }

    fun handshake(
        port: Int,
        rawPath: String,
        subprotocols: List<String> = listOf("ocpp2.1"),
        extensions: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response = Socket("localhost", port).use { socket ->
        socket.soTimeout = TIMEOUT_MILLIS

        val request = buildString {
            append("GET $rawPath HTTP/1.1\r\n")
            append("Host: localhost:$port\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ${nonce()}\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            if (subprotocols.isNotEmpty()) {
                append("Sec-WebSocket-Protocol: ${subprotocols.joinToString(", ")}\r\n")
            }
            if (extensions != null) {
                append("Sec-WebSocket-Extensions: $extensions\r\n")
            }
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1))
        socket.getOutputStream().flush()

        readResponse(BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1)))
    }

    /** 상태줄과 헤더만 읽는다. 본문은 읽지 않고 소켓을 닫는다. */
    private fun readResponse(reader: BufferedReader): Response {
        val statusLine = reader.readLine() ?: error("응답이 없다")
        val headers = mutableMapOf<String, MutableList<String>>()

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator < 0) continue
            val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim()
            headers.getOrPut(name) { mutableListOf() } += value
        }

        return Response(statusLine, headers)
    }

    /** 16 바이트 난수의 base64. 값 자체는 검증하지 않으므로 고정값이어도 되지만 규격은 지킨다. */
    private fun nonce(): String = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })

    private const val TIMEOUT_MILLIS = 5_000
}
