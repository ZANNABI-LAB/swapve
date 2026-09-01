package dev.swapve.ocpp.session

import dev.swapve.ocpp.rpc.DecodeOutcome
import dev.swapve.ocpp.rpc.OcppFrame
import dev.swapve.ocpp.rpc.OcppFrameCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

/**
 * Sends one frame line and reports whether it left.
 *
 * The [java.util.concurrent.CompletableFuture] is not decoration. A blocking implementation
 * wraps its result in [CompletableFuture.completedFuture], and **that one line is the point**:
 * it says in the consumer's own code that this callback holds the calling thread. An
 * implementation built on `AsynchronousSocketChannel` or `HttpClient.sendAsync` hands back the
 * future it already has and holds nothing.
 *
 * Report a dead connection as [TransmitOutcome.Gone] rather than by completing exceptionally —
 * the session treats it as an ordinary outcome, and an exception breaks the promise that every
 * outcome of [OcppSessionAsync.call] comes back as a value.
 */
fun interface AsyncTransmit {
    fun send(text: String): CompletableFuture<TransmitOutcome>
}

/**
 * Handles an incoming CALL.
 *
 * Called **serially per station**, so an implementation needs no station-scoped lock of its own.
 *
 * Completing the future exceptionally clears the idempotency record for that messageId and
 * answers the peer with [dev.swapve.ocpp.rpc.RpcErrorCode.InternalError] — meaning **a
 * retransmission will be processed again**.
 */
fun interface AsyncCallHandler {
    fun handle(stationId: String, call: OcppCall): CompletableFuture<InboundResponse>
}

/**
 * Handles an incoming SEND (Part 4 §4.2.4).
 *
 * **A SEND SHALL NOT be answered with a CALLRESULT or CALLERROR**, so there is nothing to
 * return but completion.
 */
fun interface AsyncSendHandler {
    fun handle(stationId: String, send: OcppCall): CompletableFuture<Void>
}

/**
 * Opens sessions for consumers that are **not written in Kotlin**.
 *
 * ### Why this exists
 *
 * L1 framing and L2 schema validation were always callable from Java. L3 was not, and the wall
 * was measured rather than guessed: `callTimeout` is a [kotlin.time.Duration], which is a value
 * class, so the constructor that takes it cannot be name-mangled — a constructor is called
 * `<init>` — and Kotlin drops the all-arguments constructor to `private`, exposing only ones
 * that demand a `DefaultConstructorMarker`. **`suspend` was never the wall.** Everything past
 * the constructor already worked from Java; what it cost was a hand-written `Continuation`, a
 * `COROUTINE_SUSPENDED` comparison and unwrapping `kotlin.Result.Failure` at every call site.
 * This class is exactly the list of things a consumer had to write, written once.
 *
 * Kotlin callers should keep using [OcppSessions] — it is the same session with none of the
 * futures in the way.
 *
 * ### It does not own threads
 *
 * You pass an [Executor] and you keep it. [close] cancels the coroutines this object started
 * and **does not shut your executor down**, so the ownership rule the session layer already
 * states — *the scope and its lifetime are yours* — is unchanged, only expressed in a type Java
 * already has.
 *
 * **On JDK 21 and later, pass `Executors.newVirtualThreadPerTaskExecutor()`.** Waiting on a
 * returned future then costs a virtual thread rather than a platform one, which is what closes
 * the gap with a Kotlin caller, who pays no thread at all while suspended. Nothing in this
 * library pins a virtual thread: the only `synchronized` blocks guard in-memory collections and
 * perform no I/O.
 *
 * ### What it still cannot give you
 *
 * **Cancellation does not travel downwards.** A Kotlin caller's `call` is a child of the
 * caller's job and dies with it. Java has no ambient context to inherit, so a future is
 * cancelled only by calling [java.util.concurrent.Future.cancel] or by [close]. Nothing here
 * leaks as long as [close] runs; nothing here notices that the work became pointless.
 *
 * @param executor runs the coroutines. Yours to create and to shut down.
 * @param clock the session reads time only through this.
 * @param eventSink required, not defaulted. An in-memory default would let a system reach
 *   production writing its OCPP log to a volatile list, and that log is the only basis on which
 *   derived state can be rebuilt.
 * @param callTimeout how long to await a response, as a [java.time.Duration].
 */
