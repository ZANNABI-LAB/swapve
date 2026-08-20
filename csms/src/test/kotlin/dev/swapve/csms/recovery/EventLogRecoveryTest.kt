package dev.swapve.csms.recovery

import dev.swapve.csms.e2e.SwapScenario
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.swap.ChargingTransactionRegistry
import dev.swapve.csms.swap.SlotStateRegistry
import dev.swapve.csms.swap.SwapTransactionRegistry
import dev.swapve.station.SwapOrder
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapTransaction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertIs

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
class EventLogRecoveryTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var liveSwaps: SwapTransactionRegistry

    @Autowired
    private lateinit var liveSlots: SlotStateRegistry

    @Autowired
    private lateinit var liveCharging: ChargingTransactionRegistry

    @Autowired
    private lateinit var recovery: EventLogRecovery

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `DB 이벤트 로그만으로 진행 중 상태와 완료 상태를 복원한다`() {
        val suffix = System.nanoTime().toString()
        val completedStation = "CS-REC-COMPLETE-$suffix"
        val halfInStation = "CS-REC-HALF-$suffix"
        val completedRequestId = 9_001
        val halfInRequestId = 9_002

        runCompleted(completedStation, completedRequestId)
        runHalfIn(halfInStation, halfInRequestId)

        val completed = StationId(completedStation)
        val halfIn = StationId(halfInStation)
        val stations = listOf(completedStation, halfInStation)

        val expectedSwaps = mapOf(
            completed to liveSwaps.of(completed),
            halfIn to liveSwaps.of(halfIn),
        )
        val expectedSlots = mapOf(
            completed to liveSlots.of(completed),
            halfIn to liveSlots.of(halfIn),
        )
        val expectedCharging = mapOf(
            completed to liveCharging.of(completed),
            halfIn to liveCharging.of(halfIn),
        )
        val ledgerRowsBefore = ledgerRows()

        val restoredSwaps = SwapTransactionRegistry()
        val restoredSlots = SlotStateRegistry()
        val restoredCharging = ChargingTransactionRegistry()
        recovery.recover(restoredSwaps, restoredSlots, restoredCharging, stations)

        assertEquals(expectedSwaps[completed], restoredSwaps.of(completed))
        assertEquals(expectedSwaps[halfIn], restoredSwaps.of(halfIn))
        assertIs<SwapTransaction.Completed>(restoredSwaps.find(completed, completedRequestId))
        assertIs<SwapTransaction.HalfIn>(restoredSwaps.find(halfIn, halfInRequestId))

        assertEquals(expectedSlots[completed], restoredSlots.of(completed))
        assertEquals(expectedSlots[halfIn], restoredSlots.of(halfIn))
        assertEquals(expectedCharging[completed], restoredCharging.of(completed))
        assertEquals(expectedCharging[halfIn], restoredCharging.of(halfIn))

        stations.forEach(::assertContiguousSeq)
        assertEquals(ledgerRowsBefore, ledgerRows(), "복구가 OUT_TIMED_OUT 장부를 다시 쓰면 안 된다")
    }

    private fun runCompleted(stationId: String, requestId: Int) {
        val config = SwapScenario.config(port, stationId, SwapOrder.IN_OUT, requestId)
        SwapScenario.simulator(config).use { simulator ->
            runBlocking {
                simulator.connect()
                simulator.bootAndSwap()
            }
        }
    }

    private fun runHalfIn(stationId: String, requestId: Int) {
        val config = SwapScenario.config(port, stationId, SwapOrder.IN_OUT, requestId)
        SwapScenario.simulator(config).use { simulator ->
            runBlocking {
                simulator.connect()
                simulator.boot()
                simulator.authorize()
                simulator.insertBatteries()
            }
        }
    }

    private fun assertContiguousSeq(stationId: String) {
        val seqs = jdbc.queryForList(
            "SELECT seq FROM ocpp_event WHERE station_id = ? ORDER BY seq",
            Long::class.java,
            stationId,
        )
        assertEquals((1L..seqs.size.toLong()).toList(), seqs)
    }

    private fun ledgerRows(): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM swap_out_timed_out", Int::class.java) ?: 0
}
