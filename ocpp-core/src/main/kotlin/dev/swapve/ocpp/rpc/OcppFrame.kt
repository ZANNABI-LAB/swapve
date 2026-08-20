package dev.swapve.ocpp.rpc

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * An OCPP-J RPC frame (Part 4 §4.2).
 *
 * The station identity (`stationId`) is **not in the frame**. It is a property of the
 * connection, settled during the WebSocket handshake (Part 4 §4.2 NOTE), so it has no place
 * here.
 */
sealed interface OcppFrame {

    /** Part 4 §4.1.4 — at most 36 characters. */
    val messageId: String

    val type: MessageType

    /**
     * A request. `[2, "<messageId>", "<action>", {<payload>}]`
     *
     * [action] is the OCPP message name **with the `Request` suffix removed** (Part 4 §4.1.6).
     * For example `BatterySwapRequest` travels as `"BatterySwap"`.
     */
    data class Call(
        override val messageId: String,
        val action: String,
        val payload: ObjectNode,
    ) : OcppFrame {
        override val type get() = MessageType.CALL
    }

    /**
     * A response. `[3, "<messageId>", {<payload>}]`
     *
     * **Carries no action field.** It is tied to its originating CALL by [messageId] alone
     * (Part 4 §4.1.6) — which is why validating one requires the caller to supply the action.
     */
    data class CallResult(
        override val messageId: String,
        val payload: ObjectNode,
    ) : OcppFrame {
        override val type get() = MessageType.CALL_RESULT
    }

    /**
     * The request could not be handled.
     * `[4, "<messageId>", "<errorCode>", "<errorDescription>", {<errorDetails>}]`
     *
     * [errorCode] is a `String` rather than a [RpcErrorCode] so that a value outside the table
     * is **recorded rather than dropped**. Use [knownErrorCode] when you need it interpreted.
     */
    data class CallError(
        override val messageId: String,
        val errorCode: String,
        val errorDescription: String,
        val errorDetails: ObjectNode,
    ) : OcppFrame {
        override val type get() = MessageType.CALL_ERROR

        /** The matching code if [errorCode] is in the table (Part 4 §4.3), otherwise `null`. */
        val knownErrorCode: RpcErrorCode? get() = RpcErrorCode.parse(errorCode)
    }

    /**
     * (2.1) The response could not be handled. Same shape as CALLERROR. `[5, ...]`
     *
     * [messageId] must equal the messageId of the **CALLRESULT** that caused it (Part 4 §4.1.4).
     */
    data class CallResultError(
        override val messageId: String,
        val errorCode: String,
        val errorDescription: String,
        val errorDetails: ObjectNode,
    ) : OcppFrame {
        override val type get() = MessageType.CALL_RESULT_ERROR

        /** The matching code if [errorCode] is in the table (Part 4 §4.3), otherwise `null`. */
        val knownErrorCode: RpcErrorCode? get() = RpcErrorCode.parse(errorCode)
    }

    /**
     * (2.1) A one-way message that expects no response.
     * `[6, "<messageId>", "<action>", {<payload>}]`
     *
     * Structurally a CALL, but no suffix is stripped from [action] — these messages never end
     * in `Request`/`Response` to begin with (Part 4 §4.2.4). For example
     * `"NotifyPeriodicEventStream"`.
     *
     * A receiver **SHALL NOT** answer a SEND with a CALLRESULT or CALLERROR.
     */
    data class Send(
        override val messageId: String,
        val action: String,
        val payload: ObjectNode,
    ) : OcppFrame {
        override val type get() = MessageType.SEND
    }
}
