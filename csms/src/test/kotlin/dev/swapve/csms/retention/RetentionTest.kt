package dev.swapve.csms.retention

import dev.swapve.csms.config.CsmsProperties
import dev.swapve.csms.e2e.SwapScenario
import dev.swapve.csms.event.JdbcOcppEventLog
import dev.swapve.csms.recovery.EventLogRecovery
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.swap.ChargingTransactionRegistry
import dev.swapve.csms.swap.SlotStateRegistry
import dev.swapve.csms.swap.SwapTransactionRegistry
import dev.swapve.station.SwapOrder
import dev.swapve.swap.StationId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
class RetentionTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var eventLog: JdbcOcppEventLog

    @Autowired
    private lateinit var retention: EventLogRetention

    @Autowired
    private lateinit var recovery: EventLogRecovery

    @Autowired
    private lateinit var properties: CsmsProperties

    @BeforeEach
    fun clearEventLog() {
        jdbc.update("DELETE FROM ocpp_event")
    }

    @Test
    fun `보존 기간보다 오래된 행이 정리되고 최근 행은 남는다`() {
        val stationId = stationId("AGE")
        insertEvent(stationId, 1, FixedClockConfig.FIXED_NOW.minusSeconds(31 * DAY_SECONDS))
        insertEvent(stationId, 2, FixedClockConfig.FIXED_NOW.minusSeconds(DAY_SECONDS))

        retention.sweep()

        val records = eventLog.of(stationId)
        assertEquals(listOf(2L), records.map { it.seq })
    }

    @Test
    fun `기동 리플레이가 replay-window 밖의 이벤트를 읽지 않는다`() {
        val stationId = stationId("REPLAY")
        val config = SwapScenario.config(port, stationId, SwapOrder.IN_OUT, requestId = 9_300)
        SwapScenario.simulator(config).use { simulator ->
            runBlocking {
                simulator.connect()
                simulator.bootAndSwap()
            }
        }
        jdbc.update(
            "UPDATE ocpp_event SET occurred_at = ? WHERE station_id = ?",
            Timestamp.from(FixedClockConfig.FIXED_NOW.minusSeconds(8 * DAY_SECONDS)),
            stationId,
        )

        val restoredSwaps = SwapTransactionRegistry()
        val restoredSlots = SlotStateRegistry()
        val restoredCharging = ChargingTransactionRegistry()
        recovery.recover(restoredSwaps, restoredSlots, restoredCharging)

        val station = StationId(stationId)
        assertTrue(restoredSwaps.of(station).isEmpty())
        assertTrue(restoredSlots.of(station).isEmpty())
        assertTrue(restoredCharging.of(station).isEmpty())
    }

    @Test
    fun `max-events-per-station 안전망이 건수를 자른다`() {
        val stationId = stationId("CAP")
        (1L..10L).forEach { seq ->
            insertEvent(stationId, seq, FixedClockConfig.FIXED_NOW.minusMillis(10L - seq))
        }

        retention.sweep(maxPerStation = 5)

        assertEquals((6L..10L).toList(), eventLog.of(stationId).map { it.seq })
    }

    @Test
    fun `정리 결과가 관측 가능하다`() {
        val stationId = stationId("OBS")
        insertEvent(stationId, 1, FixedClockConfig.FIXED_NOW.minusSeconds(31 * DAY_SECONDS))
        (2L..8L).forEach { seq ->
            insertEvent(stationId, seq, FixedClockConfig.FIXED_NOW.minusMillis(8L - seq))
        }

        val sweep = retention.sweep(maxPerStation = 5)
        val station = sweep.stations.single { it.stationId == stationId }

        assertEquals(FixedClockConfig.FIXED_NOW, sweep.sweptAt)
        assertEquals(FixedClockConfig.FIXED_NOW.minus(properties.retention.eventLog), sweep.cutoff)
        assertEquals(1, station.byAge)
        assertEquals(2, station.byCap)
        assertEquals(3, sweep.totalDeleted)
        assertEquals((4L..8L).toList(), eventLog.of(stationId).map { it.seq })
    }

    @Test
    fun `기본 정책값`() {
        assertEquals(Duration.ofDays(30), properties.retention.eventLog)
        assertEquals(Duration.ofDays(7), properties.retention.replayWindow)
        assertEquals(100_000, properties.retention.maxEventsPerStation)
    }

    private fun stationId(prefix: String): String = "CS-RET-$prefix-${System.nanoTime()}"

    private fun insertEvent(stationId: String, seq: Long, occurredAt: Instant) {
        jdbc.update(
            """
            INSERT INTO ocpp_event (
                station_id, seq, direction, action, message_id, payload, occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            stationId,
            seq,
            "INBOUND",
            "TestAction",
            "msg-$stationId-$seq",
            """[2,"msg-$seq",{}]""",
            Timestamp.from(occurredAt),
        )
    }

    private companion object {
        const val DAY_SECONDS = 24L * 60L * 60L
    }
}
