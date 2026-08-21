package dev.swapve.swap

import java.time.Instant

/**
 * The state of a swap transaction.
 *
 * ```
 *                 ┌──────┐
 *                 │ IDLE │
 *                 └──┬───┘
 *          Authorized│
 *              ┌─────▼──────┐
 *              │ AUTHORIZED │
 *              └──┬──────┬──┘
 *       BatteryIn │      │ BatteryOut
 *            ┌────▼──┐ ┌─▼───────┐
 *            │HALF_IN│ │HALF_OUT │
 *            └─┬───┬─┘ └────┬────┘
 *  BatteryOut  │   │ Timeout│ BatteryIn
 *      ┌───────▼┐ ┌▼────────────┐ ┌────────▼┐
 *      │COMPLETED│ │OUT_TIMED_OUT│ │COMPLETED│
 *      └─────────┘ └─────────────┘ └─────────┘
 * ```
 *
 * **Order-agnostic.** In goes first as a rule, but the reverse is equally standard, and both
 * paths arrive at [Completed]. Which order it was does not survive in the state — that is a
 * property of the station's configuration, not of the swap.
 *
 * Splitting the half-open state into [HalfIn] and [HalfOut] pins the invariant *"exactly one half
 * is open"* in the type system: a state with both open cannot be expressed.
 *
 * There is **no** state for the insertion timeout. A CSMS is never told about that one.
 */
sealed interface SwapTransaction {

    /** Not yet authorized. This state has no correlation key. */
    data object Idle : SwapTransaction

    /**
     * Authorization was granted and the correlation key is settled.
     *
     * If no battery is inserted from here, the station closes the swap on its own and the CSMS
     * hears nothing. A cleanup timer on the CSMS side would be **an operational convenience, not
     * standard behaviour**, and belongs outside this state machine.
     */
    data class Authorized(
        val key: SwapKey,
        val idToken: IdToken,
        val authorizedAt: Instant,
    ) : SwapTransaction

    /** A depleted battery came in; the fresh one has not gone out yet. */
    data class HalfIn(
        val key: SwapKey,
        val idToken: IdToken,
        val authorizedAt: Instant,
        val batteriesIn: List<BatteryData>,
        val inAt: Instant,
    ) : SwapTransaction {
        init {
            require(batteriesIn.isNotEmpty()) { "the incoming half holds no batteries: $key" }
        }
    }

    /** A fresh battery went out; the depleted one has not come in yet. */
    data class HalfOut(
        val key: SwapKey,
        val idToken: IdToken,
        val authorizedAt: Instant,
        val batteriesOut: List<BatteryData>,
        val outAt: Instant,
    ) : SwapTransaction {
        init {
            require(batteriesOut.isNotEmpty()) { "the outgoing half holds no batteries: $key" }
        }
    }

    /**
     * The swap ended in balance.
     *
     * Serial numbers, [BatteryData.soC] and [BatteryData.soH] are **kept for both sides**, along
     * with [startedAt] and [completedAt]. Pricing is not computed here — it attaches later as a
     * pure calculation over this record.
     *
     * The invariant *"batteries in = batteries out"* is enforced in the constructor: a
     * [Completed] with mismatched counts cannot exist.
     */
    data class Completed(
        val key: SwapKey,
        val idToken: IdToken,
        val authorizedAt: Instant,
        val batteriesIn: List<BatteryData>,
        val batteriesOut: List<BatteryData>,
        val startedAt: Instant,
        val completedAt: Instant,
    ) : SwapTransaction {
        init {
            require(batteriesIn.size == batteriesOut.size) {
                "ledger imbalance: ${batteriesIn.size} in, ${batteriesOut.size} out ($key)"
            }
            require(batteriesIn.isNotEmpty()) { "a swap with no batteries: $key" }
        }

        /** How many batteries the swapped set held. */
        val batteryCount: Int get() = batteriesIn.size
    }

    /**
     * The driver never collected the offered battery, so the swap ended one-sided (S03.FR.06).
     *
     * > *"Situation needs to be reported, because CSMS ends up with an **orphan BatteryIn for
     * > which a BatteryOut is missing**."*
     *
     * Hence [orphanBatteriesIn] stays in the state. A ledger imbalance has to be readable from
     * the record before it can be settled.
     */
    data class OutTimedOut(
        val key: SwapKey,
        val idToken: IdToken,
        val authorizedAt: Instant,
        val orphanBatteriesIn: List<BatteryData>,
        val startedAt: Instant,
        val timedOutAt: Instant,
    ) : SwapTransaction {
        init {
            require(orphanBatteriesIn.isNotEmpty()) { "a collection timeout with no orphan: $key" }
        }

        /** Batteries that came in with nothing going out. Always positive — that is why this state exists. */
        val ledgerImbalance: Int get() = orphanBatteriesIn.size
    }
}

/** The correlation key of this state. `null` only for [SwapTransaction.Idle], where it is not settled yet. */
val SwapTransaction.key: SwapKey?
    get() = when (this) {
        is SwapTransaction.Idle -> null
        is SwapTransaction.Authorized -> key
        is SwapTransaction.HalfIn -> key
        is SwapTransaction.HalfOut -> key
        is SwapTransaction.Completed -> key
        is SwapTransaction.OutTimedOut -> key
    }

/** Whether this state transitions no further. */
val SwapTransaction.isTerminal: Boolean
    get() = this is SwapTransaction.Completed || this is SwapTransaction.OutTimedOut
