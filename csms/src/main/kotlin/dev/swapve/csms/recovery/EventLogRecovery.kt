package dev.swapve.csms.recovery

import dev.swapve.csms.audit.EventLogReplay
import dev.swapve.csms.audit.EventLogReplay.ReplayBattery
import dev.swapve.csms.audit.EventLogReplay.ReplaySwap
import dev.swapve.csms.config.CsmsProperties
import dev.swapve.csms.event.JdbcOcppEventLog
import dev.swapve.csms.swap.ChargingEvent
import dev.swapve.csms.swap.ChargingTransactionRegistry
import dev.swapve.csms.swap.SlotStateRegistry
import dev.swapve.csms.swap.SwapTransactionRegistry
import dev.swapve.swap.BatteryData
import dev.swapve.swap.SlotId
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapKey
import dev.swapve.swap.SwapRequestId
import dev.swapve.swap.SwapTransaction
import dev.swapve.swap.key
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * OCPP 원문 이벤트 로그에서 파생 레지스트리를 복원한다.
 *
 * 복구 본체는 [recover] 로 분리되어 있어서 테스트가 새 레지스트리를 만들어 DB 로그만으로
 * 같은 상태가 나오는지 직접 확인할 수 있다.
 */
@Component
class EventLogRecovery(
    private val eventLog: JdbcOcppEventLog,
    private val clock: Clock,
    private val properties: CsmsProperties,
) {

    fun recover(
        swaps: SwapTransactionRegistry,
        slots: SlotStateRegistry,
        charging: ChargingTransactionRegistry,
        stationIds: List<String>? = null,
    ) {
        val since = clock.instant().minus(properties.retention.replayWindow)
        val replayStations = stationIds ?: eventLog.stationIdsSince(since)
        replayStations.forEach { stationId ->
            recoverStation(EventLogReplay.replay(stationId, eventLog.of(stationId, since)), swaps, slots, charging)
        }
    }

    private fun recoverStation(
        replayed: EventLogReplay.ReplayedStation,
        swaps: SwapTransactionRegistry,
        slots: SlotStateRegistry,
        charging: ChargingTransactionRegistry,
    ) {
        val station = StationId(replayed.stationId)

        replayed.swaps.values
            .mapNotNull(::swapStateOf)
            .forEach { state -> swaps.store(requireNotNull(state.key), state) }

        replayed.slotStates.forEach { (slotId, state) ->
            val at = replayed.slotObservedAt[slotId] ?: replayed.records.lastOrNull()?.occurredAt
                ?: return@forEach
            slots.observe(station, SlotId(slotId), state, at)
        }

        replayed.charging.values.forEach { transaction ->
            transaction.events.forEach { event ->
                charging.record(
                    stationId = station,
                    transactionId = transaction.transactionId,
                    event = ChargingEvent(
                        eventType = event.eventType,
                        triggerReason = event.triggerReason,
                        seqNo = event.seqNo,
                        slotId = event.slotId?.let(::SlotId),
                        chargingState = event.chargingState,
                        stoppedReason = event.stoppedReason,
                        idToken = event.idToken,
                        at = event.at,
                        socPercent = event.socPercent,
                    ),
                )
            }
        }

        replayed.swaps.values.forEach { swap ->
            swap.batteriesIn.forEach { battery ->
                charging.linkBattery(station, SlotId(battery.slotId), battery.serialNumber)
            }
        }
    }

    private fun swapStateOf(swap: ReplaySwap): SwapTransaction? {
        val key = SwapKey(StationId(swap.stationId), SwapRequestId(swap.requestId))
        val authorizedAt = swap.authorizedAt ?: swap.inAt ?: swap.outAt ?: swap.timedOutAt ?: return null

        return when {
            swap.outTimedOut && swap.batteriesIn.isNotEmpty() ->
                SwapTransaction.OutTimedOut(
                    key = key,
                    idToken = swap.idToken,
                    authorizedAt = authorizedAt,
                    orphanBatteriesIn = swap.batteriesIn.map(::batteryDataOf),
                    startedAt = swap.inAt ?: authorizedAt,
                    timedOutAt = swap.timedOutAt ?: swap.lastEventAt() ?: authorizedAt,
                )

            swap.batteriesIn.isNotEmpty() && swap.batteriesOut.isNotEmpty() ->
                SwapTransaction.Completed(
                    key = key,
                    idToken = swap.idToken,
                    authorizedAt = authorizedAt,
                    batteriesIn = swap.batteriesIn.map(::batteryDataOf),
                    batteriesOut = swap.batteriesOut.map(::batteryDataOf),
                    startedAt = minOf(requireNotNull(swap.inAt), requireNotNull(swap.outAt)),
                    completedAt = maxOf(requireNotNull(swap.inAt), requireNotNull(swap.outAt)),
                )

            swap.batteriesIn.isNotEmpty() ->
                SwapTransaction.HalfIn(
                    key = key,
                    idToken = swap.idToken,
                    authorizedAt = authorizedAt,
                    batteriesIn = swap.batteriesIn.map(::batteryDataOf),
                    inAt = swap.inAt ?: authorizedAt,
                )

            swap.batteriesOut.isNotEmpty() ->
                SwapTransaction.HalfOut(
                    key = key,
                    idToken = swap.idToken,
                    authorizedAt = authorizedAt,
                    batteriesOut = swap.batteriesOut.map(::batteryDataOf),
                    outAt = swap.outAt ?: authorizedAt,
                )

            else ->
                SwapTransaction.Authorized(key, swap.idToken, authorizedAt)
        }
    }

    private fun ReplaySwap.lastEventAt() = listOfNotNull(inAt, outAt, timedOutAt).maxOrNull()

    private fun batteryDataOf(battery: ReplayBattery): BatteryData =
        BatteryData(
            slotId = SlotId(battery.slotId),
            serialNumber = battery.serialNumber,
            soC = battery.soC,
            soH = battery.soH,
        )
}

@Component
@ConditionalOnProperty(prefix = "csms.recovery", name = ["enabled"], havingValue = "true")
class EventLogRecoveryRunner(
    private val recovery: EventLogRecovery,
    private val swaps: SwapTransactionRegistry,
    private val slots: SlotStateRegistry,
    private val charging: ChargingTransactionRegistry,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        recovery.recover(swaps, slots, charging)
    }
}
