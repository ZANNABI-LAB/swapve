package dev.swapve.csms.swap

import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.swap.IdToken
import dev.swapve.swap.SlotId
import dev.swapve.swap.StationId
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 충전 트랜잭션 사건 하나 — 원문에서 읽어낸 것 그대로.
 *
 * @param eventType `Started` / `Updated` / `Ended`
 * @param slotId 사건이 난 슬롯(EVSE). `evse` 가 없는 사건이면 `null`
 * @param idToken 인가 없는 트랜잭션이면 `null` — `type=NoAuthorization` 에 빈 문자열이 오므로
 *   값 객체로 만들 수 없고, 만들어서도 안 된다 (없는 인가를 있는 것처럼 적는 셈이다)
 * @param socPercent 이 사건이 실어 온 배터리 충전 상태 (**S04.FR.04** 주기 보고).
 *   계량값이 없는 사건이면 `null` 이다 — 0 으로 적으면 "0% 로 측정됐다"는 거짓이 된다.
 */
data class ChargingEvent(
    val eventType: String,
    val triggerReason: String,
    val seqNo: Int,
    val slotId: SlotId?,
    val chargingState: String?,
    val stoppedReason: String?,
    val idToken: IdToken?,
    val at: Instant,
    val socPercent: Double? = null,
)

/**
 * 충전 트랜잭션 하나의 진행. 사건이 온 순서대로 쌓인다.
 *
 * @param batterySerialNumber **어느 배터리**의 충전인가. 그 배터리가 교환으로 들어올 때
 *   `BatterySwapRequest` 가 일련번호를 실어 오면 붙는다 ([ChargingTransactionRegistry.linkBattery]).
 *   부팅 시점부터 이미 꽂혀 있던 배터리는 `null` 이다 — `TransactionEvent` 도 `NotifyEvent` 도
 *   일련번호를 싣지 않으므로 **CSMS 는 정말로 모른다**. 모르는 것을 지어내지 않는다.
 */
data class ChargingTransaction(
    val stationId: StationId,
    val transactionId: String,
    val events: List<ChargingEvent>,
    val batterySerialNumber: String? = null,
) {
    val startedAt: Instant? get() = events.firstOrNull()?.at
    val isEnded: Boolean get() = events.lastOrNull()?.eventType == BatterySwapWire.TX_ENDED

    /**
     * 어느 슬롯의 충전인가.
     *
     * 사건마다 실려 오지만 트랜잭션이 슬롯을 옮겨 다니지는 않으므로, 처음 알려 온 값이
     * 그 트랜잭션의 슬롯이다.
     */
    val slotId: SlotId? get() = events.firstNotNullOfOrNull { it.slotId }

    /** 마지막으로 보고된 SoC (S04.FR.04). 계량값을 한 번도 받지 못했으면 `null`. */
    val latestSoc: Double? get() = events.lastOrNull { it.socPercent != null }?.socPercent

    /**
     * 마지막으로 알려 온 충전 상태.
     *
     * 주기 보고는 `chargingState` 를 싣지 않는다 — 스키마가 *"required when state has
     * changed"* 라고 규정하고, 주기 보고에서는 상태가 바뀌지 않았기 때문이다. 그래서
     * **마지막으로 값이 실려 온 사건**을 본다. 마지막 사건만 보면 충전 중인 트랜잭션의
     * 상태가 계량 보고 한 번에 `null` 로 사라진다.
     */
    val chargingState: String? get() = events.lastOrNull { it.chargingState != null }?.chargingState

    /**
     * 충전 상한에 닿아 급전이 멈췄다 (`SuspendedEVSE`, S04.FR.06).
     *
     * **끝난 것이 아니다.** [isEnded] 와 함께 보면 "멈췄지만 살아 있다"가 읽힌다 — 그것이
     * 충전 트랜잭션의 종료 상태다.
     */
    val isSuspended: Boolean
        get() = !isEnded && chargingState == BatterySwapWire.CHARGING_STATE_SUSPENDED_EVSE
}

