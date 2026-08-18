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

    /** `TriggerReasonEnumType` — 배터리가 빠져 통신이 끊겼다. `TxStopPoint = EVConnected` (S04.FR.09). */
    const val TRIGGER_REASON_EV_COMMUNICATION_LOST = "EVCommunicationLost"

    /** `ChargingStateEnumType` */
    const val CHARGING_STATE_EV_CONNECTED = "EVConnected"
    const val CHARGING_STATE_CHARGING = "Charging"

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
}
