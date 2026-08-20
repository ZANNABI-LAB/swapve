package dev.swapve.station

/**
 * 시뮬레이터가 지나는 시퀀스 지점. 장애 주입의 좌표계다.
 *
 * 이름은 Part 6 재사용 상태의 단계와 일치시킨다. 그래야 "어디서 무엇을
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

    /** S02 `RequestBatterySwapRequest` 수신 직후, 응답을 정하기 직전 */
    REQUEST_BATTERY_SWAP,

    /** 충전 트랜잭션 `TransactionEventRequest` 발신 직전 */
    TRANSACTION_EVENT,

    /** `BatterySwapRequest(BatteryIn)` 발신 직전 */
    BATTERY_IN,

    /** `BatterySwapRequest(BatteryOut)` 발신 직전 */
    BATTERY_OUT,

    /** `BatterySwapRequest(BatteryOutTimeout)` 발신 직전 (S03.FR.06) */
    BATTERY_OUT_TIMEOUT,
}

/**
 * 장애 주입 훅.
 *
 * M6 은 호출 지점만 뚫어 두고 [None] 하나로 끝냈다. **M7 이 그 자리를 채운다** — 실패
 * 시나리오 F1~F6 이 성공 기준 S3 이기 때문이다.
 *
 * ### 여섯 시나리오가 전부 이 훅을 쓰지는 않는다
 *
 * 그게 정상이다. F1(배터리 부족)·F3(미등록 배터리)은 **구성**으로 재현된다 — 스테이션에
 * 내줄 배터리를 두지 않거나, CSMS 가 모르는 일련번호를 가진 배터리를 넣으면 그만이다.
 * F5(순서 위반)는 인가를 건너뛰는 것으로 충분하고, F2·F4·F6 은 시뮬레이터의 시퀀스
 * 진입점([StationSimulator.reportBatteryOutTimeout] 등)으로 재현된다.
 *
 * 훅이 필요한 것은 **시퀀스 한가운데서 무언가 끊기는** 상황이다 — 그게 [failingAt] 이고,
 * F6 의 "교환 도중 연결이 끊겼다"가 그 용도다. 없는 쓰임을 위해 훅을 더 만들지 않는다
 *.
 */
fun interface FaultInjection {

    /**
     * [step] 을 실행하기 직전에 불린다.
     *
     * 예외를 던지면 그 시퀀스가 중단된다 — 연결이 끊긴 상황이나 스테이션 자체 오류를
     * 흉내내는 자리가 여기다.
     */
    suspend fun before(step: SimStep, context: FaultContext)

    companion object {

        /** 아무것도 하지 않는다. 정상 경로가 쓰는 구현이다. */
        val None: FaultInjection = FaultInjection { _, _ -> }

        /**
         * [step] 에 도달하면 [SimulatedFault] 를 던져 시퀀스를 끊는다.
         *
         * 교환이 **반쪽으로 남는다.** 그 뒤 재접속해 CALL 을 재전송하는 것이 F6 이고, 그때
         * 장부가 두 번 늘지 않아야 한다 (F6).
         */
        fun failingAt(step: SimStep, message: String): FaultInjection = FaultInjection { at, context ->
            if (at == step) throw SimulatedFault("$message (step=$at, station=${context.stationId})")
        }
    }
}

/**
 * 주입된 장애.
 *
 * 전용 타입인 것이 요점이다. 시험이 `IllegalStateException` 을 잡으면 **시뮬레이터의 진짜
 * 버그까지 함께 삼킨다** — 그러면 "장애를 주입했는데 통과했다"와 "코드가 깨졌는데 통과했다"를
 * 구분할 수 없다.
 */
class SimulatedFault(message: String) : RuntimeException(message)

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