/**
 * 충전 트랜잭션 기록소 (S04).
 *
 * ### ★ 교환 트랜잭션과 절대 합치지 않는다
 *
 * 저장소도 다르고 키도 다르다. 교환은 `(stationId, requestId)` 로 묶이고, 충전은
 * `(stationId, transactionId)` 로 묶인다. **들어온 배터리의 충전은 교환이 끝난 뒤에도 며칠
 * 계속된다** — 한 객체에 합치면 그 배터리가 언제까지 어느 교환에 매여 있는지가 거짓이 된다.
 *
 * [linkBattery] 가 그 경계를 시험한다. 붙이는 것은 **배터리의 신원**이지 교환이 아니다 —
 * 이 클래스는 `SwapKey` 도 `SwapTransaction` 도 알지 못하고, 교환이 `Completed` 가 되든 말든
 * 여기 있는 트랜잭션은 흔들리지 않는다.
 *
 * ### 기록만 한다
 *
 * MVP 범위는 *"시뮬레이터가 발신하고 CSMS 가 수신·기록"* 까지다 (확정 결정 결정 #8).
 * 스마트차징도 요금도 여기서 하지 않는다. 나중에 붙일 때 필요한 것은 전부
 * 이벤트 로그와 이 기록 위의 순수 계산이다.
 *
 * ### 재부팅으로 끊긴 트랜잭션도 지우지 않는다 (S04.FR.11)
 *
 * 스테이션이 재부팅하면 진행 중이던 트랜잭션은 **종료 통보 없이** 사라지고, 배터리가 있는
 * EVSE 마다 새 트랜잭션이 시작된다. 그때 옛 트랜잭션을 오류로 취급하거나 지우지 않는다 —
 * 그 시점까지 관측된 사실은 사실이었고, 지우면 그 배터리가 언제까지 충전됐는지가 사라진다.
 * 끝나지 않은 채 남은 것 자체가 "재부팅으로 끊겼다"는 기록이다.
 *
 * 스레드 안전하다.
 */
@Component
class ChargingTransactionRegistry {

    private val lock = Any()
    private val transactions = ConcurrentHashMap<Pair<StationId, String>, ChargingTransaction>()

    /** 사건 하나를 기록한다. 처음 보는 `transactionId` 면 트랜잭션이 새로 생긴다. */
    fun record(stationId: StationId, transactionId: String, event: ChargingEvent) = synchronized(lock) {
        val key = stationId to transactionId
        val existing = transactions[key]
        transactions[key] = existing
            ?.copy(events = existing.events + event)
            ?: ChargingTransaction(stationId, transactionId, listOf(event))
        Unit
    }

    /**
     * 이 슬롯에서 **지금 돌고 있는** 충전 트랜잭션.
     *
     * 같은 슬롯을 여러 트랜잭션이 지나가므로 (배터리를 빼고 새로 꽂으면 다음 트랜잭션이다),
     * 끝나지 않은 것 중 **가장 늦게 시작된** 것을 고른다. 재부팅으로 끊겨 남아 있는 옛
     * 트랜잭션이 새 것을 가리지 않게 하는 것이 요점이다 (S04.FR.11).
     */
    fun liveAt(stationId: StationId, slotId: SlotId): ChargingTransaction? =
        transactions.values
            .filter { it.stationId == stationId && it.slotId == slotId && !it.isEnded }
            .maxByOrNull { it.startedAt ?: Instant.MIN }

    /**
     * **어느 배터리**의 충전인지를 붙인다 (조회에서 슬롯과 배터리가 읽혀야 한다).
     *
     * `BatterySwapRequest(BatteryIn)` 이 `(evseId, serialNumber)` 를 실어 오는 그 순간이
     * CSMS 가 슬롯과 배터리를 잇는 **유일한 지점**이다. `TransactionEvent` 도 `NotifyEvent` 도
     * 일련번호를 싣지 않는다.
     *
     * 교환을 참조하지 않는다는 점이 중요하다. 이 함수가 받는 것은 `(슬롯, 일련번호)` 라는
     * 사실뿐이고, 그 뒤 교환이 완료되든 타임아웃이 나든 이 기록은 그대로다 (생명주기 분리).
     *
     * @return 붙인 뒤의 트랜잭션. 그 슬롯에 도는 트랜잭션이 없으면 `null` — 배터리가 꽂혔는데
     *   충전이 시작되지 않은 상태이고, 없는 트랜잭션을 만들어 내지 않는다.
     */
    fun linkBattery(stationId: StationId, slotId: SlotId, serialNumber: String): ChargingTransaction? =
        synchronized(lock) {
            val live = liveAt(stationId, slotId) ?: return null
            val linked = live.copy(batterySerialNumber = serialNumber)
            transactions[stationId to live.transactionId] = linked
            linked
        }

    fun find(stationId: StationId, transactionId: String): ChargingTransaction? =
        transactions[stationId to transactionId]

    fun of(stationId: StationId): List<ChargingTransaction> =
        transactions.values.filter { it.stationId == stationId }.sortedBy { it.transactionId }

    fun size(): Int = transactions.size
}
