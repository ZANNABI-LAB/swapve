package dev.swapve.csms.retention

import dev.swapve.csms.config.CsmsProperties
import dev.swapve.csms.event.JdbcOcppEventLog
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class StationSweep(
    val stationId: String,
    val byAge: Int,
    val byCap: Int,
)

data class RetentionSweep(
    val sweptAt: Instant,
    val cutoff: Instant,
    val stations: List<StationSweep>,
    val totalDeleted: Int,
)

@Component
class EventLogRetention(
    private val eventLog: JdbcOcppEventLog,
    private val properties: CsmsProperties,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(EventLogRetention::class.java)

    fun sweep(
        now: Instant = clock.instant(),
        retention: Duration = properties.retention.eventLog,
        maxPerStation: Int = properties.retention.maxEventsPerStation,
    ): RetentionSweep {
        val cutoff = now.minus(retention)
        val stations = eventLog.stationIds()
        val swept = stations.map { stationId ->
            val byAge = eventLog.deleteOlderThan(stationId, cutoff)
            val byCap = eventLog.trimToMaxPerStation(stationId, maxPerStation)
            logger.info(
                "event log retention station={} cutoff={} maxPerStation={} deletedByAge={} deletedByCap={}",
                stationId,
                cutoff,
                maxPerStation,
                byAge,
                byCap,
            )
            StationSweep(stationId, byAge, byCap)
        }
        val totalDeleted = swept.sumOf { it.byAge + it.byCap }
        logger.info(
            "event log retention sweptAt={} cutoff={} stations={} totalDeleted={}",
            now,
            cutoff,
            swept.size,
            totalDeleted,
        )
        return RetentionSweep(now, cutoff, swept, totalDeleted)
    }
}

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "csms.retention", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class EventLogRetentionScheduling

@Component
@ConditionalOnProperty(prefix = "csms.retention", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class EventLogRetentionRunner(private val retention: EventLogRetention) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        retention.sweep()
    }

    @Scheduled(fixedDelayString = "\${csms.retention.sweep-interval:1h}")
    fun sweep() {
        retention.sweep()
    }
}
