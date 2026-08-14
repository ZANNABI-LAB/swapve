package dev.swapve.swap

/**
 * 배터리 한 개의 상태 — 오간 시점에 관측된 값.
 *
 * [soC] 와 [soH] 를 **양쪽(들어온 배터리·나간 배터리) 다 보존한다** (PLAN §11.2).
 * 표준이 과금 근거를 명시하기 때문이다 — *"the price can depend, for example, on the
 * difference between the state of charge of the old and new batteries"* (Part 2 S. Ch.1).
 * 교환 완료 시 결과만 남기고 이 값을 버리면 나중에 과금이 스키마 변경 없이는 불가능해진다.
 *
 * [slotId] 는 이 배터리가 오간 슬롯이다. 입고 슬롯과 출고 슬롯은 서로 다르다 (PLAN §7.1).
 *
 * 범위 검증을 생성 시점에 하는 이유: 범위를 벗어난 값은 **상태 전이의 흐름이 아니라 값 자체의
 * 성립 여부** 문제다. 상태 전이는 예외를 쓰지 않지만([SwapStateMachine]), 값 객체의 불변식은
 * 생성자에서 막는 게 맞다.
 *
 * @param serialNumber 배터리 일련번호
 * @param soC 충전 상태 (%). 0..100
 * @param soH 건강 상태 (%). 0..100
 */
data class BatteryData(
    val slotId: SlotId,
    val serialNumber: String,
    val soC: Double,
    val soH: Double,
) {
    init {
        require(serialNumber.isNotBlank()) { "배터리 일련번호가 비어 있다" }
        require(soC in 0.0..100.0) { "soC 는 0..100 이어야 한다: $soC" }
        require(soH in 0.0..100.0) { "soH 는 0..100 이어야 한다: $soH" }
    }
}
