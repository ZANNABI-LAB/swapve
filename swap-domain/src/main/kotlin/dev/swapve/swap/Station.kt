package dev.swapve.swap

/**
 * 교환 스테이션 — 슬롯의 집합과 소유자.
 *
 * [operatorId] 는 값이 항상 하나여도 둔다. 재고 판정은 스테이션이 하므로
 * CSMS 는 이 모델로 재고를 계산하지 않는다 — 관측된 슬롯 상태를 담아 둘 뿐이다.
 */
data class Station(
    val id: StationId,
    val operatorId: OperatorId,
    val slots: Map<SlotId, Slot> = emptyMap(),
) {
    init {
        require(slots.all { (id, slot) -> id == slot.id }) { "슬롯 번호와 키가 어긋났다" }
    }

    /** 배터리가 들어 있어 교환에 쓸 수 있는 슬롯들. */
    val slotsHoldingBattery: List<Slot> get() = slots.values.filter { it.state == SlotState.HOLDS_BATTERY }
}
