package dev.swapve.ocpp.session

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.ocpp.rpc.RpcErrorCode

/**
 * One request to send, or one that arrived — `(action, payload)`.
 *
 * **No messageId.** Ids are issued at transmit time by [dev.swapve.ocpp.rpc.MessageIds]
 * (Part 4 §4.1.4). Letting callers supply one would make every caller responsible for the
 * uniqueness rule that spans reconnects.
 *
 * The spec says a retransmission MAY reuse the original messageId, but the session does not
 * retransmit at all — it reports the timeout to the caller and stops there.
 */
data class OcppCall(
    val action: String,
    val payload: ObjectNode,
) {
    init {
        require(action.isNotBlank()) { "action must not be blank (Part 4 §4.1.6)" }
    }
}

/**
 * How one CALL ended.
 *
 * **Never throws.** A rejection, a timeout, a missing connection — all of them are values, so
 * callers handle every case in one `when` instead of a `try`/`catch`. "Not connected" in
 * particular is ordinary operation, not an exceptional event: stations drop off all the time.
 */
sealed interface OcppResult {

    /** A CALLRESULT arrived and satisfied the official schema. */
    data class Accepted(val messageId: String, val payload: ObjectNode) : OcppResult

    /** A CALLERROR arrived — the peer could not handle the request (Part 4 §4.2.3). */
    data class Rejected(
        val messageId: String,
        val errorCode: String,
        val errorDescription: String,
        val errorDetails: ObjectNode,
    ) : OcppResult {
        /** The matching code if it is in the table (Part 4 §4.3), otherwise `null`. The raw string stays in [errorCode]. */
        val knownErrorCode: RpcErrorCode? get() = RpcErrorCode.parse(errorCode)
    }

    /**
     * A CALLRESULT arrived but did not satisfy the official schema.
     *
     * The session tells the peer with a `CALLRESULTERROR` (type 5) before returning this
     * (Part 4 §4.2.5).
     */
    data class InvalidResponse(
        val messageId: String,
        val errorCode: RpcErrorCode,
        val errorDescription: String,
    ) : OcppResult

    /**
     * Neither a response nor an error arrived in time (Part 4 §4.1.1).
     *
     * The pending entry for that messageId is dropped at this point. A CALLRESULT that arrives
     * later is treated as an unmatched response: recorded, then discarded.
     */
    data class TimedOut(val messageId: String) : OcppResult

    /**
     * That station has no connection.
     *
     * **A result, not an exception.** Stations disconnect as a matter of course.
     */
    data class NotConnected(val stationId: String) : OcppResult
}

/**
 * What the layer above answers an incoming CALL with.
 *
 * That layer knows nothing of frames or messageIds. It decides **what to answer**; the session
 * decides which frame carries it and when it goes out.
 */
sealed interface InboundResponse {

    /** Answer with a CALLRESULT. */
    data class Respond(val payload: ObjectNode) : InboundResponse {
        companion object {
            /** *"Empty response by CSMS to confirm receipt"* — as `BatterySwapResponse` is. */
            fun empty(): Respond = Respond(JsonNodeFactory.instance.objectNode())
        }
    }

    /** Answer with a CALLERROR (Part 4 §4.2.3). */
    data class Fail(
        val errorCode: RpcErrorCode,
        val errorDescription: String,
        val errorDetails: ObjectNode = JsonNodeFactory.instance.objectNode(),
    ) : InboundResponse
}

/**
 * Handles an incoming CALL.
 *
 * Called **serially per station** ([StationSerializer]), so an implementation needs no
 * station-scoped lock of its own.
 *
 * Throwing clears the idempotency record for that messageId and answers the peer with
 * [RpcErrorCode.InternalError] — meaning **a retransmission will be processed again**.
 */
typealias OcppCallHandler = suspend (stationId: String, call: OcppCall) -> InboundResponse

/**
 * Handles an incoming SEND (Part 4 §4.2.4).
 *
 * Returns nothing. **A SEND SHALL NOT be answered with a CALLRESULT or CALLERROR.**
 */
typealias OcppSendHandler = suspend (stationId: String, send: OcppCall) -> Unit
