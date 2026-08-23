package dev.swapve.csms.ws

import dev.swapve.csms.config.CsmsProperties
import dev.swapve.csms.ocpp.OcppMessageRouter
import dev.swapve.ocpp.rpc.OcppFrameCodec
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.session.InboundCallLedger
import dev.swapve.ocpp.session.OcppEventSink
import dev.swapve.ocpp.session.OcppSession
import dev.swapve.ocpp.session.TransmitOutcome
import dev.swapve.ocpp.session.SessionRegistry
import dev.swapve.ocpp.session.StationSerializer
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.toKotlinDuration

/**
 * WebSocket 연결 하나를 M4 의 [OcppSession] 하나에 잇는다.
 *
 * ### 여기서 프로토콜을 다시 구현하지 않는다
 *
 * 받은 텍스트 프레임을 [OcppSession.receive] 에 **그대로** 넘긴다. 프레이밍(M1), 스키마
 * 검증(M2), 멱등과 per-station 직렬화(M4)는 전부 그 안에서 일어난다. 이 클래스가 아는 것은
 * "연결이 생겼다 / 문자열이 왔다 / 연결이 끊겼다" 세 가지뿐이다.
 *
 * ### 공유하는 것과 연결마다 새로 만드는 것
 *
 * [InboundCallLedger] · [StationSerializer] · [OcppPayloadValidator] · [OcppEventSink] 는
 * **전 연결이 공유하는 싱글턴 빈**이다. 앞의 둘은 키가 `stationId` 라서 재접속으로 세션이
 * 바뀌어도 이어져야 하고 (F6), 검증기는 스키마 181 개를 캐시하므로 연결마다 새로
 * 만들면 그만큼 다시 파싱한다. 연결마다 새로 만드는 것은 [OcppSession] 하나뿐이다.
 *
 * ### 수신 순서와 교착
 *
 * 프레임은 도착 순서대로 [OcppSession.receive] 에 들어가야 한다. 그렇다고 컨테이너 스레드를
 * `runBlocking` 으로 붙잡으면 **교착한다**: 수신 처리 중에 CSMS 가 같은 스테이션으로 CALL 을
 * 보내고 응답을 기다리면, 그 응답 프레임을 읽어야 할 스레드가 이미 막혀 있다.
 *
 * 그래서 [CoroutineStart.UNDISPATCHED] 로 띄운다. 코루틴 본문이 **첫 중단 지점까지는 호출
 * 스레드에서 즉시** 실행되므로 디코딩·이벤트기록·멱등 판정이 도착 순서대로 일어나고, 거기서
 * 중단되면 컨테이너 스레드는 곧바로 다음 프레임을 읽으러 돌아간다. WebSocket 컨테이너가
 * 한 연결의 메시지 콜백을 동시에 부르지 않는다는 보장(JSR-356)이 순서의 나머지 절반이다.
 */
