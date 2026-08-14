package dev.swapve.ocpp.session

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.ocpp.rpc.DecodeOutcome
import dev.swapve.ocpp.rpc.MessageIds
import dev.swapve.ocpp.rpc.OcppFrame
import dev.swapve.ocpp.rpc.OcppFrameCodec
import dev.swapve.ocpp.rpc.RpcErrorCode
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.schema.PayloadValidation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 스테이션 하나의 **연결 하나**를 나타내는 OCPP-J 세션 (Part 4 §4.1).
 *
 * ### 전송을 하지 않는다
 *
 * 이 클래스는 프레임 한 줄을 문자열로 만들어 [transmit] 에 넘길 뿐이다. WebSocket 도,
 * Spring 도, Netty 도 모른다 (PLAN §6 설계원칙 1). 추상 전송 SPI 를 두지 않고 함수 하나를
 * 받는 이유도 같다 — 구현체 하나짜리 인터페이스는 확장이 아니라 부채다 (PLAN §11.0).
 *
 * ### 현재 시각을 조회하지 않는다
 *
 * 시각은 [clock] 에서, 타임아웃은 코루틴의 시간에서 온다. 덕분에 타임아웃 테스트가 가상
 * 시간으로 즉시 끝난다 — 실제로 몇 초를 기다리는 테스트는 느린 데다 간헐적으로 깨진다.
 *
 * ### 동기성 (Part 4 §4.1.1)
 *
 * 앞서 보낸 CALL 이 응답되거나 타임아웃되기 전에는 다음 CALL 을 **같은 연결로** 보내지
 * 않는다(SHALL NOT). 그래서 [call] 은 연결당 하나뿐인 [callGate] 를 잡는다. 이 제약의 범위는
 * **연결당**이므로 다른 스테이션으로 보내는 것은 막지 않고, [send] 도 막지 않는다 —
 * SEND 는 응답을 기다리지 않으므로 이 규칙에 걸리지 않는다.
 *
 * 상대가 보낸 CALL 이 우리가 응답을 기다리는 중에 도착하는 것은 정상이다(양쪽 CALL 이
 * 교차할 수 있다). 수신 경로는 [callGate] 를 잡지 않으므로 구조상 막히지 않는다.
 *
 * ### 멱등과 직렬화는 세션 밖에 있다
 *
 * [ledger] 와 [serializer] 는 **여러 세션이 공유한다.** 둘 다 키가 `stationId` 라서
 * 재접속으로 세션이 바뀌어도 그대로 이어진다 (PLAN §5.4 F6, §11.5).
 *
 * @param transmit 프레임 한 줄을 내보낸다. 실패하면 예외를 던져도 된다 — 이미 멱등 원장에
 *   기록된 응답은 재접속 후 재전송으로 살아난다.
 * @param callTimeout 응답 대기 한도. 스펙은 간격을 정하지 않고 구현에 맡기되 **모바일망은
 *   유선보다 왕복이 훨씬 길다**는 점을 고려하라고 한다 (Part 4 §4.1.1). 기본
 *   [DEFAULT_CALL_TIMEOUT] 은 그 여유를 둔 값이다.
 * @param codec 상태가 없다. 여러 세션이 공유해도 된다.
 * @param validator 컴파일된 스키마를 캐시한다. **인스턴스 하나를 공유하는 것을 전제로
 *   만들어졌다** — 세션마다 새로 만들면 181 개 스키마를 세션 수만큼 다시 파싱한다.
 */
