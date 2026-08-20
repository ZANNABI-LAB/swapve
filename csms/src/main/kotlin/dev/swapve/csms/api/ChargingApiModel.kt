package dev.swapve.csms.api

import dev.swapve.csms.swap.ChargingTransaction
import dev.swapve.ocpp.swap.BatterySwapWire
import java.time.Instant

/**
 * 충전 트랜잭션의 진행 상태 (S04).
 *
 * 원문의 `chargingState` 문자열을 그대로 내보내지 않는다. 그 값은 표준이 늘릴 수 있고
 * (`SuspendedEV` 등), 앱·운영 도구가 알아야 하는 것은 **"충전 중인가 · 멈췄나 · 끝났나"**
 * 셋이다. 경계에서 한 번 옮기는 값이 그 대가다 ([SwapStatus] 와 같은 이유).
 */
enum class ChargingStatus {

    /** 배터리가 꽂혔고 트랜잭션이 열렸다. 아직 급전이 시작되지는 않았다 (`TxStartPoint = EVConnected`). */
    CONNECTED,

    /** 충전 중이다. SoC 가 주기 보고로 올라온다 (S04.FR.04). */
    CHARGING,

    /**
     * 충전 상한(`MaxSoc`)에 닿아 급전이 멈췄다 (S04.FR.06).
     *
     * **끝난 것이 아니다.** 배터리는 슬롯에 그대로 있고, 트랜잭션은 누군가 꺼내갈 때 닫힌다.
     */
    SUSPENDED,

    /** 배터리가 빠져 트랜잭션이 닫혔다 (`TxStopPoint = EVConnected`, S04.FR.09). */
    ENDED,

    /** 상태를 알려 온 사건이 아직 없다. 모른다는 뜻이지 "충전 안 함"이 아니다. */
    UNKNOWN,
}

/**
 * 충전 트랜잭션 1건의 조회 결과 — `GET /api/stations/{stationId}/charging-transactions/{transactionId}`.
 *
 * ### 교환 조회와 **다른 자원**이다
 *
 * 경로부터 다르다. 충전은 스테이션의 슬롯에 매인 것이고 교환은 이용자의 요청에 매인 것이라,
 * 한 자원으로 합치면 *"교환이 끝났는데 충전은 계속된다"* 를 표현할 수 없다. [swapId] 같은
 * 역참조 필드를 두지 않은 것도 같은 이유다 — 이 배터리는 어느 교환에도 매여 있지 않다.
 *
 * @param slotId 어느 슬롯(EVSE)의 충전인가.
 * @param batterySerialNumber 어느 배터리인가. 교환으로 들어온 배터리면 값이 있고, 부팅
 *   시점부터 꽂혀 있던 배터리는 `null` 이다 — `TransactionEvent` 가 일련번호를 싣지 않아
 * **CSMS 가 정말로 모른다**. 알고 싶으면 `GetVariables` 로 물어야 한다.
 * @param socPercent 마지막으로 보고된 SoC (S04.FR.04). 계량값을 받지 못했으면 `null` 이다 —
 *   0 으로 채우면 "0% 로 측정됐다"는 거짓이 된다.
 */
data class ChargingTransactionView(
    val self: String,
    val stationId: String,
    val transactionId: String,
    val slotId: Int?,
    val batterySerialNumber: String?,
    val status: ChargingStatus,
    val socPercent: Double?,
    val startedAt: Instant?,
    val updatedAt: Instant?,
    val eventCount: Int,
) {
    companion object {

        fun of(transaction: ChargingTransaction) = ChargingTransactionView(
            self = ChargingIds.pathOf(transaction),
            stationId = transaction.stationId.value,
            transactionId = transaction.transactionId,
            slotId = transaction.slotId?.value,
            batterySerialNumber = transaction.batterySerialNumber,
            status = statusOf(transaction),
            socPercent = transaction.latestSoc,
            startedAt = transaction.startedAt,
            updatedAt = transaction.events.lastOrNull()?.at,
            eventCount = transaction.events.size,
        )

        private fun statusOf(transaction: ChargingTransaction): ChargingStatus = when {
            transaction.isEnded -> ChargingStatus.ENDED
            transaction.isSuspended -> ChargingStatus.SUSPENDED
            transaction.chargingState == BatterySwapWire.CHARGING_STATE_CHARGING -> ChargingStatus.CHARGING
            transaction.chargingState == BatterySwapWire.CHARGING_STATE_EV_CONNECTED -> ChargingStatus.CONNECTED
            else -> ChargingStatus.UNKNOWN
        }
    }
}

/** 충전 트랜잭션 조회 자원의 경로. `SwapIds` 와 같은 자리에 있는 규칙이다. */
object ChargingIds {

    fun pathOf(transaction: ChargingTransaction): String =
        "/api/stations/${transaction.stationId.value}/charging-transactions/${transaction.transactionId}"
}
