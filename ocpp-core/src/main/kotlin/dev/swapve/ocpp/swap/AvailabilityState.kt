package dev.swapve.ocpp.swap

/**
 * ⚠️ **Slot availability reads the opposite of the intuition** (Part 2 §S Ch.2).
 *
 * > *"An 'Available' slot does **not** contain a battery for swapping, whereas an 'Occupied'
 * > slot **does** have a battery that can be used for swapping."*
 *
 * ### This inversion is known here and nowhere else
 *
 * It is the likeliest source of bugs in this area. The domain says `EMPTY`/`HOLDS_BATTERY`
 * while the protocol says `Available`/`Occupied`, and **a bridge between two vocabularies kept
 * in two places eventually goes wrong in one of them** — the simulator inserts a battery and
 * reports `Available`, the CSMS reads that as "has a battery". So [holdsBattery] and [wireOf]
 * are the only places that hold the knowledge.
 *
 * ### Why this lives in `ocpp-core`
 *
 * `AvailabilityState` is **device model vocabulary** — a variable of the `Connector` component.
 * Putting it in the domain module would leak the protocol into the domain; depending on the
 * domain from here would invert the layering. Protocol vocabulary stays in the protocol module,
 * and **only the last step to a domain enum is left to the modules at the boundary** — a step
 * from `Boolean` to an enum, where the inversion has no room to slip back in.
 *
 * [UNAVAILABLE] says the slot cannot be used at all, whatever it holds, so [holdsBattery] is
 * `null` there — not knowing is not flattened into `false`.
 */
enum class AvailabilityState(val wireValue: String) {

    /** The slot holds **no** battery to swap. */
    AVAILABLE("Available"),

    /** The slot **does** hold a battery that can be swapped. */
    OCCUPIED("Occupied"),

    /** The slot cannot be used. Says nothing about whether a battery is in it. */
    UNAVAILABLE("Unavailable"),
    ;

    /** What this state says about holding a battery. `null` for [UNAVAILABLE] — it does not say. */
    val holdsBattery: Boolean?
        get() = when (this) {
            AVAILABLE -> false
            OCCUPIED -> true
            UNAVAILABLE -> null
        }

    companion object {

        /** Reads the value off the wire. `null` for anything outside the table — the caller records it rather than dropping it. */
        fun parse(wireValue: String): AvailabilityState? = entries.firstOrNull { it.wireValue == wireValue }

        /**
         * Holding a battery → the value on the wire. **A battery in the slot is [OCCUPIED].**
         *
         * The only door a simulator passes through when building `NotifyEvent.actualValue`.
         */
        fun wireOf(holdsBattery: Boolean): String = if (holdsBattery) OCCUPIED.wireValue else AVAILABLE.wireValue

        /**
         * The value on the wire → holding a battery. `null` if unrecognised or [UNAVAILABLE].
         *
         * The only door a CSMS passes through when reading `NotifyEvent.actualValue`.
         */
        fun holdsBattery(wireValue: String): Boolean? = parse(wireValue)?.holdsBattery
    }
}
