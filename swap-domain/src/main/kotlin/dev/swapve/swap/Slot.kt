package dev.swapve.swap

/**
 * 슬롯의 점유 상태.
 *
 * ⚠️ **프로토콜의 가용성 열거형과 의미가 반대다**. 프로토콜에서 "가용"은
 * *"슬롯에 교환용 배터리가 없다"* 를 뜻하고, "점유"가 *"교환에 쓸 수 있는 배터리가 있다"* 를
 * 뜻한다. 버그 유발 1순위다.
 *
 * 그래서 도메인에서는 **관측 사실 그대로** 이름 붙인다 — 배터리가 없으면 [EMPTY],
 * 있으면 [HOLDS_BATTERY]. 프로토콜 어휘는 경계(csms)에서만 변환하고 이 모듈에 들이지 않는다
 * (원칙 4).
 */
enum class SlotState {

    /** 배터리가 **없다.** */
    EMPTY,

    /** 배터리가 **있다.** 교환에 쓸 수 있다. */
    HOLDS_BATTERY,

    /** 슬롯을 쓸 수 없다 (고장·점검 등). 배터리 유무와 무관하게 교환 대상이 아니다. */
    UNUSABLE,
}

/**
 * 슬롯 하나. 슬롯 = EVSE 1개에 대응하는 논리 단위.
 *
 * [state] 와 [battery] 의 정합성을 생성 시점에 막는다 — [SlotState.EMPTY] 인데 배터리가 있거나
 * [SlotState.HOLDS_BATTERY] 인데 배터리가 없는 슬롯은 애초에 만들어질 수 없다.
 * [SlotState.UNUSABLE] 은 양쪽 다 허용한다. 고장 난 슬롯에 배터리가 갇혀 있을 수 있다.
 */
data class Slot(
    val id: SlotId,
    val state: SlotState,
    val battery: BatteryData? = null,
) {
    init {
        when (state) {
            SlotState.EMPTY -> require(battery == null) { "$state 슬롯에 배터리가 들어 있다: $id" }
            SlotState.HOLDS_BATTERY -> require(battery != null) { "$state 슬롯이 비어 있다: $id" }
            SlotState.UNUSABLE -> Unit
        }
        require(battery == null || battery.slotId == id) {
            "배터리가 기록한 슬롯과 실제 슬롯이 다르다: ${battery?.slotId} != $id"
        }
    }
}
