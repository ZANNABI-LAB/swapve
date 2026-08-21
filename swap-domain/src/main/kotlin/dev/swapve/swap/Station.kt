package dev.swapve.swap

/**
 * A swap station — a set of slots and their owner.
 *
 * [operatorId] is kept even while there is only ever one value. Stock is judged by the station,
 * so a CSMS does not compute availability from this model; it only holds the slot states it has
 * observed.
 */
data class Station(
    val id: StationId,
    val operatorId: OperatorId,
    val slots: Map<SlotId, Slot> = emptyMap(),
) {
    init {
        require(slots.all { (id, slot) -> id == slot.id }) { "slot key disagrees with the slot's own id" }
    }

    /** The slots holding a battery that can be swapped. */
    val slotsHoldingBattery: List<Slot> get() = slots.values.filter { it.state == SlotState.HOLDS_BATTERY }
}
