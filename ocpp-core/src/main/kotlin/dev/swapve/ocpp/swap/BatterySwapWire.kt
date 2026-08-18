package dev.swapve.ocpp.swap

/**
 * 배터리 교환이 실제로 쓰는 **전선 위의 어휘** — action 이름과 열거형 값 상수.
 *
 * ### 왜 상수로 모으는가
 *
 * Part 6 의 Tool validation 은 `trigger=Delta`, `component.name="Connector"`,
 * `variable.name="AvailabilityState"`, `triggerReason=CablePluggedIn` 같은 **정확한 문자열**을
 * 요구한다 (PLAN §7.2). 이 값들을 시뮬레이터와 CSMS 가 각자 리터럴로 적으면, 시험은
 * "리터럴 A 와 리터럴 B 가 같은가"를 확인할 뿐 계약을 확인하지 못한다. 양쪽이 같은 상수를
 * 참조하게 두면 시험이 **계약 대조**가 되고, 표준이 바뀔 때 고칠 자리도 하나다.
 *
 * 스키마 자체를 대신하지 않는다 — 페이로드가 맞는지는 언제나 공식 스키마가 판정한다
 * (PLAN §6 원칙 2). 여기 있는 것은 스키마가 `enum` 으로 허용하는 값 **중 우리가 고른 것**뿐이다.
 *
 * 슬롯 가용성만은 [AvailabilityState] 로 따로 뺐다. 그건 값이 아니라 **반전된 의미**를
 * 다루는 자리라서다 (PLAN §4.2).
 */
object BatterySwapWire {

    // ------------------------------------------------------------------ action (Part 4 §4.1.6)

    const val BOOT_NOTIFICATION = "BootNotification"
    const val HEARTBEAT = "Heartbeat"
    const val AUTHORIZE = "Authorize"
    const val NOTIFY_EVENT = "NotifyEvent"
    const val SECURITY_EVENT_NOTIFICATION = "SecurityEventNotification"
    const val TRANSACTION_EVENT = "TransactionEvent"
    const val BATTERY_SWAP = "BatterySwap"
    const val REQUEST_BATTERY_SWAP = "RequestBatterySwap"

    // ------------------------------------------------------------------ BatterySwapEventEnumType

    /** 헌 배터리가 **들어왔다.** */
    const val BATTERY_IN = "BatteryIn"

    /** 새 배터리가 **나갔다.** */
    const val BATTERY_OUT = "BatteryOut"

    /** 제공된 배터리를 꺼내가지 않았다 (S03.FR.06, PLAN §4.7). */
    const val BATTERY_OUT_TIMEOUT = "BatteryOutTimeout"

    // ------------------------------------------------------------------ 슬롯 상태 보고 (S03.FR.02/04)

    /**
     * 슬롯 상태를 싣는 컴포넌트. 슬롯 1개 = EVSE 1개이고 그 안의 커넥터가 상태를 보고한다
     * (PLAN §4.1/§4.5).
     */
    const val COMPONENT_CONNECTOR = "Connector"

    /** 슬롯 점유 상태 변수. 값의 의미는 [AvailabilityState] 를 보라 — 직관과 반대다. */
    const val VARIABLE_AVAILABILITY_STATE = "AvailabilityState"

    /** `EventTriggerEnumType` — 상태가 **바뀌어서** 보내는 알림이다. */
    const val TRIGGER_DELTA = "Delta"

    /** `EventNotificationEnumType` — 장비에 하드와이어된 알림. 모니터 설정으로 생긴 것이 아니다. */
    const val NOTIFICATION_HARD_WIRED = "HardWiredNotification"

    // ------------------------------------------------------------------ SecurityEventNotification

    /** 부팅 직후 보고하는 보안 이벤트 (Part 6 `BootedBatterySwapping`). */
    const val SECURITY_EVENT_STARTUP = "StartupOfTheDevice"

    /** 재시작/리부트. Part 6 는 [SECURITY_EVENT_STARTUP] 과 이 값 둘 중 하나를 허용한다. */
    const val SECURITY_EVENT_RESET_OR_REBOOT = "ResetOrReboot"

    // ------------------------------------------------------------------ TransactionEvent (S04)

    /** `TransactionEventEnumType` */
    const val TX_STARTED = "Started"
    const val TX_UPDATED = "Updated"
    const val TX_ENDED = "Ended"

    /** `TriggerReasonEnumType` — 배터리가 슬롯에 꽂혔다. `TxStartPoint = EVConnected` (S04.FR.08). */
    const val TRIGGER_REASON_CABLE_PLUGGED_IN = "CablePluggedIn"

    /** `TriggerReasonEnumType` — 충전 상태가 바뀌었다. */
    const val TRIGGER_REASON_CHARGING_STATE_CHANGED = "ChargingStateChanged"

    /**
     * `TriggerReasonEnumType` — 충전 한도에 도달했다.
     *
     * ### ★ 배터리 제거로 트랜잭션이 끝날 때의 triggerReason 이 이것이다
     *
     * Part 6 `TC_S_103_CSMS` step 13/17 원문이 그렇다. PLAN v3 는 이 자리를
     * `EVCommunicationLost` 로 **추정**했고 v3.1 이 스펙 원문 대조로 정정했다 (PLAN §0).
     * 그 추정값은 스펙 어디에도 근거가 없어 상수 자체를 지웠다 — 남겨 두면 다시 쓰인다.
     *
     * 종료 사건은 세 값을 **각각** 요구한다: 왜 끝났나(`triggerReason`) · 무엇이
     * 끊겼나([STOPPED_REASON_EV_DISCONNECTED]) · 끝난 뒤의 상태([CHARGING_STATE_IDLE]).
     * 셋 다 enum 으로는 유효한 값이라 **스키마 검증으로는 잡히지 않는다.**
     */
    const val TRIGGER_REASON_ENERGY_LIMIT_REACHED = "EnergyLimitReached"

