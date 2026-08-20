package dev.swapve.ocpp.rpc

/**
 * RPC framework error codes (Part 4 §4.3, Table 9).
 *
 * The `errorCode` of a `CALLERROR` / `CALLRESULTERROR` MUST come from this table. On the
 * receiving side, though, **the raw string is preserved** — see [OcppFrame.CallError.errorCode].
 */
enum class RpcErrorCode {
    /** The payload of the Action is syntactically incorrect. */
    FormatViolation,

    /** Any other error not covered by a more specific code. */
    GenericError,

    /** An internal error on the receiver kept the Action from being handled. */
    InternalError,

    /**
     * An unsupported Message Type Number.
     *
     * ⚠️ **Deprecated by errata 2026-06 §4.3.** From 2.1 an unknown message type is ignored
     * silently. The constant stays for receive-side compatibility, but **is never sent**.
     */
    @Deprecated("errata 2026-06 §4.3 — an unknown message type is ignored, not answered with CALLERROR")
    MessageTypeNotSupported,

    /** The requested Action is not known by the receiver. */
    NotImplemented,

    /** A known Action, but one the receiver does not support. */
    NotSupported,

    /** Syntactically correct, but violates a cardinality constraint. */
    OccurrenceConstraintViolation,

    /** Syntactically correct, but carries an invalid value. */
    PropertyConstraintViolation,

    /** The payload does not follow the PDU structure. */
    ProtocolError,

    /** The RPC request itself is invalid — an unreadable messageId, for instance. */
    RpcFrameworkError,

    /** A security problem arose while handling the message. */
    SecurityError,

    /** Syntactically correct, but violates a data type constraint. */
    TypeConstraintViolation,
    ;

    companion object {
        private val byName = entries.associateBy(RpcErrorCode::name)

        /**
         * `null` for a string outside the table.
         *
         * Does not throw — on receive, an unrecognised code is kept rather than discarded.
         */
        fun parse(raw: String): RpcErrorCode? = byName[raw]
    }
}
