package dev.swapve.csms.swap

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
)

/** 충전 트랜잭션 하나의 진행. 사건이 온 순서대로 쌓인다. */
data class ChargingTransaction(
    val stationId: StationId,
    val transactionId: String,
    val events: List<ChargingEvent>,
) {
    val startedAt: Instant? get() = events.firstOrNull()?.at
    val isEnded: Boolean get() = events.lastOrNull()?.eventType == "Ended"
}

/**
 * 충전 트랜잭션 기록소 (S04, PLAN §4.10).
 *
 * ### ★ 교환 트랜잭션과 절대 합치지 않는다 (PLAN §5.1)
 *
 * 저장소도 다르고 키도 다르다. 교환은 `(stationId, requestId)` 로 묶이고, 충전은
 * `(stationId, transactionId)` 로 묶인다. **들어온 배터리의 충전은 교환이 끝난 뒤에도 며칠
 * 계속된다** — 한 객체에 합치면 그 배터리가 언제까지 어느 교환에 매여 있는지가 거짓이 된다.
 *
 * ### 기록만 한다
 *
 * MVP 범위는 *"시뮬레이터가 발신하고 CSMS 가 수신·기록"* 까지다 (PLAN §4.10, §10 결정 #8).
 * 스마트차징도 요금도 여기서 하지 않는다. 나중에 붙일 때 필요한 것은 전부
 * 이벤트 로그(PLAN §11.1)와 이 기록 위의 순수 계산이다.
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

    fun find(stationId: StationId, transactionId: String): ChargingTransaction? =
        transactions[stationId to transactionId]

    fun of(stationId: StationId): List<ChargingTransaction> =
        transactions.values.filter { it.stationId == stationId }.sortedBy { it.transactionId }

    fun size(): Int = transactions.size
}
