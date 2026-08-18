package dev.swapve.station

/**
 * 시뮬레이터가 지나는 시퀀스 지점. 장애 주입의 좌표계다.
 *
 * 이름은 Part 6 재사용 상태의 단계와 일치시킨다 (PLAN §7.2). 그래야 "어디서 무엇을
 * 어긋나게 하는가"를 시나리오 쪽에서 표준 용어로 말할 수 있다.
 */
enum class SimStep {

    /** `BootNotificationRequest` 발신 직전 */
    BOOT,

    /** 슬롯 상태 알림(`NotifyEventRequest`) 발신 직전 */
    SLOT_STATUS,

    /** `SecurityEventNotificationRequest` 발신 직전 */
    SECURITY_EVENT,

    /** S01 `AuthorizeRequest` 발신 직전 */
    AUTHORIZE,

    /** 충전 트랜잭션 `TransactionEventRequest` 발신 직전 */
    TRANSACTION_EVENT,

    /** `BatterySwapRequest(BatteryIn)` 발신 직전 */
    BATTERY_IN,

    /** `BatterySwapRequest(BatteryOut)` 발신 직전 */
    BATTERY_OUT,
}

/**
 * 장애 주입 훅 — **뼈대뿐이다.**
 *
 * ### 지금은 자리만 만든다
 *
 * 실패 시나리오 F1~F6 (PLAN §5.4) 은 **M7 의 일이다.** 여기에 미리 구현해 두면 M6 의 범위를
 * 넘고, 검증되지 않은 코드가 "있는 것처럼" 보인다. 그래서 지금 있는 것은 호출 지점과
 * [None] 하나뿐이다.
 *
 * ### 왜 그럼에도 지금 두는가
 *
 * 호출 지점을 나중에 뚫으려면 시퀀스 함수 전부를 손대야 한다. 훅 하나를 미리 심어 두는
 * 비용은 함수 호출 한 줄이고, 그건 PLAN §11.0 이 경계한 "미래를 위한 추상 계층"이 아니라
 * **되돌리기 비싼 자리를 지금 열어 두는 것**에 해당한다.
 *
 * 구현체는 [before] 에서 지연·예외·상태 조작을 하거나, [rewrite] 로 나갈 페이로드를
 * 바꿔치기할 수 있다. 어느 쪽도 지금은 하지 않는다.
 */
fun interface FaultInjection {

    /**
     * [step] 을 실행하기 직전에 불린다.
     *
     * 예외를 던지면 그 시퀀스가 중단된다 — 연결이 끊긴 상황이나 스테이션 자체 오류를
     * 흉내내는 자리가 여기다.
     */
    suspend fun before(step: SimStep, context: FaultContext)

    /** 아무것도 하지 않는다. M6 의 정상 경로가 쓰는 유일한 구현이다. */
    companion object {
        val None: FaultInjection = FaultInjection { _, _ -> }
    }
}

/**
 * 훅에 함께 넘기는 상황 정보.
 *
 * @param stationId 어느 스테이션인가
 * @param slotId 슬롯 단위 단계면 그 슬롯, 아니면 `null`
 * @param requestId 교환 중이면 그 상관 번호, 아니면 `null`
 */
data class FaultContext(
    val stationId: String,
    val slotId: Int? = null,
    val requestId: Int? = null,
)
