package dev.swapve.csms.charging

import dev.swapve.csms.conformance.ConformanceScenario
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.support.MutableClock
import dev.swapve.csms.swap.ChargingTransaction
import dev.swapve.csms.swap.ChargingTransactionRegistry
import dev.swapve.csms.swap.SwapTransactionRegistry
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.session.OcppEventRecord
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.station.StationSimulator
import dev.swapve.swap.SlotId
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapTransaction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ★ **S04 충전 트랜잭션의 생명주기** (PLAN §4.10, §10 결정 #8 — 수신·기록만).
 *
 * 다섯 계기가 순서대로 일어나고, CSMS 가 그것을 기록한다:
 *
 * | 계기 | 메시지 |
 * |---|---|
 * | 배터리 삽입 | `Started(CablePluggedIn, EVConnected)` |
 * | 충전 시작 | `Updated(ChargingStateChanged, Charging)` |
 * | 충전 중 | `Updated(MeterValuePeriodic)` + measurand `SoC` (**S04.FR.04**) |
 * | `MaxSoc` 도달 | `Updated(EnergyLimitReached, SuspendedEVSE)` — **트랜잭션은 유지된다** |
 * | 배터리 제거 | `Ended(EnergyLimitReached, Idle, EVDisconnected)` |
 *
 * ### 실제 시간을 기다리지 않는다
 *
 * 스테이션의 시계를 시험이 민다 ([ChargingScenario.simulator]). SoC 가 "시간에 따라"
 * 오르는 것을 보이면서도 시험은 밀리초 안에 끝난다.
 *
 * ### 스마트차징도 요금도 없다 (결정 #8)
 *
 * 여기서 확인하는 것은 **무엇이 기록됐는가**뿐이다. CSMS 는 충전을 지시하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
class ChargingLifecycleTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var chargingTransactions: ChargingTransactionRegistry

    @Autowired
    private lateinit var swaps: SwapTransactionRegistry

    @Autowired
    private lateinit var validator: OcppPayloadValidator

    // ------------------------------------------------------------------ 삽입 · 충전 시작

    @Test
    fun `배터리 삽입이 Started_CablePluggedIn_EVConnected 로 기록된다`() {
        val run = runLifecycle("CS-CHG-STARTED", chargeToLimit = false, removeBatteries = false)

        val transaction = assertNotNull(run.chargingAt(1), "슬롯 1 의 충전 트랜잭션이 없다")
        val started = transaction.events.first()

        assertEquals(BatterySwapWire.TX_STARTED, started.eventType)
        assertEquals(BatterySwapWire.TRIGGER_REASON_CABLE_PLUGGED_IN, started.triggerReason)
        // ★ TxStartPoint = EVConnected (S04.FR.08) — Charging 이 아니다.
        assertEquals(BatterySwapWire.CHARGING_STATE_EV_CONNECTED, started.chargingState)
        assertEquals(SlotId(1), started.slotId)
        assertEquals(0, started.seqNo)
    }

    @Test
    fun `충전 시작이 Updated_ChargingStateChanged_Charging 으로 기록된다`() {
        val run = runLifecycle("CS-CHG-CHARGING", chargeToLimit = false, removeBatteries = false)

        val transaction = assertNotNull(run.chargingAt(1))
        val update = transaction.events[1]

        assertEquals(BatterySwapWire.TX_UPDATED, update.eventType)
        assertEquals(BatterySwapWire.TRIGGER_REASON_CHARGING_STATE_CHANGED, update.triggerReason)
        assertEquals(BatterySwapWire.CHARGING_STATE_CHARGING, update.chargingState)
        assertEquals(BatterySwapWire.CHARGING_STATE_CHARGING, transaction.chargingState)
        assertTrue(!transaction.isEnded && !transaction.isSuspended, "아직 살아 있고 멈추지도 않았다")
    }

    // ------------------------------------------------------------------ S04.FR.04 — SoC 진행

    @Test
    fun `SoC 가 주기 보고로 올라간다`() {
        val run = runLifecycle("CS-CHG-SOC", chargeToLimit = true, removeBatteries = false)

        val transaction = assertNotNull(run.chargingAt(1))
        val samples = transaction.events.filter { it.socPercent != null }

        // 23 → 33 → 43 → 50(상한). 마지막 하나는 SuspendedEVSE 사건이 같은 값을 다시 싣는다.
        val reported = samples.map { it.socPercent }
        assertEquals(listOf(33.0, 43.0, 50.0, 50.0), reported, "보고된 SoC 자취")
        assertEquals(50.0, transaction.latestSoc)

        // 주기 보고는 chargingState 를 싣지 않는다 — 상태가 바뀌지 않았기 때문이다
        // (스키마: "required when state has changed"). 그래도 트랜잭션의 상태는 읽힌다.
        val periodic = samples.filter { it.triggerReason == BatterySwapWire.TRIGGER_REASON_METER_VALUE_PERIODIC }
        assertEquals(3, periodic.size)
        periodic.forEach { assertNull(it.chargingState, "주기 보고가 상태 변경을 자칭했다") }

        // 시각이 실제로 흘렀다 — 시계를 밀었을 뿐 기다리지 않았다.
        val times = periodic.map { it.at }
        assertEquals(times.sorted(), times, "보고 시각이 앞으로 가지 않았다")
        assertTrue(times.first() > FixedClockConfig.FIXED_NOW, "시각이 멈춰 있다")
    }

    // ------------------------------------------------------------------ ★ MaxSoc 도달

    @Test
    fun `MaxSoc 에 닿으면 SuspendedEVSE 이고 트랜잭션은 살아 있다`() {
        val run = runLifecycle("CS-CHG-SUSPEND", chargeToLimit = true, removeBatteries = false)

        val transaction = assertNotNull(run.chargingAt(1))
        val suspend = transaction.events.last()

        assertEquals(BatterySwapWire.TX_UPDATED, suspend.eventType, "종료가 아니라 갱신이다")
        assertEquals(BatterySwapWire.TRIGGER_REASON_ENERGY_LIMIT_REACHED, suspend.triggerReason)
        assertEquals(BatterySwapWire.CHARGING_STATE_SUSPENDED_EVSE, suspend.chargingState)

        // ★ 여기가 핵심이다 — 멈춘 것은 에너지 흐름이지 트랜잭션이 아니다 (PLAN §4.10 표 4행).
        assertTrue(transaction.isSuspended, "SuspendedEVSE 로 읽히지 않는다")
        assertTrue(!transaction.isEnded, "상한 도달로 트랜잭션이 종료됐다 — 종료는 배터리를 뺄 때다")
        assertNull(suspend.stoppedReason, "멈춤에는 stoppedReason 이 없다 — 끝난 것이 아니기 때문이다")
    }

    // ------------------------------------------------------------------ 배터리 제거

    @Test
    fun `배터리 제거가 Ended 세 값으로 기록된다`() {
        val run = runLifecycle("CS-CHG-ENDED", chargeToLimit = false, removeBatteries = true)

        // 반출 슬롯(3·4)의 트랜잭션이 닫혔다.
        ChargingScenario.DISPENSE_SLOTS.forEach { slotId ->
            val transaction = assertNotNull(
                run.transactionsOf().firstOrNull { it.slotId == SlotId(slotId) },
                "슬롯 $slotId 의 충전 트랜잭션이 없다",
            )
            val ended = transaction.events.last()

            // ★ v3.1 정정값 그대로다 (PLAN §0). 셋 다 enum 으로는 유효해서 스키마가 잡지 못한다.
            assertEquals(BatterySwapWire.TX_ENDED, ended.eventType)
            assertEquals(BatterySwapWire.TRIGGER_REASON_ENERGY_LIMIT_REACHED, ended.triggerReason, "왜 끝났나")
            assertEquals(BatterySwapWire.STOPPED_REASON_EV_DISCONNECTED, ended.stoppedReason, "무엇이 끊겼나")
            assertEquals(BatterySwapWire.CHARGING_STATE_IDLE, ended.chargingState, "끝난 뒤의 상태")
            assertTrue(transaction.isEnded)
        }
    }

    // ------------------------------------------------------------------ ★★ PLAN §5.1 — 두 생명주기의 독립

    @Test
    fun `교환이 Completed 가 된 뒤에도 들어온 배터리의 충전은 계속된다`() {
        val run = runLifecycle("CS-CHG-INDEPENDENT", chargeToLimit = true, removeBatteries = true)

        // 교환은 끝났다 — 들어온 수 = 나간 수 (PLAN §5.3).
        val swap = assertNotNull(swaps.find(StationId(run.stationId), run.requestId), "교환이 열리지 않았다")
        val completed = assertIs<SwapTransaction.Completed>(swap, "교환이 완료되지 않았다: $swap")
        assertEquals(2, completed.batteriesIn.size)
        assertEquals(2, completed.batteriesOut.size)

        // ★ 그런데 들어온 배터리의 충전은 **여전히 살아 있다.** 며칠 더 계속될 것이다.
        ChargingScenario.INSERT_SLOTS.forEach { slotId ->
            val charging = assertNotNull(run.chargingAt(slotId), "슬롯 $slotId 의 충전이 없다")
            assertTrue(
                !charging.isEnded,
                "교환이 끝났다고 충전 트랜잭션까지 닫혔다 — 두 생명주기가 합쳐졌다는 뜻이다 (PLAN §5.1)",
            )
        }

        // 슬롯 1 은 상한에 닿아 멈췄고, 슬롯 2 는 아직 충전 중이다. 교환 하나에 매인 상태가 아니다.
        assertTrue(assertNotNull(run.chargingAt(1)).isSuspended)
        assertEquals(
            BatterySwapWire.CHARGING_STATE_CHARGING,
            assertNotNull(run.chargingAt(2)).chargingState,
        )
    }

    @Test
    fun `충전 트랜잭션 조회에서 어느 슬롯의 어느 배터리인지 읽힌다`() {
        val run = runLifecycle("CS-CHG-IDENTITY", chargeToLimit = true, removeBatteries = true)

        val charging = assertNotNull(run.chargingAt(1))

        assertEquals(SlotId(1), charging.slotId, "어느 슬롯인가")
        // `BatterySwap(BatteryIn)` 이 실어 온 일련번호가 붙는다 — 그것이 CSMS 가 슬롯과
        // 배터리를 잇는 유일한 지점이다.
        assertEquals(ChargingScenario.INCOMING[0].serialNumber, charging.batterySerialNumber, "어느 배터리인가")
        assertEquals(50.0, charging.latestSoc, "얼마나 찼나")

        // 부팅부터 꽂혀 있던 배터리는 일련번호를 알 길이 없다 — 모르는 것을 지어내지 않는다.
        val preexisting = assertNotNull(run.transactionsOf().firstOrNull { it.slotId == SlotId(3) })
        assertNull(preexisting.batterySerialNumber)
    }

    // ------------------------------------------------------------------ 프로토콜

    @Test
    fun `오간 모든 메시지가 공식 스키마를 통과하고 오류 프레임이 없다`() {
        val run = runLifecycle("CS-CHG-SCHEMA", chargeToLimit = true, removeBatteries = true)

        ConformanceScenario.assertAllSchemaValid(run.records, validator)
        ConformanceScenario.assertNoErrorFrames(run.records)
    }

    // ------------------------------------------------------------------ 공통

    private inner class LifecycleRun(
        val stationId: String,
        val requestId: Int,
        val records: List<OcppEventRecord>,
    ) {
        fun transactionsOf(): List<ChargingTransaction> = chargingTransactions.of(StationId(stationId))

        /** 그 슬롯에서 도는 (끝나지 않은) 충전 트랜잭션. */
        fun chargingAt(slotId: Int): ChargingTransaction? =
            chargingTransactions.liveAt(StationId(stationId), SlotId(slotId))
    }

    /**
     * 교환 1건을 돌리되 **충전 진행을 사이에 끼운다.**
     *
     * @param chargeToLimit 슬롯 1 을 상한까지 충전한다. 걸음마다 시계를 민다 — `sleep` 은 없다.
     * @param removeBatteries 반출까지 진행해 교환을 완료시킨다.
     */
    private fun runLifecycle(
        stationId: String,
        chargeToLimit: Boolean,
        removeBatteries: Boolean,
    ): LifecycleRun {
        val config = ChargingScenario.config(port, stationId)
        val (simulator, clock) = ChargingScenario.simulator(config)

        simulator.use {
            runBlocking {
                it.connect()
                it.boot()
                it.authorize()
                it.insertBatteries()
                it.reportChargingStarted()

                if (chargeToLimit) chargeSlot(it, clock, slotId = 1)
                if (removeBatteries) it.removeBatteries()
            }
        }

        return LifecycleRun(stationId, config.requestId, simulator.eventLog.of(stationId))
    }

    /** 상한에 닿을 때까지 한 걸음씩 충전한다. **걸음 사이에 시각이 흐른다.** */
    private suspend fun chargeSlot(
        simulator: StationSimulator,
        clock: MutableClock,
        slotId: Int,
    ) {
        while (!simulator.isChargingSuspended(slotId)) {
            clock.advance(ChargingScenario.SOC_STEP_INTERVAL.toMillis())
            simulator.advanceCharging(slotId, ChargingScenario.SOC_STEP)
        }
    }
}
