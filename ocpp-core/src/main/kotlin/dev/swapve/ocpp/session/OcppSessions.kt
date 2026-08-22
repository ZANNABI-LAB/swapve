package dev.swapve.ocpp.session

import dev.swapve.ocpp.rpc.OcppFrameCodec
import dev.swapve.ocpp.schema.OcppPayloadValidator
import java.time.Clock
import kotlin.time.Duration

/**
 * Opens [OcppSession]s with the shared pieces already wired.
 *
 * ### What this saves the caller
 *
 * A session takes seven things, and four of them have effectively one right answer: the
 * idempotency ledger and the station serializer are keyed by `stationId` and must **outlive any
 * one connection**, while the codec and the validator are stateless (or cache-only) and are meant
 * to be shared process-wide — building a validator per session would reparse all 181 schemas that
 * many times.
 *
 * Getting that wrong is not loud. A ledger rebuilt on reconnect makes a retransmission look like
 * a new message, and **a battery swap is then counted twice.** This class holds those four so
 * that reconnecting is correct by construction: call [open] again with the same `stationId` and
 * the ledger carries across.
 *
 * ### What it deliberately does not do
 *
 * It does not own connections. Establishing them, reconnecting, and calling
 * [OcppSession.receive] in arrival order remain the caller's job — this only removes the wiring
 * that has one answer, not the decisions that have several.
 *
 * [eventSink] is required rather than defaulted. An in-memory default would let a system reach
 * production writing its OCPP log to a volatile list, and that log is the only basis on which
 * derived state can be rebuilt.
 *
 * The [OcppSession] constructor stays public. Anything this class settles can still be settled
 * by hand when a caller needs to.
 *
 * Thread-safe, and intended to be held as one instance.
 *
 * ```
 * val sessions = OcppSessions(Clock.systemUTC(), eventSink = myEventLog)
 * val session = sessions.open(stationId, transmit = { ws.send(it); TransmitOutcome.Delivered }, onCall = ::handle)
 * ```
 */
class OcppSessions(
    private val clock: Clock,
    private val eventSink: OcppEventSink,
    private val callTimeout: Duration = OcppSession.DEFAULT_CALL_TIMEOUT,
    private val ledger: InboundCallLedger = InboundCallLedger(),
    private val serializer: StationSerializer = StationSerializer(),
    private val codec: OcppFrameCodec = OcppFrameCodec(),
    private val validator: OcppPayloadValidator = OcppPayloadValidator(),
) {

    /**
     * Opens a session for one connection of [stationId].
     *
     * Call it again for the same station after a reconnect — the ledger and the serializer are
     * the same ones, which is what keeps idempotency and ordering intact across the gap.
     *
     * @param transmit sends one frame line and says whether it left ([OcppTransmit]). A dead
     *   connection is [TransmitOutcome.Gone], not an exception. A response already recorded
     *   survives an undelivered send, and a retransmission after reconnect gets it back.
     * @param onCall handles an incoming CALL. Called serially per station.
     * @param onSend handles an incoming SEND. A SEND is never answered.
     */
    fun open(
        stationId: String,
        transmit: OcppTransmit,
        onCall: OcppCallHandler,
        onSend: OcppSendHandler = { _, _ -> },
    ): OcppSession = OcppSession(
        stationId = stationId,
        transmit = transmit,
        onCall = onCall,
        eventSink = eventSink,
        ledger = ledger,
        serializer = serializer,
        clock = clock,
        onSend = onSend,
        codec = codec,
        validator = validator,
        callTimeout = callTimeout,
    )
}