class OcppSession(
    val stationId: String,
    private val transmit: suspend (String) -> Unit,
    private val onCall: OcppCallHandler,
    private val eventSink: OcppEventSink,
    private val ledger: InboundCallLedger,
    private val serializer: StationSerializer,
    private val clock: Clock,
    private val onSend: OcppSendHandler = { _, _ -> },
    private val codec: OcppFrameCodec = OcppFrameCodec(),
    private val validator: OcppPayloadValidator = OcppPayloadValidator(),
    private val callTimeout: Duration = DEFAULT_CALL_TIMEOUT,
) {

    private class PendingCall(val action: String, val response: CompletableDeferred<OcppResult>)

    /** 우리가 보내고 응답을 기다리는 CALL. CALLRESULT 에는 action 이 없어서 여기서 찾는다 (Part 4 §4.1.6). */
    private val pending = ConcurrentHashMap<String, PendingCall>()

    /** Part 4 §4.1.1 — 연결당 in-flight CALL 은 하나뿐이다. */
    private val callGate = Mutex()

    @Volatile
    private var closed = false

    // ------------------------------------------------------------------ 발신

    /**
     * CALL 을 보내고 응답을 기다린다.
     *
     * 앞선 CALL 이 끝나기 전이면 **여기서 기다린다** (Part 4 §4.1.1). 거부하지 않고 줄을
     * 세우는 이유: 호출자가 "지금은 안 된다"를 받아 재시도 루프를 직접 짜게 만드는 것은
     * 같은 코드를 호출부마다 반복시키는 일이다.
     *
     * 예외를 던지지 않는다. 전 경우가 [OcppResult] 값이다.
     */
    suspend fun call(call: OcppCall): OcppResult {
        if (closed) return OcppResult.NotConnected(stationId)

        return callGate.withLock {
            val messageId = MessageIds.next()
            val waiter = PendingCall(call.action, CompletableDeferred())
            pending[messageId] = waiter

            try {
                emit(OcppFrame.Call(messageId, call.action, call.payload), call.action)
            } catch (e: Throwable) {
                pending.remove(messageId)
                throw e
            }

            // 가상 시간에서도 그대로 동작한다 — 테스트가 실제로 기다리지 않는 지점이다.
            val result = withTimeoutOrNull(callTimeout) { waiter.response.await() }
            pending.remove(messageId)
            result ?: OcppResult.TimedOut(messageId)
        }
    }

    /**
     * SEND 를 보낸다 (Part 4 §4.2.4).
     *
     * 응답을 기다리지 않으므로 §4.1.1 동기성 제약에 걸리지 않는다. **pending CALL 이 있어도
     * 즉시 나간다.**
     *
     * @return 발번된 messageId. 이벤트 로그와 대조할 때 쓴다.
     */
    suspend fun send(action: String, payload: ObjectNode): String {
        val messageId = MessageIds.next()
        emit(OcppFrame.Send(messageId, action, payload), action)
        return messageId
    }

    /**
     * 연결이 끝났다. 응답을 기다리던 CALL 들을 [OcppResult.NotConnected] 로 깨운다.
     *
     * **멱등 원장은 건드리지 않는다.** 재접속 후 재전송이 오면 저장된 응답을 그대로 다시
     * 보내야 하기 때문이다 (PLAN §5.4 F6).
     */
    fun close() {
        closed = true
        val waiters = pending.values.toList()
        pending.clear()
        waiters.forEach { it.response.complete(OcppResult.NotConnected(stationId)) }
    }

    // ------------------------------------------------------------------ 수신

    /**
     * 프레임 한 줄을 받는다.
     *
     * **도착 순서대로 호출해야 한다.** 순서 보장은 [StationSerializer] 가 하지만, 이미
     * 뒤섞여 들어온 호출을 되돌려 놓지는 못한다.
     *
     * 처리 흐름은 M1 → M2 → 상위 계층 순이다:
     * 원문 → 디코딩 → (Ignored 면 무시 / Malformed 면 CALLERROR) → 스키마 검증 →
     * (통과하면 상위 계층 / 실패하면 그 errorCode 로 CALLERROR).
     */
    suspend fun receive(text: String) {
        val outcome = codec.decode(text)
        logInbound(outcome, text)

        when (outcome) {
            // 표에 없는 메시지 타입. 회신하지 않는다 (Part 4 §4.1.3 + errata 2026-06 §4.1).
            is DecodeOutcome.Ignored -> Unit

            is DecodeOutcome.Malformed -> emit(
                OcppFrame.CallError(outcome.messageId, outcome.errorCode.name, outcome.errorDescription, emptyPayload()),
                action = null,
            )

            is DecodeOutcome.Decoded -> when (val frame = outcome.frame) {
                is OcppFrame.Call -> handleCall(frame)
                is OcppFrame.Send -> handleSend(frame)
                is OcppFrame.CallResult -> handleCallResult(frame)
                is OcppFrame.CallError -> handleCallError(frame)
                // 우리가 보낸 CALLRESULT 를 상대가 처리하지 못했다는 통보다 (Part 4 §4.2.5).
                // 우리 pending 과는 짝이 아니다 — 기록만 남긴다.
                is OcppFrame.CallResultError -> Unit
            }
        }
    }

    /**
     * 수신 CALL 처리 — 멱등 판정이 먼저고, 스테이션 직렬화가 그다음이다.
     *
     * 순서가 중요하다. 직렬화 안에서 판정하면 [CallClaim.InFlight] 가 영영 나올 수 없고
     * (앞 메시지가 끝나야 뒤가 들어오니까), 재전송이 앞 메시지 뒤에 줄을 서서 불필요하게
     * 기다린다. 밖에서 판정하면 재전송은 즉시 답을 받고 돌아간다.
     *
     * 판정 자체는 잠깐의 임계구역이라 도착 순서를 흐트러뜨리지 않는다.
     */
    private suspend fun handleCall(frame: OcppFrame.Call) {
        val key = InboundCallKey(stationId, frame.messageId)

        when (val claim = ledger.claim(key)) {
            // 부수효과는 이미 한 번 일어났다. 상위 계층을 다시 부르지 않는다 (PLAN §5.4 F4·F6).
            is CallClaim.AlreadyAnswered -> emitRaw(claim.responseText, frame.action, frame.messageId)

            // Part 4 §4.2.3 — "이미 같은 고유 식별자의 메시지를 처리 중".
            // Table 9 에 이 상황에 정확히 대응하는 코드가 없다. 표의 정의상 "더 구체적인
            // 코드로 덮이지 않는 그 밖의 오류"인 GenericError 가 맞는 자리다.
            CallClaim.InFlight -> emit(
                callError(frame.messageId, RpcErrorCode.GenericError, "already processing message ${frame.messageId}"),
                frame.action,
            )

            CallClaim.Fresh -> serializer.withStation(stationId) {
                val response = try {
                    respondTo(frame)
                } catch (e: Throwable) {
                    // 흔적을 지운다 — 재전송으로 다시 시도할 수 있어야 한다.
                    ledger.release(key)
                    emit(callError(frame.messageId, RpcErrorCode.InternalError, describe(e)), frame.action)
                    return@withStation
                }

                val text = codec.encode(response)
                // 내보내기 전에 기록한다. 전송이 실패해도 재접속 뒤 재전송으로 같은 답이 나가야 한다.
                ledger.complete(key, text)
                emitRaw(text, frame.action, frame.messageId)
            }
        }
    }

    /** 스키마 검증 → 상위 계층. 둘 중 어디서 막히든 회신할 프레임 하나로 수렴한다. */
    private suspend fun respondTo(frame: OcppFrame.Call): OcppFrame {
        val validation = validator.validateCall(frame.action, frame.payload)
        if (validation is PayloadValidation.Invalid) {
            return OcppFrame.CallError(frame.messageId, validation.errorCode.name, validation.errorDescription, emptyPayload())
        }

        return when (val answer = onCall(stationId, OcppCall(frame.action, frame.payload))) {
            is InboundResponse.Respond -> OcppFrame.CallResult(frame.messageId, answer.payload)
            is InboundResponse.Fail -> OcppFrame.CallError(
                frame.messageId,
                answer.errorCode.name,
                OcppPayloadValidator.truncate(answer.errorDescription),
                answer.errorDetails,
            )
        }
    }

    /**
     * 수신 SEND 처리 (Part 4 §4.2.4).
     *
     * **어떤 경우에도 회신하지 않는다(SHALL NOT).** 스키마를 통과하지 못해도 마찬가지다 —
     * 알릴 방법이 없으니 이벤트 로그에 원문이 남은 것으로 끝낸다.
     *
     * 멱등 원장에 넣지 않는다. SEND 는 응답이 없어서 상대가 재전송할 계기 자체가 없다.
     */
    private suspend fun handleSend(frame: OcppFrame.Send) {
        if (validator.validateSend(frame.action, frame.payload) is PayloadValidation.Invalid) return
        serializer.withStation(stationId) {
            onSend(stationId, OcppCall(frame.action, frame.payload))
        }
    }

    /**
     * 수신 CALLRESULT 처리.
     *
     * **스테이션 직렬화를 타지 않는다.** 응답은 상위 계층을 부르지 않고 기다리던 [call] 을
     * 깨울 뿐이라 순서를 보장할 대상이 아니고, 직렬화를 태우면 스테이션 락을 쥔 상위 계층이
     * 스스로 CALL 을 보내고 응답을 기다리는 순간 교착한다.
     *
     * 짝이 없으면(타임아웃 뒤 늦게 도착) 기록만 남기고 버린다. **아무것도 깨지지 않는다.**
     */
    private suspend fun handleCallResult(frame: OcppFrame.CallResult) {
        val waiter = pending.remove(frame.messageId) ?: return

        // CALLRESULT 프레임에는 action 이 없다. 원래 CALL 의 action 을 pending 표에서 찾아
        // 넘긴다 — M2 가 validateCallResult 를 그렇게 만든 이유다 (Part 4 §4.1.6).
        val validation = validator.validateCallResult(waiter.action, frame.payload)
        if (validation is PayloadValidation.Invalid) {
            emit(
                OcppFrame.CallResultError(
                    frame.messageId,
                    validation.errorCode.name,
                    validation.errorDescription,
                    emptyPayload(),
                ),
                waiter.action,
            )
            waiter.response.complete(
                OcppResult.InvalidResponse(frame.messageId, validation.errorCode, validation.errorDescription),
            )
            return
        }

        waiter.response.complete(OcppResult.Accepted(frame.messageId, frame.payload))
    }

    private fun handleCallError(frame: OcppFrame.CallError) {
        val waiter = pending.remove(frame.messageId) ?: return
        waiter.response.complete(
            OcppResult.Rejected(frame.messageId, frame.errorCode, frame.errorDescription, frame.errorDetails),
        )
    }

    // ------------------------------------------------------------------ 기록과 송출

    /**
     * 수신 원문을 남긴다 (PLAN §11.1).
     *
     * 디코딩 **뒤에** 부르는 이유는 action/messageId 를 채우기 위해서다. 원문은 어느 쪽이든
     * 그대로 보존되므로 정보가 버려지지 않는다.
     */
    private fun logInbound(outcome: DecodeOutcome, text: String) {
        val (messageId, action) = when (outcome) {
            // 메시지 타입을 모르면 messageId 도 그 규약대로 읽혔다고 볼 수 없다.
            // 원문은 payload 에 통째로 남는다.
            is DecodeOutcome.Ignored -> OcppFrameCodec.UNREADABLE_MESSAGE_ID to null
            is DecodeOutcome.Malformed -> outcome.messageId to null
            is DecodeOutcome.Decoded -> outcome.frame.messageId to actionOf(outcome.frame)
        }
        eventSink.append(stationId, MessageDirection.INBOUND, action, messageId, text, clock.instant())
    }

    /** CALLRESULT/CALLERROR 에는 action 이 없다. 짝이 되는 CALL 에서 가져온다. 못 찾으면 `null`. */
    private fun actionOf(frame: OcppFrame): String? = when (frame) {
        is OcppFrame.Call -> frame.action
        is OcppFrame.Send -> frame.action
        is OcppFrame.CallResult, is OcppFrame.CallError -> pending[frame.messageId]?.action
        is OcppFrame.CallResultError -> null
    }

    private suspend fun emit(frame: OcppFrame, action: String?) =
        emitRaw(codec.encode(frame), action, frame.messageId)

    /**
     * 원문 한 줄을 기록하고 내보낸다.
     *
     * 기록이 먼저다. 나갔는데 기록이 없는 것보다, 기록됐는데 못 나간 쪽이 낫다 — 전자는
     * 로그로 상태를 재구성할 수 없게 만들지만(§11.1), 후자는 재전송으로 수습된다.
     */
    private suspend fun emitRaw(text: String, action: String?, messageId: String) {
        eventSink.append(stationId, MessageDirection.OUTBOUND, action, messageId, text, clock.instant())
        transmit(text)
    }

    private fun callError(messageId: String, code: RpcErrorCode, description: String) =
        OcppFrame.CallError(messageId, code.name, OcppPayloadValidator.truncate(description), emptyPayload())

    private fun describe(e: Throwable): String = "${e::class.simpleName}: ${e.message.orEmpty()}"

    private fun emptyPayload(): ObjectNode = JsonNodeFactory.instance.objectNode()

    companion object {
        /**
         * 기본 응답 대기 한도.
         *
         * 스펙은 간격을 구현에 맡기면서 **모바일망은 유선보다 왕복이 훨씬 길다**는 점을
         * 고려하라고 한다 (Part 4 §4.1.1). 배터리 교환 스테이션은 상당수가 셀룰러로 붙고,
         * 재전송 폭주보다는 한 번 넉넉히 기다리는 쪽이 안전하므로 30 초로 둔다.
         */
        val DEFAULT_CALL_TIMEOUT: Duration = 30.seconds
    }
}
