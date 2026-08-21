package dev.swapve.swap

/**
 * The outcome of attempting a transition.
 *
 * Success, ignore and anomaly are **told apart and returned**, never signalled by an exception.
 * A protocol violation is still answered normally, and what to record or reply is policy for the
 * layer above. This decides, and stops there.
 *
 * All three carry [state]. A caller may use [state] as the next state whether or not it examines
 * the kind — [Ignored] and [Anomaly] hand back the state unchanged.
 */
sealed interface SwapTransition {

    /** The state after this transition. */
    val state: SwapTransaction

    /** It advanced. */
    data class Advanced(override val state: SwapTransaction) : SwapTransition

    /**
     * Ignored for idempotency. The ledger does not move.
     *
     * Not a failure. Retransmissions and duplicate deliveries happen in normal operation, and not
     * counting them twice is the correct behaviour.
     */
    data class Ignored(
        override val state: SwapTransaction,
        val reason: IgnoreReason,
    ) : SwapTransition

    /**
     * An event outside the protocol. The state is left alone and **the fact is recorded**.
     *
     * The state machine does not blow up here. The layer above records an anomaly and still
     * answers normally.
     */
    data class Anomaly(
        override val state: SwapTransaction,
        val reason: AnomalyReason,
        val description: String,
    ) : SwapTransition
}

/** Why it was ignored. */
enum class IgnoreReason {

    /** An incoming event arrived again under the same correlation key. */
    DUPLICATE_BATTERY_IN,

    /** An outgoing event arrived again under the same correlation key. */
    DUPLICATE_BATTERY_OUT,

    /** An already authorized swap was authorized again — a retransmission across a reconnect. */
    DUPLICATE_AUTHORIZATION,

    /** An event arrived for a swap that already ended. */
    ALREADY_TERMINAL,
}

/** Why it was judged an anomaly. */
enum class AnomalyReason {

    /** A swap event arrived without authorization. */
    NOT_AUTHORIZED,

    /** An event arrived under a different correlation key than the swap in progress. */
    KEY_MISMATCH,

    /** The number of batteries in does not match the number out. */
    BATTERY_COUNT_MISMATCH,

    /** A collection timeout arrived in a state where it cannot occur. */
    UNEXPECTED_TIMEOUT,
}
