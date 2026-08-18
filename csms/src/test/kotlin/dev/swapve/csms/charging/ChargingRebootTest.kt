package dev.swapve.csms.charging

import dev.swapve.csms.conformance.ConformanceScenario
import dev.swapve.csms.conformance.ConformanceScenario.callPayload
import dev.swapve.csms.conformance.ConformanceScenario.stationSent
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.swap.ChargingTransaction
import dev.swapve.csms.swap.ChargingTransactionRegistry
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.session.OcppEventRecord
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.swap.SlotId
import dev.swapve.swap.StationId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ★ **S04.FR.11 — 스테이션 재부팅** (PLAN §4.10).
 *
 * > *"스테이션 재부팅 시, 배터리가 들어 있는 모든 EVSE 에 대해 트랜잭션을 새로 시작한다."*
 *
 * ### CSMS 가 정상으로 받아야 하는 두 가지
 *
 * 1. **`Started` 가 무더기로 온다.** 배터리가 있는 슬롯마다 하나씩이다. 그 직전에 같은
 *    슬롯에서 다른 트랜잭션이 돌고 있었더라도 마찬가지다.
 * 2. **재부팅 전 트랜잭션은 종료 통보 없이 사라진다.** 전원이 나간 스테이션은 `Ended` 를
 *    보낼 기회가 없다. 끝나지 않은 채 남은 트랜잭션을 **오류로 취급하면 안 된다** — 그
 *    시점까지 관측된 사실은 사실이었고, 지우면 그 배터리가 언제까지 충전됐는지가 사라진다.
 *
 * `TC_S_103_CSMS` 의 함정 4 (*"시작을 본 적 없는 트랜잭션의 종료"*) 와 **거울상**이다.
 * 그쪽은 끝만 보고, 이쪽은 시작만 본다. 둘 다 정상이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
class ChargingRebootTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var chargingTransactions: ChargingTransactionRegistry

    @Autowired
    private lateinit var validator: OcppPayloadValidator

    @Test
    fun `재부팅하면 배터리가 있는 EVSE 마다 트랜잭션이 새로 시작된다`() {
        val run = runReboot("CS-REBOOT-START")

        // 배터리가 있는 슬롯은 3·4 다 (1·2 는 비어 있다). 재부팅 전에도 후에도 그 둘만 시작된다.
        assertEquals(
            ChargingScenario.DISPENSE_SLOTS.map { SlotId(it) },
            run.startedSlots(afterReboot = false),
            "부팅 시 시작된 슬롯",
        )
        assertEquals(
            ChargingScenario.DISPENSE_SLOTS.map { SlotId(it) },
            run.startedSlots(afterReboot = true),
            "재부팅 시 시작된 슬롯 — 배터리가 있는 EVSE 마다 하나씩이다 (S04.FR.11)",
        )

        // 빈 슬롯은 시작되지 않는다. 충전할 배터리가 없으니 당연하고, 그래서 확인한다.
        val allStarted = run.startedSlots(afterReboot = false) + run.startedSlots(afterReboot = true)
        ChargingScenario.INSERT_SLOTS.forEach { slotId ->
            assertTrue(SlotId(slotId) !in allStarted, "빈 슬롯 $slotId 이 충전을 시작했다")
        }
    }

    @Test
    fun `재부팅 전 트랜잭션은 종료 통보 없이 남고 CSMS 는 그것을 오류로 보지 않는다`() {
        val run = runReboot("CS-REBOOT-ORPHAN")
        val station = StationId(run.stationId)

        val beforeReboot = run.transactionIds(afterReboot = false)
        val afterReboot = run.transactionIds(afterReboot = true)

        // 새 트랜잭션이다 — 옛 식별자를 이어 쓰면 같은 트랜잭션이 두 번 시작한 것이 된다.
        assertEquals(2, beforeReboot.size)
        assertEquals(2, afterReboot.size)
        beforeReboot.forEach { old ->
            assertTrue(old !in afterReboot, "재부팅 뒤에도 같은 transactionId 를 쓴다: $old")
        }

        // 끊긴 트랜잭션은 **끝나지 않은 채로 남아 있다.** 그 자체가 재부팅의 기록이다.
        beforeReboot.forEach { transactionId ->
            val orphan = assertNotNull(
                chargingTransactions.find(station, transactionId),
                "재부팅 전 트랜잭션 $transactionId 이 사라졌다",
            )
            assertTrue(!orphan.isEnded, "$transactionId 에 오지도 않은 종료가 기록됐다")
            assertTrue(orphan.events.isNotEmpty())
        }

        // 그리고 새 트랜잭션이 그 슬롯의 현재 충전이다 — 옛 것이 가리지 않는다.
        ChargingScenario.DISPENSE_SLOTS.forEach { slotId ->
            val live = assertNotNull(chargingTransactions.liveAt(station, SlotId(slotId)))
            assertTrue(live.transactionId in afterReboot, "슬롯 $slotId 의 현재 충전이 옛 트랜잭션이다")
        }

        // ★ 오류 프레임이 하나도 없다. "무더기 Started" 를 CSMS 가 정상으로 받았다는 뜻이다.
        ConformanceScenario.assertNoErrorFrames(run.records)
        ConformanceScenario.assertAllSchemaValid(run.records, validator)
    }

    @Test
    fun `재부팅은 PowerUp 이 아니라 LocalReset 으로 알려진다`() {
        val run = runReboot("CS-REBOOT-REASON")

        val boots = run.records.stationSent()
            .filter { it.action == BatterySwapWire.BOOT_NOTIFICATION }
            .map { it.callPayload().path("reason").asText() }

        // 전원 인가와 재시작은 다른 사건이고, 표준이 그 둘을 구분할 값을 준다.
        assertEquals(listOf(BatterySwapWire.BOOT_REASON_POWER_UP, BatterySwapWire.BOOT_REASON_LOCAL_RESET), boots)

        val securityEvents = run.records.stationSent()
            .filter { it.action == BatterySwapWire.SECURITY_EVENT_NOTIFICATION }
            .map { it.callPayload().path("type").asText() }
        assertEquals(
            listOf(BatterySwapWire.SECURITY_EVENT_STARTUP, BatterySwapWire.SECURITY_EVENT_RESET_OR_REBOOT),
            securityEvents,
        )
    }

    @Test
    fun `재부팅 뒤에도 충전이 이어서 진행된다`() {
        val run = runReboot("CS-REBOOT-RESUME")
        val station = StationId(run.stationId)

        // 재부팅 뒤 새 트랜잭션에서 SoC 를 한 번 보고했다. 배터리의 충전은 계속되고 있고,
        // 끊긴 것은 트랜잭션이라는 기록 단위였을 뿐이다.
        val live: ChargingTransaction = assertNotNull(chargingTransactions.liveAt(station, SlotId(3)))
        val startingSoc = assertNotNull(ChargingScenario.PARTIALLY_CHARGED[3]).soC
        assertEquals(
            startingSoc + ChargingScenario.SOC_STEP * 2,
            assertNotNull(live.latestSoc),
            "재부팅 전 한 걸음 + 재부팅 후 한 걸음",
        )
    }

    // ------------------------------------------------------------------ 공통

    private class RebootRun(val stationId: String, val records: List<OcppEventRecord>, val rebootAt: Int) {

        /** 시뮬레이터가 보낸 `TransactionEvent(Started)` 들 — 재부팅 전/후로 갈라 본다. */
        private fun started(afterReboot: Boolean) = records
            .filterIndexed { index, _ -> (index >= rebootAt) == afterReboot }
            .stationSent()
            .filter { it.action == BatterySwapWire.TRANSACTION_EVENT }
            .map { it.callPayload() }
            .filter { it.path("eventType").asText() == BatterySwapWire.TX_STARTED }

        fun startedSlots(afterReboot: Boolean): List<SlotId> =
            started(afterReboot).map { SlotId(it.path("evse").path("id").asInt()) }

        fun transactionIds(afterReboot: Boolean): List<String> =
            started(afterReboot).map { it.path("transactionInfo").path("transactionId").asText() }
    }

    /**
     * 부팅 → 충전 조금 → **재부팅** → 충전 조금.
     *
     * 재부팅 전 슬롯 3 의 충전을 한 걸음 진행시켜 두는 것이 요점이다. 그래야 "진행 중이던
     * 트랜잭션"이 실제로 존재하고, 그것이 종료 없이 끊기는 상황이 만들어진다.
     */
    private fun runReboot(stationId: String): RebootRun {
        // 상한 아래에서 시작하는 배터리를 쓴다 — 그래야 재부팅 전후로 충전이 이어지는 것이 보인다.
        val config = ChargingScenario.config(port, stationId, dispensed = ChargingScenario.PARTIALLY_CHARGED)
        val (simulator, clock) = ChargingScenario.simulator(config)
        var rebootAt = 0

        simulator.use {
            runBlocking {
                it.connect()
                it.boot()

                clock.advance(ChargingScenario.SOC_STEP_INTERVAL.toMillis())
                it.advanceCharging(slotId = 3, byPercent = ChargingScenario.SOC_STEP)

                // 여기서부터가 재부팅 이후다.
                rebootAt = it.eventLog.of(stationId).size
                it.reboot()

                clock.advance(ChargingScenario.SOC_STEP_INTERVAL.toMillis())
                it.advanceCharging(slotId = 3, byPercent = ChargingScenario.SOC_STEP)
            }
        }

        return RebootRun(stationId, simulator.eventLog.of(stationId), rebootAt)
    }
}
