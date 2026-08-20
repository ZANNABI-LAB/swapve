package dev.swapve.csms.support

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 시험용 OCPP 스테이션 클라이언트.
 *
 * JDK 내장 [java.net.http.WebSocket] 을 쓴다 — 의존성이 0 이고, M6 의 `station-sim` 이 쓸
 * 것과 같은 스택이다 (기술선택). 즉 이 클라이언트로 통과한 시험은 시뮬레이터에서도
 * 같은 방식으로 붙는다는 뜻이다.
 *
 * 받은 텍스트는 큐에 쌓아 두고 [receive] 로 꺼낸다. 부분 프레임은 [last] 가 참이 될 때까지
 * 이어 붙인다.
 */
class OcppTestClient private constructor(
    private val webSocket: WebSocket,
    private val inbox: LinkedBlockingQueue<String>,
) : AutoCloseable {

    /** 서버가 101 에 실어 답한 서브프로토콜 (Part 4 §3.3). 협상되지 않았으면 빈 문자열이다. */
    val subprotocol: String get() = webSocket.subprotocol

    fun send(text: String) {
        webSocket.sendText(text, true).join()
    }

    /** 다음 메시지를 기다린다. 오지 않으면 실패한다. */
    fun receive(timeout: Duration = DEFAULT_TIMEOUT): String =
        inbox.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)
            ?: error("${timeout.toMillis()}ms 안에 응답이 오지 않았다")

    /** 정해진 시간 동안 아무것도 오지 않았음을 확인한다. `null` 이면 조용했다는 뜻이다. */
    fun receiveOrNull(timeout: Duration): String? = inbox.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)

    val isOpen: Boolean get() = !webSocket.isInputClosed && !webSocket.isOutputClosed

    override fun close() {
        runCatching { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join() }
        webSocket.abort()
    }

    companion object {

        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)

        /**
         * 연결한다.
         *
         * @param rawPath 퍼센트 인코딩된 상태 그대로의 경로. 예: `/ocpp/RDAM%7C123`
         * @param subprotocols 제시할 서브프로토콜. 비우면 헤더 자체를 보내지 않는다.
         */
        fun connect(
            port: Int,
            rawPath: String,
            subprotocols: List<String> = listOf("ocpp2.1"),
        ): OcppTestClient {
            val inbox = LinkedBlockingQueue<String>()
            val builder = HttpClient.newHttpClient().newWebSocketBuilder()
            if (subprotocols.isNotEmpty()) {
                builder.subprotocols(subprotocols.first(), *subprotocols.drop(1).toTypedArray())
            }

            val webSocket = builder
                .connectTimeout(DEFAULT_TIMEOUT)
                .buildAsync(URI.create("ws://localhost:$port$rawPath"), CollectingListener(inbox))
                .join()

            return OcppTestClient(webSocket, inbox)
        }
    }

    private class CollectingListener(private val inbox: LinkedBlockingQueue<String>) : WebSocket.Listener {

        private val partial = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            partial.append(data)
            if (last) {
                inbox.add(partial.toString())
                partial.setLength(0)
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }
    }
}
