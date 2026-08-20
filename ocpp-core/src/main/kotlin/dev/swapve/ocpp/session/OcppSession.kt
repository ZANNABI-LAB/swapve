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
 * An OCPP-J session — **one connection** of one station (Part 4 §4.1).
 *
 * ### It does not transmit
 *
 * This class turns a frame into one line of text and hands it to [transmit]. It knows nothing
 * of WebSocket, Spring or Netty. Taking a function rather than defining a transport SPI is the
 * same decision: an interface with a single implementation is debt, not extensibility.
 *
 * ### It does not read the current time
 *
 * The time comes from [clock] and timeouts come from coroutine time. That is what lets timeout
 * tests finish instantly on virtual time — tests that really wait several seconds are slow and
 * intermittently flaky.
 *
 * ### Synchronicity (Part 4 §4.1.1)
 *
 * A next CALL SHALL NOT go out **on the same connection** before the previous one is answered
 * or times out, so [call] takes [callGate], of which there is one per connection. The
 * constraint is **per connection**: it does not hold up other stations, and it does not hold up
 * [send], which expects no response and so falls outside the rule.
 *
 * A CALL arriving from the peer while we await our own is normal — both sides may have CALLs in
 * flight. The receive path never takes [callGate], so it cannot be blocked by one.
 *
 * ### Idempotency and serialisation live outside the session
 *
 * [ledger] and [serializer] are **shared across sessions**. Both are keyed by `stationId`, so
 * they carry across a reconnect that replaces the session — which is exactly when a station
 * retransmits and idempotency has to still hold.
 *
 * @param transmit sends one frame line. It may throw — a response already recorded in the
 *   ledger survives, and a retransmission after reconnect gets it back.
 * @param callTimeout how long to await a response. The spec leaves the interval to the
 *   implementation but asks that **mobile networks have far longer round trips than wired ones**
 *   be taken into account (Part 4 §4.1.1). [DEFAULT_CALL_TIMEOUT] leaves that room.
 * @param codec stateless. Sessions may share one.
 * @param validator caches compiled schemas. **Built to be shared as a single instance** —
 *   one per session would reparse all 181 schemas that many times.
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

    /** CALLs we sent and are awaiting. A CALLRESULT carries no action, so it is looked up here (Part 4 §4.1.6). */
    private val pending = ConcurrentHashMap<String, PendingCall>()

    /** Part 4 §4.1.1 — one in-flight CALL per connection, no more. */
    private val callGate = Mutex()

    @Volatile
    private var closed = false

    /**
     * Sends a CALL and awaits the response.
     *
     * If an earlier CALL is still outstanding this **waits here** (Part 4 §4.1.1). Queuing
     * rather than rejecting is deliberate: handing callers a "not now" would make every call
     * site write the same retry loop.
     *
     * Never throws. Every outcome is an [OcppResult].
     */
    suspend fun call(call: OcppCall): OcppResult {
        if (closed) return OcppResult.NotConnected(stationId)

        return callGate.withLock {
            val messageId = MessageIds.newId()
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
     * Sends a SEND (Part 4 §4.2.4).
     *
     * It expects no response, so the §4.1.1 synchronicity rule does not apply — **it goes out
     * immediately even with a CALL pending.**
     *
     * @return the messageId that was issued, for matching against the event log.
     */
    suspend fun send(action: String, payload: ObjectNode): String {
        val messageId = MessageIds.newId()
        emit(OcppFrame.Send(messageId, action, payload), action)
        return messageId
    }

    /**
     * The connection ended. Wakes every awaiting CALL with [OcppResult.NotConnected].
     *
     * **Leaves the idempotency ledger alone.** When a retransmission arrives after reconnect,
     * the stored response has to go back out unchanged.
     */
    fun close() {
        closed = true
        val waiters = pending.values.toList()
        pending.clear()
        waiters.forEach { it.response.complete(OcppResult.NotConnected(stationId)) }
    }

    /**
     * Receives one frame line.
     *
     * **Must be called in arrival order.** [StationSerializer] keeps processing ordered, but it
     * cannot unscramble calls that already arrived out of order.
     *
     * The flow is: raw line → decode → (ignore, or CALLERROR if malformed) → schema validation →
     * (the layer above, or a CALLERROR carrying that errorCode).
     */
    suspend fun receive(text: String) {
        val outcome = codec.decode(text)
        logInbound(outcome, text)

        when (outcome) {
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
                is OcppFrame.CallResultError -> Unit
            }
        }
    }

    /**
     * Handles an incoming CALL — the idempotency claim comes first, station serialisation second.
     *
     * The order matters. Claiming inside the serialisation would make [CallClaim.InFlight]
     * unreachable — nothing gets in until the previous message finishes — and a retransmission
     * would queue behind that message for no reason. Claiming outside lets it get its answer and
     * leave at once.
     *
     * The claim itself is a brief critical section, so it does not disturb arrival order.
     */
    private suspend fun handleCall(frame: OcppFrame.Call) {
        val key = InboundCallKey(stationId, frame.messageId)

        when (val claim = ledger.claim(key)) {
            is CallClaim.AlreadyAnswered -> emitRaw(claim.responseText, frame.action, frame.messageId)

            CallClaim.InFlight -> emit(
                callError(frame.messageId, RpcErrorCode.GenericError, "already processing message ${frame.messageId}"),
                frame.action,
            )

            CallClaim.Fresh -> serializer.withStation(stationId) {
                val response = try {
                    respondTo(frame)
                } catch (e: Throwable) {
                    ledger.release(key)
                    emit(callError(frame.messageId, RpcErrorCode.InternalError, describe(e)), frame.action)
                    return@withStation
                }

                val text = codec.encode(response)
                ledger.complete(key, text)
                emitRaw(text, frame.action, frame.messageId)
            }
        }
    }

    /** Schema validation, then the layer above. Either can stop it, and both converge on one frame to answer with. */
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
     * Handles an incoming SEND (Part 4 §4.2.4).
     *
     * **Never answers, under any circumstance (SHALL NOT)** — including when the payload fails
     * its schema. There is no way to report it, so the raw line in the event log is where it ends.
     *
     * Not entered in the idempotency ledger: a SEND has no response, so the peer has no occasion
     * to retransmit one.
     */
    private suspend fun handleSend(frame: OcppFrame.Send) {
        if (validator.validateSend(frame.action, frame.payload) is PayloadValidation.Invalid) return
        serializer.withStation(stationId) {
            onSend(stationId, OcppCall(frame.action, frame.payload))
        }
    }

    /**
     * Handles an incoming CALLRESULT.
     *
     * **Does not go through station serialisation.** A response calls nothing above — it only
     * wakes the [call] that was waiting — so there is no order to preserve. And serialising it
     * would deadlock the moment the layer above, holding the station lock, sends its own CALL
     * and awaits the answer.
     *
     * With no match (a response arriving after its timeout) it is recorded and dropped.
     * **Nothing breaks.**
     */
    private suspend fun handleCallResult(frame: OcppFrame.CallResult) {
        val waiter = pending.remove(frame.messageId) ?: return

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

    /**
     * Records an incoming raw line.
     *
     * Called **after** decoding so that action and messageId can be filled in. The raw text is
     * preserved either way, so nothing is lost by the ordering.
     */
    private fun logInbound(outcome: DecodeOutcome, text: String) {
        val (messageId, action) = when (outcome) {
            is DecodeOutcome.Ignored -> OcppFrameCodec.UNREADABLE_MESSAGE_ID to null
            is DecodeOutcome.Malformed -> outcome.messageId to null
            is DecodeOutcome.Decoded -> outcome.frame.messageId to actionOf(outcome.frame)
        }
        eventSink.append(stationId, MessageDirection.INBOUND, action, messageId, text, clock.instant())
    }

    /** CALLRESULT and CALLERROR carry no action. It comes from the matching CALL, or `null` if there is none. */
    private fun actionOf(frame: OcppFrame): String? = when (frame) {
        is OcppFrame.Call -> frame.action
        is OcppFrame.Send -> frame.action
        is OcppFrame.CallResult, is OcppFrame.CallError -> pending[frame.messageId]?.action
        is OcppFrame.CallResultError -> null
    }

    private suspend fun emit(frame: OcppFrame, action: String?) =
        emitRaw(codec.encode(frame), action, frame.messageId)

    /**
     * Records one raw line, then sends it.
     *
     * Recording comes first. Sent-but-unrecorded is worse than recorded-but-unsent: the former
     * makes state unreconstructible from the log, while the latter is recoverable by
     * retransmission.
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
         * The default response deadline.
         *
         * The spec leaves the interval to the implementation while asking that **mobile networks
         * have far longer round trips than wired ones** be considered (Part 4 §4.1.1). Many
         * battery swap stations connect over cellular, and waiting generously once is safer than
         * a storm of retransmissions — hence 30 seconds.
         */
        val DEFAULT_CALL_TIMEOUT: Duration = 30.seconds
    }
}