@Component
class OcppWebSocketHandler(
    private val sessions: SessionRegistry,
    private val router: OcppMessageRouter,
    private val eventSink: OcppEventSink,
    private val ledger: InboundCallLedger,
    private val serializer: StationSerializer,
    private val validator: OcppPayloadValidator,
    private val codec: OcppFrameCodec,
    private val clock: Clock,
    private val properties: CsmsProperties,
) : TextWebSocketHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** WebSocket 세션 id → 연결 하나의 상태. */
    private val connections = ConcurrentHashMap<String, StationConnection>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val principal = session.attributes[OcppHandshakeInterceptor.STATION_PRINCIPAL_ATTRIBUTE] as? StationPrincipal
        if (principal == null) {
            // 핸드셰이크를 통과했다면 반드시 있다. 없다는 것은 배선이 끊겼다는 뜻이라 연결을 열지 않는다.
            log.error("StationPrincipal 없이 연결이 수립됐다. 핸드셰이크 인터셉터 배선을 확인하라: {}", session.uri)
            session.close(CloseStatus.SERVER_ERROR)
            return
        }

        // sendMessage 는 스레드 안전하지 않다. 동시 송신을 이 데코레이터가 직렬화한다.
        val transport = ConcurrentWebSocketSessionDecorator(
            session,
            SEND_TIME_LIMIT_MILLIS,
            properties.maxTextMessageSize * SEND_BUFFER_FACTOR,
        )
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("ocpp-${principal.stationId}"),
        )

        val ocppSession = OcppSession(
            stationId = principal.stationId,
            transmit = { text ->
                // 송신은 블로킹 I/O 다. 코루틴 기본 디스패처의 스레드를 붙잡지 않게 옮긴다.
                withContext(Dispatchers.IO) {
                    try {
                        transport.sendMessage(TextMessage(text))
                        TransmitOutcome.Delivered
                    } catch (failure: Exception) {
                        // 소켓이 죽은 것은 정상 사건이다. 예외로 올리면 세션의 "모든 결과를
                        // 값으로 돌려준다"가 깨지고, REST 가 "안 붙어 있다" 대신 500 을 낸다.
                        TransmitOutcome.Gone("${principal.stationId}: ${failure.message ?: failure.toString()}")
                    }
                }
            },
            // 핸들러는 stationId 문자열이 아니라 StationPrincipal 을 받는다.
            onCall = { _, call -> router.handle(principal, call) },
            eventSink = eventSink,
            ledger = ledger,
            serializer = serializer,
            clock = clock,
            codec = codec,
            validator = validator,
            callTimeout = properties.callTimeout.toKotlinDuration(),
        )

        connections[session.id] = StationConnection(principal, ocppSession, transport, scope)

        // 같은 스테이션의 이전 연결이 남아 있으면 그 세션을 깨운다. 기다리던 CALL 들이
        // NotConnected 로 돌아간다. 멱등 원장은 건드리지 않는다 — 재전송이 오면 저장된 응답을
        // 그대로 다시 보내야 한다 (F6).
        sessions.register(ocppSession)?.let { previous ->
            log.info("같은 스테이션의 이전 세션을 대체한다: station={}", principal.stationId)
            previous.close()
            closeTransportOf(previous)
        }

        log.info(
            "연결 수립: station={} auth={} subprotocol={}",
            principal.stationId, principal.authMethod, session.acceptedProtocol,
        )
    }

    /**
     * 교체된 [previous] 세션이 타고 있던 WebSocket 을 닫는다.
     *
     * 세션만 닫으면 소켓은 열린 채 남는다. 닫힌 세션의 [OcppSession.receive] 는 프레임을
     * 회신 없이 버리므로 그 소켓은 조용한 블랙홀이 되고, 상대는 타임아웃까지 기다린다.
     */
    private fun closeTransportOf(previous: OcppSession) {
        // stationId 가 아니라 세션 동일성으로 찾는다 — 방금 등록한 새 연결이 같은 stationId 라서다.
        val stale = connections.values.firstOrNull { it.ocppSession === previous } ?: return
        try {
            stale.transport.close(REPLACED)
        } catch (failure: Exception) {
            // 이미 죽은 소켓일 수 있다. 옛 연결 정리 실패가 새 연결 수립을 깨뜨리면 안 된다.
            log.warn("교체된 연결의 소켓을 닫지 못했다: station={}", previous.stationId, failure)
        }
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val connection = connections[session.id] ?: return

        connection.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                connection.ocppSession.receive(message.payload)
            } catch (e: Throwable) {
                // 전송 실패나 예기치 못한 오류로 연결 처리가 통째로 멈추지 않게 한다.
                // 프로토콜 오류는 이미 세션이 CALLERROR 로 답했고 여기까지 오지 않는다.
                log.warn("프레임 처리 실패: station={}", connection.principal.stationId, e)
            }
        }
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        val connection = connections[session.id] ?: return
        log.warn("전송 오류: station={}", connection.principal.stationId, exception)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val connection = connections.remove(session.id) ?: return

        connection.ocppSession.close()

        // **그 세션이 아직 등록된 것일 때만** 지운다. 재접속이 빠르면 새 세션이 이미 등록된
        // 뒤에 옛 연결의 종료가 도착한다 (M4 SessionRegistry.unregister 참조).
        val wasCurrent = sessions.unregister(connection.ocppSession)

        connection.scope.cancel("connection closed: $status")

        log.info(
            "연결 종료: station={} status={} 현재세션여부={}",
            connection.principal.stationId, status, wasCurrent,
        )
    }

    /**
     * 연결 하나가 들고 있는 것 전부.
     *
     * [scope] 는 연결의 생명주기와 같다. 종료 시 취소되므로 처리 중이던 CALL 은 중단되고,
     * M4 가 멱등 원장의 흔적을 지운다 — 재접속 후 재전송하면 다시 처리된다.
     *
     * [transport] 를 들고 있는 이유는 교체된 연결의 소켓을 닫기 위해서다. 송신과 닫기가
     * 겹치지 않게 원본이 아니라 직렬화 데코레이터를 그대로 쥔다.
     */
    private class StationConnection(
        val principal: StationPrincipal,
        val ocppSession: OcppSession,
        val transport: WebSocketSession,
        val scope: CoroutineScope,
    )

    private companion object {
        /** 송신이 이 시간을 넘겨 막히면 세션을 끊는다. 느린 상대 하나가 버퍼를 무한히 물고 있지 못하게 한다. */
        const val SEND_TIME_LIMIT_MILLIS = 10_000

        /** 송신 버퍼 상한 = 텍스트 프레임 상한 × 이 값. */
        const val SEND_BUFFER_FACTOR = 8

        /** 교체로 닫는 것은 정상 종료다 — 상대가 재접속을 정당하게 한 것이고 우리가 옛 연결을 정리한다. */
        val REPLACED: CloseStatus = CloseStatus.NORMAL.withReason("replaced by a new connection")
    }
}
