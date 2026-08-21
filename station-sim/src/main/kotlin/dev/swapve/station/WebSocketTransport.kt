package dev.swapve.station

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * 스테이션 쪽 WebSocket 전송 — **JDK 내장 [java.net.http.WebSocket] 만 쓴다.**
 *
 * 외부 WebSocket 라이브러리를 넣지 않는다 (기술선택 — 시뮬레이터 의존성 0).
 * 그 사실은 산문이 아니라 `:station-sim:checkNoForbiddenDependencies` 가 기계로 확인한다.
 *
 * ### 프로토콜을 모른다
 *
 * 이 클래스가 아는 것은 "텍스트 한 줄을 보낸다 / 텍스트 한 줄이 왔다" 두 가지뿐이다.
 * 프레이밍(M1)·스키마 검증(M2)·멱등·직렬화(M4)는 전부 `ocpp-core` 의 `OcppSession` 안에서
 * 일어난다 (원칙 3 — 시뮬레이터와 CSMS 가 같은 `ocpp-core` 를 공유한다).
 *
 * ### 순서와 교착
 *
 * 수신 콜백은 도착 순서대로 불리고, 우리는 [CoroutineStart.UNDISPATCHED] 로 이어 붙인다.
 * 코루틴 본문이 첫 중단 지점까지 호출 스레드에서 즉시 실행되므로 디코딩과 멱등 판정이
 * 도착 순서대로 일어나고, 거기서 중단되면 콜백 스레드는 곧바로 다음 프레임을 읽으러
 * 돌아간다. CSMS 쪽 핸들러와 같은 이유·같은 방식이다.
 *
 * ### 송신 직렬화
 *
 * [WebSocket.sendText] 는 앞선 송신이 끝나기 전에 다시 부르면 안 된다. 수신 CALL 에 대한
 * 응답과 우리가 보내는 CALL 이 겹칠 수 있으므로 [sendLock] 으로 직렬화한다.
 */
class WebSocketTransport private constructor(
    private val webSocket: WebSocket,
    private val scope: CoroutineScope,
) : StationTransport {

    private val sendLock = Mutex()

    /** 서버가 101 에 실어 답한 서브프로토콜 (Part 4 §3.3). 협상되지 않았으면 빈 문자열이다. */
    override val subprotocol: String get() = webSocket.subprotocol

    override val isOpen: Boolean get() = !webSocket.isInputClosed && !webSocket.isOutputClosed

    /** 텍스트 프레임 한 줄을 보낸다. */
    override suspend fun send(text: String) = sendLock.withLock {
        webSocket.sendText(text, true).await()
        Unit
    }

    override fun close() {
        runCatching { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join() }
        webSocket.abort()
        scope.cancel("transport closed")
    }

    companion object {

        /** OCPP 2.1 의 WebSocket 서브프로토콜 (Part 4 §3.1.2). */
        const val SUBPROTOCOL = "ocpp2.1"

        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

        /**
         * 연결한다.
         *
         * @param url 스테이션 식별자까지 붙은 최종 URL (Part 4 §3.1.1).
         * @param authorization Basic 인증의 `username:password` 원문. `null` 이면 헤더를 보내지 않는다.
         * @param onText 도착한 텍스트 한 줄을 처리한다. 보통 `OcppSession::receive` 다.
         */
        suspend fun connect(
            url: String,
            authorization: String? = null,
            onText: suspend (String) -> Unit,
        ): WebSocketTransport {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val builder = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .subprotocols(SUBPROTOCOL)
                .connectTimeout(CONNECT_TIMEOUT)

            if (authorization != null) {
                val encoded = Base64.getEncoder().encodeToString(authorization.toByteArray(Charsets.UTF_8))
                builder.header("Authorization", "Basic $encoded")
            }

            val webSocket = builder
                .buildAsync(URI.create(url), ReassemblingListener(scope, onText))
                .await()

            return WebSocketTransport(webSocket, scope)
        }
    }

    /**
     * 부분 프레임을 이어 붙여 한 줄로 만든 뒤 넘긴다.
     *
     * WebSocket 은 하나의 텍스트 메시지를 여러 조각으로 나눠 줄 수 있다. 조각째로
     * 디코딩하려 들면 JSON 이 깨진다.
     */
    private class ReassemblingListener(
        private val scope: CoroutineScope,
        private val onText: suspend (String) -> Unit,
    ) : WebSocket.Listener {

        private val partial = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            partial.append(data)
            if (last) {
                val text = partial.toString()
                partial.setLength(0)
                scope.launch(start = CoroutineStart.UNDISPATCHED) { onText(text) }
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }
    }
}
