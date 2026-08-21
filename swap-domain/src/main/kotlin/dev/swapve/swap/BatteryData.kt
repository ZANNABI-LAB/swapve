package dev.swapve.swap

/**
 * One battery as observed at the moment it moved.
 *
 * [soC] and [soH] are **kept for both sides** — the battery that came in and the one that went
 * out. The standard names that as the basis for pricing: *"the price can depend, for example, on
 * the difference between the state of charge of the old and new batteries"* (Part 2 S. Ch.1).
 * Keeping only the outcome on completion would make pricing impossible without a schema change.
 *
 * [slotId] is the slot this battery moved through — the incoming and outgoing slots differ.
 *
 * Ranges are checked at construction because a value outside them is a question of **whether the
 * value holds together at all**, not of how a transition flows. Transitions do not use exceptions
 * ([SwapStateMachine]); the invariants of a value object belong in its constructor.
 *
 * @param serialNumber the battery serial number
 * @param soC state of charge, 0..100 (%)
 * @param soH state of health, 0..100 (%)
 */
data class BatteryData(
    val slotId: SlotId,
    val serialNumber: String,
    val soC: Double,
    val soH: Double,
) {
    init {
        require(serialNumber.isNotBlank()) { "battery serial number is blank" }
        require(soC in 0.0..100.0) { "soC must be within 0..100: $soC" }
        require(soH in 0.0..100.0) { "soH must be within 0..100: $soH" }
    }
}