    /** `ChargingStateEnumType` */
    const val CHARGING_STATE_EV_CONNECTED = "EVConnected"
    const val CHARGING_STATE_CHARGING = "Charging"

    /** `ChargingStateEnumType` — 트랜잭션이 끝난 뒤의 상태 (Part 6 `TC_S_103_CSMS` step 13/17). */
    const val CHARGING_STATE_IDLE = "Idle"

    /** `ReasonEnumType` — 배터리를 빼서 트랜잭션이 끝났다 (PLAN §4.10). */
    const val STOPPED_REASON_EV_DISCONNECTED = "EVDisconnected"

    // ------------------------------------------------------------------ IdTokenType

    /**
     * 충전 트랜잭션에 쓰는 대체 토큰의 종류 (S04.FR.02/03).
     *
     * 교환 스테이션의 충전은 사람이 카드를 대서 시작되지 않는다. 설정된
     * `BatterySwapCtrlr.IdToken` 을 `Central` 로 싣거나, 아무 토큰도 없다는 뜻으로
     * [ID_TOKEN_TYPE_NO_AUTHORIZATION] 을 쓴다.
     */
    const val ID_TOKEN_TYPE_CENTRAL = "Central"

    /** 인가가 없는 트랜잭션. 이때 `idToken.idToken` 은 **빈 문자열**이다 (Part 6 Tool validation). */
    const val ID_TOKEN_TYPE_NO_AUTHORIZATION = "NoAuthorization"

    // ------------------------------------------------------------------ 응답 상태값

    /** `RegistrationStatusEnumType` */
    const val REGISTRATION_ACCEPTED = "Accepted"

    /** `AuthorizationStatusEnumType` */
    const val AUTHORIZATION_ACCEPTED = "Accepted"

    /** `GenericStatusEnumType` — `RequestBatterySwapResponse.status` (S02). */
    const val GENERIC_ACCEPTED = "Accepted"
    const val GENERIC_REJECTED = "Rejected"

    // ------------------------------------------------------------------ customData 거부 확장 (PLAN §4.8)

    /**
     * `BatterySwapResponse` 로 배터리를 거부할 때 쓰는 벤더 식별자 (Part 2 S03 Error handling).
     *
     * `BatterySwapResponse` 는 *"Empty response by CSMS to confirm receipt"* 이고 **거부할 수
     * 없다** (PLAN §4.3). OCA 가 그 한계를 위한 공식 우회를 정해 두었다 — 응답에
     * `customData` 를 실어 `status`/`statusInfo` 를 알린다. 그래도 **응답의 성격은 수신
     * 확인 그대로**이므로 CALLERROR 로 답하지 않는다.
     *
     * 지원 여부는 디바이스 모델의
     * `CustomizationCtrlr.CustomImplementationEnabled[이 vendorId] = true` 로 보고한다.
     */
    const val VENDOR_ID_BATTERY_SWAP_RESPONSE = "org.openchargealliance.batteryswapresponse"
}

/**
 * 배터리 거부 사유 코드 — 부록 `reason_codes.csv` 가 정본이다 (PLAN §4.9.1).
 *
 * 이 여섯 개는 표준이 **사전 정의**한 값이라 우리가 짓는 것이 아니다. 문자열 리터럴로 흩어
 * 놓으면 오타가 조용히 통과한다 — `statusInfo.reasonCode` 는 `maxLength` 만 검사받고 값의
 * 목록은 스키마에 없기 때문이다.
 *
 * @param wireValue 전선 위의 값. 열거형 이름이 바뀌어도 이 값은 흔들리지 않는다.
 * @param appliesToRequestBatterySwap `RequestBatterySwapResponse.statusInfo` 에도 쓸 수 있는가.
 *   부록 표에서 그 자리에 나오는 것은 [NO_BATTERY_AVAILABLE] 하나뿐이다 (S02.FR.04).
 */
enum class BatteryRejectionReason(
    val wireValue: String,
    val appliesToRequestBatterySwap: Boolean = false,
) {

    /** SoH 가 너무 낮다. */
    BATTERY_SOH_LOW("BatterySoHLow"),

    /** SoC 값이 부적절하다. */
    BATTERY_SOC("BatterySoC"),

    /** 배터리가 손상됐다. */
    BATTERY_DAMAGED("BatteryDamaged"),

    /** 우리가 모르는 일련번호다 (PLAN §5.4 F3). */
    BATTERY_UNKNOWN("BatteryUnknown"),

    /** 허용되지 않는 배터리 타입이다. */
    BATTERY_TYPE("BatteryType"),

    /**
     * 교환에 내줄 배터리가 없다 (S02.FR.04, PLAN §5.4 F1).
     *
     * **재고 판정은 스테이션이 한다** (PLAN §4.5). CSMS 는 이 코드를 만들지 않고 받아 기록만
     * 한다 — Part 6 `TC_S_102_CSMS` 가 그 시나리오다.
     */
    NO_BATTERY_AVAILABLE("NoBatteryAvailable", appliesToRequestBatterySwap = true);

    companion object {
        /** 전선 위의 값으로 찾는다. 대소문자를 가리지 않는다 — *"The string is case-insensitive"* (스키마). */
        fun ofWire(value: String?): BatteryRejectionReason? =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) }
    }
}
