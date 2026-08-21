package dev.swapve.station

/**
 * 스테이션이 CSMS 와 텍스트 한 줄을 주고받는 통로.
 *
 * ### 왜 인터페이스가 필요한가
 *
 * JDK 에는 **클라이언트 쪽 WebSocket 만** 있다 ([java.net.http.WebSocket]). 서버 쪽이
 * 없으므로 시뮬레이터 앞에 가짜 CSMS 를 세울 수단이 `station-sim` 안에는 없고, 외부
 * WebSocket 라이브러리를 들이는 것은 `checkNoForbiddenDependencies` 가 막는다.
 *
 * 그래서 남는 길은 하나다 — **전송을 갈아 끼울 수 있게 하는 것.** 이 이음새가 없으면
 * [StationSimulator] 는 실제 CSMS 를 띄워야만 시험할 수 있고, 그러면 시뮬레이터 자신의
 * 결함과 CSMS 의 결함이 한 덩어리로 붙어 어느 쪽이 틀렸는지 가려낼 수 없다.
 *
 * 표면은 [WebSocketTransport] 의 것과 정확히 같다. 인터페이스를 뽑느라 무언가를 새로
 * 추상화하지 않았다는 뜻이다.
 */
interface StationTransport : AutoCloseable {

    /** 서버가 101 에 실어 답한 서브프로토콜 (Part 4 §3.3). 협상되지 않았으면 빈 문자열이다. */
    val subprotocol: String

    val isOpen: Boolean

    /** 텍스트 프레임 한 줄을 보낸다. */
    suspend fun send(text: String)

    override fun close()
}

/**
 * 전송을 여는 방법. [StationSimulator] 가 연결할 때마다 이것을 부른다.
 *
 * 인자 셋은 [WebSocketTransport.connect] 의 것을 그대로 옮긴 것이다 — 실제 연결이 필요로
 * 하는 정보가 곧 이음새의 모양이어야 가짜가 진짜를 대신할 수 있다.
 */
fun interface StationTransportFactory {

    /**
     * 연다.
     *
     * @param url 스테이션 식별자까지 붙은 최종 URL (Part 4 §3.1.1).
     * @param authorization Basic 인증의 `username:password` 원문. `null` 이면 인증하지 않는다.
     * @param onText 도착한 텍스트 한 줄을 처리한다. 보통 `OcppSession::receive` 다.
     */
    suspend fun open(
        url: String,
        authorization: String?,
        onText: suspend (String) -> Unit,
    ): StationTransport

    companion object {

        /** 실제 경로 — JDK 내장 WebSocket. 시뮬레이터의 기본값이 곧 운영에서 도는 것이다. */
        val WebSocket = StationTransportFactory { url, authorization, onText ->
            WebSocketTransport.connect(url, authorization, onText)
        }
    }
}
