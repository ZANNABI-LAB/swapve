package dev.swapve.swap

import java.time.Instant

/**
 * An event fed into a swap transaction.
 *
 * Every event carries [key] and [at]. Time rides on the event rather than being read, so a
 * transition never asks for the current clock — the same input gives the same result whenever it
 * is replayed.
 *
 * Batteries are **always a list**. The official conformance case swaps a set of two
 * (`TC_S_103_CSMS`); supporting a single battery would fail conformance.
 */
sealed interface SwapEvent {

    /** The swap this event belongs to (a composite key). */
    val key: SwapKey

    /** When the event was observed. */
    val at: Instant

    /**
     * Authorization was granted. Locally initiated (RFID at the station) or remotely (sent by the
     * CSMS), it is the same event to the domain (S01/S02) — which side issued the correlation
     * number is the boundary layer's concern.
     */
    data class Authorized(
        override val key: SwapKey,
        val idToken: IdToken,
        override val at: Instant,
    ) : SwapEvent

    /** The driver **put in** a depleted battery. */
    data class BatteryIn(
        override val key: SwapKey,
        val idToken: IdToken,
        val batteries: List<BatteryData>,
        override val at: Instant,
    ) : SwapEvent {
        init {
            require(batteries.isNotEmpty()) { "an incoming event with no batteries" }
        }
    }

    /** The driver **took** a fresh battery. */
    data class BatteryOut(
        override val key: SwapKey,
        val idToken: IdToken,
        val batteries: List<BatteryData>,
        override val at: Instant,
    ) : SwapEvent {
        init {
            require(batteries.isNotEmpty()) { "an outgoing event with no batteries" }
        }
    }

    /**
     * The offered battery was **never collected** (S03.FR.06).
     *
     * The one of the two timeouts a CSMS is told about. The other — authorized but no battery
     * inserted — is closed by the station on its own, and nothing reaches the CSMS, so there is
     * no event for it at all.
     */
    data class BatteryOutTimeout(
        override val key: SwapKey,
        override val at: Instant,
    ) : SwapEvent
}
