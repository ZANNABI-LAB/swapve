package dev.swapve.swap

/**
 * 스테이션 식별자.
 *
 * OCPP 프레임에는 없다 — WebSocket 핸드셰이크에서 정해지는 연결의 속성이다.
 * 도메인에는 **반드시 있어야 한다**: 교환 상관키가 스테이션 범위에서만 유일하기 때문이다.
 */
@JvmInline
value class StationId(val value: String) {
    init {
        require(value.isNotBlank()) { "stationId 가 비어 있다" }
    }

    override fun toString(): String = value
}

/**
 * 스테이션 소유 사업자 식별자.
 *
 * 값이 항상 하나여도 둔다. 단일 사업자를 코드에 전제로 박으면 나중에
 * 로밍(OCPI)을 붙일 때 전 데이터 마이그레이션이 필요해진다.
 */
@JvmInline
value class OperatorId(val value: String) {
    init {
        require(value.isNotBlank()) { "operatorId 가 비어 있다" }
    }

    override fun toString(): String = value
}

/**
 * 슬롯 식별자. 슬롯 하나가 프로토콜의 EVSE 하나에 대응한다.
 *
 * 프로토콜 필드는 0 이상의 정수다. 경계 계층에서 이 타입으로 변환한다.
 */
@JvmInline
value class SlotId(val value: Int) {
    init {
        require(value >= 0) { "슬롯 번호는 0 이상이어야 한다: $value" }
    }

    override fun toString(): String = value.toString()
}

/**
 * 교환 1건의 상관 번호.
 *
 * **이것만으로는 교환을 식별할 수 없다.** 스펙에 전역 유일성 규정이 없으므로 스테이션
 * 범위에서만 유일하다. 상관키가 필요한 자리에는 항상 [SwapKey] 를 쓴다.
 */
@JvmInline
value class SwapRequestId(val value: Int) {
    override fun toString(): String = value.toString()
}

/**
 * 교환 1건의 상관키 — `(스테이션, 상관 번호)` 복합키.
 *
 * 서로 다른 스테이션이 같은 [SwapRequestId] 를 써도 충돌하지 않는다.
 */
data class SwapKey(
    val stationId: StationId,
    val requestId: SwapRequestId,
) {
    override fun toString(): String = "$stationId/$requestId"
}
