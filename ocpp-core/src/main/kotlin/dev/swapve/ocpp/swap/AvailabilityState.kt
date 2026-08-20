package dev.swapve.ocpp.swap

/**
 * ⚠️ **직관과 반대인 슬롯 가용성** (Part 2 §S Ch.2).
 *
 * > *"An 'Available' slot does **not** contain a battery for swapping, whereas an 'Occupied'
 * > slot **does** have a battery that can be used for swapping."*
 *
 * ### 이 반전 지식은 이 파일에만 있다
 *
 * **버그 유발 1순위**가 이 반전이다. 도메인은 `EMPTY`/`HOLDS_BATTERY`
 * 로 부르고 (swap-domain `SlotState`), 프로토콜은 `Available`/`Occupied` 로 부른다.
 * **두 어휘 사이의 다리를 두 곳에 두면 언젠가 한쪽만 틀린다** — 시뮬레이터는 배터리를 넣고
 * `Available` 을 보내는데 CSMS 는 그것을 "배터리 있음"으로 읽는 식이다. 그래서 변환의
 * 유일한 지식인 [holdsBattery] / [wireOf] 를 여기 하나로 모은다.
 *
 * ### 왜 `ocpp-core` 인가
 *
 * `AvailabilityState` 는 **OCPP 디바이스 모델의 어휘**다 (컴포넌트 `Connector` 의 변수).
 * swap-domain 에 두면 도메인에 프로토콜 어휘가 새어 들어가고 (원칙 4), 그렇다고
 * `ocpp-core → swap-domain` 의존을 만들면 층위가 뒤집힌다. 프로토콜 어휘를 프로토콜 모듈에
 * 두고, **도메인 열거형으로 바꾸는 마지막 한 걸음만 각 경계 모듈이 한다** — 그 한 걸음은
 * `Boolean` → 도메인 열거형이라 반전이 끼어들 자리가 없다.
 *
 * [UNAVAILABLE] 은 배터리 유무와 무관하게 "슬롯을 쓸 수 없다"를 뜻하므로 [holdsBattery] 가
 * `null` 이다 — 모른다는 사실을 `false` 로 뭉개지 않는다.
 */
enum class AvailabilityState(val wireValue: String) {

    /** 슬롯에 교환용 배터리가 **없다.** */
    AVAILABLE("Available"),

    /** 슬롯에 교환에 쓸 수 있는 배터리가 **있다.** */
    OCCUPIED("Occupied"),

    /** 슬롯을 쓸 수 없다. 배터리 유무는 이 값으로 알 수 없다. */
    UNAVAILABLE("Unavailable"),
    ;

    /**
     * 이 상태가 뜻하는 배터리 유무. [UNAVAILABLE] 이면 `null` — 알 수 없다는 뜻이다.
     */
    val holdsBattery: Boolean?
        get() = when (this) {
            AVAILABLE -> false
            OCCUPIED -> true
            UNAVAILABLE -> null
        }

    companion object {

        /** 전선 위의 값에서 읽는다. 표에 없는 값이면 `null` — 버리지 않고 호출자가 기록한다. */
        fun parse(wireValue: String): AvailabilityState? = entries.firstOrNull { it.wireValue == wireValue }

        /**
         * 배터리 유무 → 전선 위의 값. **배터리를 넣으면 [OCCUPIED] 다.**
         *
         * 시뮬레이터가 `NotifyEvent.actualValue` 를 만들 때 지나는 유일한 문이다.
         */
        fun wireOf(holdsBattery: Boolean): String = if (holdsBattery) OCCUPIED.wireValue else AVAILABLE.wireValue

        /**
         * 전선 위의 값 → 배터리 유무. 모르는 값이거나 [UNAVAILABLE] 이면 `null`.
         *
         * CSMS 가 `NotifyEvent.actualValue` 를 읽을 때 지나는 유일한 문이다.
         */
        fun holdsBattery(wireValue: String): Boolean? = parse(wireValue)?.holdsBattery
    }
}
