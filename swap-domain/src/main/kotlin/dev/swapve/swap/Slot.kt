package dev.swapve.swap

/**
 * Whether a slot holds a battery.
 *
 * ⚠️ **The protocol's availability enum means the opposite.** There, "available" means *the slot
 * holds no battery to swap*, and "occupied" means *it holds one that can be used*. It is the
 * likeliest source of bugs in this area.
 *
 * So the domain names what was **observed**: [EMPTY] when there is no battery, [HOLDS_BATTERY]
 * when there is. Protocol vocabulary is converted at the boundary and never enters this module.
 */
enum class SlotState {

    /** **No** battery. */
    EMPTY,

    /** A battery **is** there, and it can be swapped. */
    HOLDS_BATTERY,

    /** The slot cannot be used (a fault, maintenance). Not a swap candidate whatever it holds. */
    UNUSABLE,
}

/**
 * One slot — the logical unit corresponding to one EVSE.
 *
 * [state] and [battery] are reconciled at construction: an [SlotState.EMPTY] slot holding a
 * battery, or a [SlotState.HOLDS_BATTERY] slot with none, cannot be built in the first place.
 * [SlotState.UNUSABLE] allows either — a battery can be stuck in a broken slot.
 */
data class Slot(
    val id: SlotId,
    val state: SlotState,
    val battery: BatteryData? = null,
) {
    init {
        when (state) {
            SlotState.EMPTY -> require(battery == null) { "an $state slot holds a battery: $id" }
            SlotState.HOLDS_BATTERY -> require(battery != null) { "a $state slot is empty: $id" }
            SlotState.UNUSABLE -> Unit
        }
        require(battery == null || battery.slotId == id) {
            "the battery records a different slot than it sits in: ${battery?.slotId} != $id"
        }
    }
}
