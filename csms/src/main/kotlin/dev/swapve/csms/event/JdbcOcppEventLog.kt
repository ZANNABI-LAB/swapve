package dev.swapve.csms.event

import dev.swapve.ocpp.session.MessageDirection
import dev.swapve.ocpp.session.OcppEventRecord
import dev.swapve.ocpp.session.OcppEventSink
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * JDBC 기반 OCPP 이벤트 로그.
 *
 * `seq` 는 스테이션 범위에서만 증가한다. 프로세스가 다시 떠도 첫 append 때 DB 의
 * `MAX(seq)` 를 읽어 이어 붙인다.
 */
class JdbcOcppEventLog(private val jdbc: JdbcTemplate) : OcppEventSink {

    private val stationLocks = ConcurrentHashMap<String, Any>()
    private val nextSeqByStation = ConcurrentHashMap<String, Long>()

    override fun append(
        stationId: String,
        direction: MessageDirection,
        action: String?,
        messageId: String,
        payload: String,
        occurredAt: Instant,
    ): OcppEventRecord {
        val lock = stationLocks.computeIfAbsent(stationId) { Any() }
        return synchronized(lock) {
            val seq = nextSeqByStation.computeIfAbsent(stationId) { seedNextSeq(it) }
            val record = OcppEventRecord(
                seq = seq,
                stationId = stationId,
                direction = direction,
                action = action,
                messageId = messageId,
                payload = payload,
                occurredAt = occurredAt,
            )
            jdbc.update(
                """
                INSERT INTO ocpp_event (
                    station_id, seq, direction, action, message_id, payload, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                record.stationId,
                record.seq,
                record.direction.name,
                record.action,
                record.messageId,
                record.payload,
                Timestamp.from(record.occurredAt),
            )
            nextSeqByStation[stationId] = seq + 1
            record
        }
    }

    /** 한 스테이션의 기록을 `seq` 순서로. */
    fun of(stationId: String): List<OcppEventRecord> =
        jdbc.query(
            """
            SELECT station_id, seq, direction, action, message_id, payload, occurred_at
            FROM ocpp_event
            WHERE station_id = ?
            ORDER BY seq
            """.trimIndent(),
            ::map,
            stationId,
        )

    /** 전체 기록. 스테이션별 순번이라 전역 순서는 `occurred_at, station_id, seq` 정렬일 뿐이다. */
    fun all(): List<OcppEventRecord> =
        jdbc.query(
            """
            SELECT station_id, seq, direction, action, message_id, payload, occurred_at
            FROM ocpp_event
            ORDER BY occurred_at, station_id, seq
            """.trimIndent(),
            ::map,
        )

    fun stationIds(): List<String> =
        jdbc.queryForList("SELECT DISTINCT station_id FROM ocpp_event ORDER BY station_id", String::class.java)

    fun size(): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM ocpp_event", Int::class.java) ?: 0

    private fun seedNextSeq(stationId: String): Long =
        (jdbc.queryForObject(
            "SELECT COALESCE(MAX(seq), 0) FROM ocpp_event WHERE station_id = ?",
            Long::class.java,
            stationId,
        ) ?: 0L) + 1L

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int): OcppEventRecord =
        OcppEventRecord(
            seq = rs.getLong("seq"),
            stationId = rs.getString("station_id"),
            direction = MessageDirection.valueOf(rs.getString("direction")),
            action = rs.getString("action"),
            messageId = rs.getString("message_id"),
            payload = rs.getString("payload"),
            occurredAt = rs.getTimestamp("occurred_at").toInstant(),
        )
}