class OcppSessionsAsync private constructor(
    private val sessions: OcppSessions,
    private val codec: OcppFrameCodec,
    private val scope: CoroutineScope,
) : AutoCloseable {

    companion object {

        /** The default of [OcppSession.DEFAULT_CALL_TIMEOUT], as a type Java can name. */
        @JvmField
        val DEFAULT_CALL_TIMEOUT: JavaDuration =
            JavaDuration.ofNanos(OcppSession.DEFAULT_CALL_TIMEOUT.inWholeNanoseconds)

        @JvmStatic
        @JvmOverloads
        fun using(
            executor: Executor,
            clock: Clock,
            eventSink: OcppEventSink,
            callTimeout: JavaDuration = DEFAULT_CALL_TIMEOUT,
        ): OcppSessionsAsync {
            // 세션이 쓰는 것과 같은 종류의 코덱이다. 상태가 없어 공유해도 되고, 여기서는
            // 프레임을 응답과 요청으로 가르는 데만 쓴다 ([OcppSessionAsync.receive]).
            val codec = OcppFrameCodec()
            return OcppSessionsAsync(
                sessions = OcppSessions(
                    clock = clock,
                    eventSink = eventSink,
                    callTimeout = callTimeout.toKotlinDuration(),
                    codec = codec,
                ),
                codec = codec,
                scope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob()),
            )
        }
    }

    /**
     * Opens a session for one connection of [stationId].
     *
     * Call it again for the same station after a reconnect — the idempotency ledger and the
     * per-station serializer are the same ones, which is what keeps a retransmitted message
     * from being counted twice across the gap.
     */
    @JvmOverloads
    fun open(
        stationId: String,
        transmit: AsyncTransmit,
        onCall: AsyncCallHandler,
        onSend: AsyncSendHandler = AsyncSendHandler { _, _ -> CompletableFuture.completedFuture(null) },
    ): OcppSessionAsync = OcppSessionAsync(
        codec,
        sessions.open(
            stationId = stationId,
            transmit = { text -> transmit.send(text).await() },
            onCall = { id, call -> onCall.handle(id, call).await() },
            onSend = { id, send -> onSend.handle(id, send).await() },
        ),
        scope,
    )

    /**
     * Cancels the coroutines this object started. **Your executor is not shut down** — you
     * created it, so you close it.
     */
    override fun close() {
        scope.cancel()
    }
}

/**
 * One connection, with futures instead of `suspend`.
 *
 * ### Requests keep their order; responses must not wait for them
 *
 * A Kotlin caller calls `receive` in arrival order itself. Here that would be an easy mistake to
 * make — a future looks like something you may fire several of at once — so [receive] chains
 * **requests** onto the one before. A frame that fails does not stall the ones behind it.
 *
 * **CALLRESULT, CALLERROR and CALLRESULTERROR skip the chain**, and that is not an optimisation.
 * A response carries a `messageId`, so it has no order to keep; and putting it in line behind a
 * slow request handler is the deadlock `OcppSession.handleCallResult` exists to avoid — the
 * answer to an outbound `call` would sit in the queue until that call had already timed out.
 * Written the other way, ordinary two-way traffic produces timeouts that never happened on
 * the wire.
 */
class OcppSessionAsync internal constructor(
    private val codec: OcppFrameCodec,
    private val session: OcppSession,
    parent: CoroutineScope,
) : AutoCloseable {

    // 부모에 매달린 자식이다 — 부모를 닫으면 함께 죽고, 이 세션만 닫으면 이것만 죽는다.
    private val scope = CoroutineScope(
        parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]),
    )

    private val chainLock = Any()
    private var tail: CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    /** The station this connection belongs to. */
    val stationId: String get() = session.stationId

    /**
     * Sends a CALL and completes with the response.
     *
     * Never completes exceptionally for a protocol outcome: a timeout is [OcppResult.TimedOut]
     * and a dead connection is [OcppResult.NotConnected], because callers act on the outcome
     * rather than on the wording of an exception.
     *
     * If an earlier CALL on this connection is still outstanding this waits for it — one
     * in-flight CALL per connection is the rule (Part 4 §4.1.1), and queuing rather than
     * rejecting keeps every call site from writing the same retry loop.
     */
    fun call(call: OcppCall): CompletableFuture<OcppResult> = scope.future { session.call(call) }

    /**
     * Feeds one received frame line to the session.
     *
     * Completes when that frame has been processed. Requests are chained after the requests
     * handed in before them, so passing frames straight from a socket read loop is correct
     * without any locking of your own; responses are started at once, for the reason given on
     * this class.
     */
    fun receive(text: String): CompletableFuture<Void> {
        if (isResponse(text)) return started(text)

        return synchronized(chainLock) {
            val started = tail.thenCompose { started(text) }
            // 실패한 프레임이 뒤를 막지 않게 결과를 지우고 이어붙인다.
            tail = started.handle { _, _ -> null }
            started
        }
    }

    private fun started(text: String): CompletableFuture<Void> =
        scope.future { session.receive(text) }.thenAccept { }

    /**
     * 읽을 수 없는 줄은 요청으로 친다 — 세션이 CALLERROR 로 답할 것이고, 그 답은 순서를
     * 지키는 편이 낫다. 판정을 여기서 틀려도 세션의 처리는 달라지지 않는다.
     */
    private fun isResponse(text: String): Boolean {
        val frame = (codec.decode(text) as? DecodeOutcome.Decoded)?.frame ?: return false
        return frame is OcppFrame.CallResult ||
            frame is OcppFrame.CallError ||
            frame is OcppFrame.CallResultError
    }

    /**
     * Stops the session from sending anything further and **cancels the work this session
     * started** — a handler future that never completes would otherwise hold the request chain
     * open for good. Does not close your transport, and does not touch the executor.
     */
    override fun close() {
        session.close()
        scope.cancel()
    }
}
