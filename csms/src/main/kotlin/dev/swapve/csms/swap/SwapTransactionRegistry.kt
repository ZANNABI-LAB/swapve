package dev.swapve.csms.swap

import dev.swapve.ocpp.swap.BatteryRejectionReason
import dev.swapve.swap.AnomalyReason
import dev.swapve.swap.IgnoreReason
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapKey
import dev.swapve.swap.SwapRequestId
import dev.swapve.swap.SwapTransaction
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 교환 진행 중 관측된 이상 (PLAN §5.4 F5).
 *
 * **응답과 무관하다.** `BatterySwap` 은 거부할 수 없으므로 (PLAN §4.3) 규약 위반도 정상
 * 회신되고, 사실만 여기 남는다.
 */
data class SwapAnomaly(
    val key: SwapKey,
    val reason: AnomalyReason,
    val description: String,
    val at: Instant,
)

/**
 * 멱등 무시 기록 (PLAN §5.4 F4/F6).
 *
 * 실패가 아니다. 재전송·중복 수신은 정상적으로 일어나고, **두 번 반영되지 않았다**는 사실이
 * 기록으로 남아야 나중에 장부를 설명할 수 있다.
 */
data class SwapIgnored(
    val key: SwapKey,
    val reason: IgnoreReason,
    val at: Instant,
)

/**
 * S02 원격 개시가 스테이션에게 거부당한 기록 (PLAN §5.4 F1).
 *
 * 교환 트랜잭션이 **열리지 않았다.** 그래서 [SwapTransaction] 이 아니라 별도 기록이다 —
 * 상태 없는 것을 상태로 만들면 "인가됐다가 취소된 교환"처럼 보인다.
 *
 * **재고 판정은 스테이션이 한다** (PLAN §4.5, S02.FR.04). CSMS 는 사유를 만들지 않고 받아
 * 적을 뿐이며, 그게 `TC_S_102_CSMS` 다.
 *
 * @param reasonCode 스테이션이 보낸 `statusInfo.reasonCode` **원문**. 부록
 *   `reason_codes.csv` 밖의 값이 와도 버리지 않는다 (PLAN §11.0) — 해석은 [reason] 이 한다.
 */
data class SwapRejection(
    val key: SwapKey,
    val idToken: dev.swapve.swap.IdToken,
    val reasonCode: String?,
    val additionalInfo: String?,
    val at: Instant,
) {
    /** 사전 정의된 사유면 그 값, 아니면 `null`. 원문은 [reasonCode] 에 남아 있다. */
    val reason: BatteryRejectionReason? get() = BatteryRejectionReason.ofWire(reasonCode)
}

/**
 * 교환 트랜잭션 보관소 — `(stationId, requestId)` 로 조회한다 (PLAN §5.3).
 *
 * ### 왜 복합키인가
 *
 * `requestId` 는 **스테이션 범위에서만 유일하다.** 스펙에 전역 유일성 규정이 없으므로 서로
 * 다른 스테이션이 같은 번호를 써도 충돌해서는 안 된다. [SwapKey] 가 그 복합키다.
 *
 * ### 여기까지가 M6 다
 *
 * REST 조회는 **M8** 이다 (PLAN §8). 지금은 내부 보관과 조회 함수까지만 만든다 — 없는
 * 소비자를 위해 엔드포인트를 미리 뚫는 것이 과설계다 (PLAN §11.0).
 *
 * ### 상태는 여기서 계산하지 않는다
 *
 * 전이는 전부 `swap-domain` 의 `SwapStateMachine` 이 한다 (M3). 이 클래스는 **마지막 상태를
 * 들고 있을 뿐**이고, 그 상태 전부는 이벤트 로그(PLAN §11.1)에서 다시 계산될 수 있다.
 *
 * 스레드 안전하다.
 */
@Component
class SwapTransactionRegistry {

    private val transactions = ConcurrentHashMap<SwapKey, SwapTransaction>()

    private val recordLock = Any()
    private val anomalies = ArrayList<SwapAnomaly>()
    private val ignored = ArrayList<SwapIgnored>()
    private val rejections = ArrayList<SwapRejection>()

    /** 이 교환의 현재 상태. 처음 보는 키면 [SwapTransaction.Idle] 이다. */
    fun stateOf(key: SwapKey): SwapTransaction = transactions[key] ?: SwapTransaction.Idle

    /** 전이 결과를 반영한다. `Ignored`/`Anomaly` 도 상태를 그대로 돌려주므로 같은 자리로 들어온다. */
    fun store(key: SwapKey, state: SwapTransaction) {
        transactions[key] = state
    }

    /** `(stationId, requestId)` 로 조회한다. 진행 중이든 끝났든 마지막 상태를 돌려준다. */
    fun find(stationId: StationId, requestId: Int): SwapTransaction? =
        transactions[SwapKey(stationId, SwapRequestId(requestId))]

    /** 이 스테이션에서 진행됐던 교환 전부. */
    fun of(stationId: StationId): Map<SwapKey, SwapTransaction> =
        transactions.filterKeys { it.stationId == stationId }

    /**
     * 스테이션을 가로지르는 교환 전부 — **지표(M8, PLAN §2 S5)가 읽는 자리다.**
     *
     * `SwapTransaction.Idle` 이 섞여 있을 수 있다. 인가 없이 도착한 사건(PLAN §5.4 F5)은
     * 상태를 바꾸지 못하고 `Idle` 그대로 저장되기 때문이다 — **열린 적 없는 교환**이므로
     * 세는 쪽에서 걸러야 한다. 여기서 미리 빼지 않는 이유는, 그러면 "왜 저장했는데 안
     * 보이나"를 이 함수를 읽는 사람이 알 수 없기 때문이다.
     */
    fun all(): Map<SwapKey, SwapTransaction> = transactions.toMap()

    fun recordAnomaly(anomaly: SwapAnomaly) = synchronized(recordLock) { anomalies += anomaly; Unit }

    fun recordIgnored(event: SwapIgnored) = synchronized(recordLock) { ignored += event; Unit }

    fun recordRejection(rejection: SwapRejection) = synchronized(recordLock) { rejections += rejection; Unit }

    fun anomalies(): List<SwapAnomaly> = synchronized(recordLock) { anomalies.toList() }

    fun ignoredEvents(): List<SwapIgnored> = synchronized(recordLock) { ignored.toList() }

    /** 스테이션이 거부한 원격 개시들 (PLAN §5.4 F1). */
    fun rejections(): List<SwapRejection> = synchronized(recordLock) { rejections.toList() }
}
