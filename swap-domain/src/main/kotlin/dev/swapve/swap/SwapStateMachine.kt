package dev.swapve.swap

import java.time.Instant

/**
 * The swap transaction state machine.
 *
 * One pure function, `(state, event) -> transition`. No I/O, no global state, no reading of the
 * clock — time rides on the event. The same input always gives the same result.
 *
 * **Order-agnostic.** In-first and out-first are handled symmetrically and both reach the same
 * [SwapTransaction.Completed]. Which order a station runs is something this machine has no need
 * to know; knowing it would mean an assumption baked into the code.
 *
 * **Batteries move as a set.** Both directions take several at once.
 */
object SwapStateMachine {

    /**
     * Applies one event.
     *
     * Never throws. An event outside the protocol comes back as [SwapTransition.Anomaly], a
     * duplicate as [SwapTransition.Ignored]. In both cases the state is unchanged.
     */
    fun transition(state: SwapTransaction, event: SwapEvent): SwapTransition {
        val current = state.key
        if (current != null && current != event.key) {
            return anomaly(state, AnomalyReason.KEY_MISMATCH, "swap in progress is $current but an event for ${event.key} arrived")
        }
        if (state.isTerminal) {
            return SwapTransition.Ignored(state, IgnoreReason.ALREADY_TERMINAL)
        }

        return when (state) {
            is SwapTransaction.Idle -> fromIdle(state, event)
            is SwapTransaction.Authorized -> fromAuthorized(state, event)
            is SwapTransaction.HalfIn -> fromHalfIn(state, event)
            is SwapTransaction.HalfOut -> fromHalfOut(state, event)
            is SwapTransaction.Completed, is SwapTransaction.OutTimedOut ->
                SwapTransition.Ignored(state, IgnoreReason.ALREADY_TERMINAL)
        }
    }

    /** Applies events in order and returns the final state. Use [transition] when the steps matter. */
    fun replay(state: SwapTransaction, events: List<SwapEvent>): SwapTransaction =
        events.fold(state) { acc, event -> transition(acc, event).state }

    private fun fromIdle(state: SwapTransaction.Idle, event: SwapEvent): SwapTransition = when (event) {
        is SwapEvent.Authorized -> SwapTransition.Advanced(
            SwapTransaction.Authorized(event.key, event.idToken, event.at),
        )

        is SwapEvent.BatteryIn, is SwapEvent.BatteryOut, is SwapEvent.BatteryOutTimeout ->
            anomaly(state, AnomalyReason.NOT_AUTHORIZED, "a ${event.label} event arrived for unauthorized swap ${event.key}")
    }

    private fun fromAuthorized(state: SwapTransaction.Authorized, event: SwapEvent): SwapTransition = when (event) {
        is SwapEvent.Authorized -> SwapTransition.Ignored(state, IgnoreReason.DUPLICATE_AUTHORIZATION)

        is SwapEvent.BatteryIn -> SwapTransition.Advanced(
            SwapTransaction.HalfIn(state.key, state.idToken, state.authorizedAt, event.batteries, event.at),
        )

        is SwapEvent.BatteryOut -> SwapTransition.Advanced(
            SwapTransaction.HalfOut(state.key, state.idToken, state.authorizedAt, event.batteries, event.at),
        )

        is SwapEvent.BatteryOutTimeout ->
            anomaly(state, AnomalyReason.UNEXPECTED_TIMEOUT, "a collection timeout arrived for swap ${state.key}, where no battery has moved")
    }

    private fun fromHalfIn(state: SwapTransaction.HalfIn, event: SwapEvent): SwapTransition = when (event) {
        is SwapEvent.Authorized -> SwapTransition.Ignored(state, IgnoreReason.DUPLICATE_AUTHORIZATION)

        is SwapEvent.BatteryIn -> SwapTransition.Ignored(state, IgnoreReason.DUPLICATE_BATTERY_IN)

        is SwapEvent.BatteryOut -> complete(
            state = state,
            key = state.key,
            idToken = state.idToken,
            authorizedAt = state.authorizedAt,
            batteriesIn = state.batteriesIn,
            batteriesOut = event.batteries,
            startedAt = state.inAt,
            completedAt = event.at,
        )

        is SwapEvent.BatteryOutTimeout -> SwapTransition.Advanced(
            SwapTransaction.OutTimedOut(
                key = state.key,
                idToken = state.idToken,
                authorizedAt = state.authorizedAt,
                orphanBatteriesIn = state.batteriesIn,
                startedAt = state.inAt,
                timedOutAt = event.at,
            ),
        )
    }

    private fun fromHalfOut(state: SwapTransaction.HalfOut, event: SwapEvent): SwapTransition = when (event) {
        is SwapEvent.Authorized -> SwapTransition.Ignored(state, IgnoreReason.DUPLICATE_AUTHORIZATION)

        is SwapEvent.BatteryIn -> complete(
            state = state,
            key = state.key,
            idToken = state.idToken,
            authorizedAt = state.authorizedAt,
            batteriesIn = event.batteries,
            batteriesOut = state.batteriesOut,
            startedAt = state.outAt,
            completedAt = event.at,
        )

        is SwapEvent.BatteryOut -> SwapTransition.Ignored(state, IgnoreReason.DUPLICATE_BATTERY_OUT)

        is SwapEvent.BatteryOutTimeout ->
            anomaly(state, AnomalyReason.UNEXPECTED_TIMEOUT, "a collection timeout arrived for swap ${state.key}, whose battery already went out")
    }

    /**
     * Joins the two halves into a completed swap. In-first and out-first arrive at the same place.
     *
     * Mismatched counts do not produce a [SwapTransaction.Completed]. An imbalance leaves as an
     * anomaly rather than a completion, so that *"in = out"* holds by construction.
     */
    private fun complete(
        state: SwapTransaction,
        key: SwapKey,
        idToken: IdToken,
        authorizedAt: Instant,
        batteriesIn: List<BatteryData>,
        batteriesOut: List<BatteryData>,
        startedAt: Instant,
        completedAt: Instant,
    ): SwapTransition {
        if (batteriesIn.size != batteriesOut.size) {
            return anomaly(
                state,
                AnomalyReason.BATTERY_COUNT_MISMATCH,
                "the ledger of swap $key does not balance: ${batteriesIn.size} in, ${batteriesOut.size} out",
            )
        }
        return SwapTransition.Advanced(
            SwapTransaction.Completed(
                key = key,
                idToken = idToken,
                authorizedAt = authorizedAt,
                batteriesIn = batteriesIn,
                batteriesOut = batteriesOut,
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    private fun anomaly(state: SwapTransaction, reason: AnomalyReason, description: String) =
        SwapTransition.Anomaly(state, reason, description)

    /** The event name used in anomaly descriptions. */
    private val SwapEvent.label: String
        get() = when (this) {
            is SwapEvent.Authorized -> "authorization"
            is SwapEvent.BatteryIn -> "battery-in"
            is SwapEvent.BatteryOut -> "battery-out"
            is SwapEvent.BatteryOutTimeout -> "collection timeout"
        }
}
