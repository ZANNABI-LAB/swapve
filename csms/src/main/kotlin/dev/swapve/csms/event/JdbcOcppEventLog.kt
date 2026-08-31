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

    /** 한 스테이션의 창 안 기록을 `seq` 순서로. */
    fun of(stationId: String, since: Instant): List<OcppEventRecord> =
        jdbc.query(
            """
            SELECT station_id, seq, direction, action, message_id, payload, occurred_at
            FROM ocpp_event
            WHERE station_id = ? AND occurred_at >= ?
            ORDER BY seq
            """.trimIndent(),
            ::map,
            stationId,
            Timestamp.from(since),
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

    /**
     * 창 안 전체 기록.
     *
     * `occurred_at` 인덱스는 아직 두지 않는다. 이 질의나 보존 정리가 실제 병목으로 측정될 때
     * 추가하면 되고, 지금은 `schema.sql` 한 장의 단순성이 더 값지다.
     */
    fun since(from: Instant): List<OcppEventRecord> =
        jdbc.query(
            """
            SELECT station_id, seq, direction, action, message_id, payload, occurred_at
            FROM ocpp_event
            WHERE occurred_at >= ?
            ORDER BY occurred_at, station_id, seq
            """.trimIndent(),
            ::map,
            Timestamp.from(from),
        )

    fun stationIds(): List<String> =
        jdbc.queryForList("SELECT DISTINCT station_id FROM ocpp_event ORDER BY station_id", String::class.java)

    fun stationIdsSince(from: Instant): List<String> =
        jdbc.queryForList(
            "SELECT DISTINCT station_id FROM ocpp_event WHERE occurred_at >= ? ORDER BY station_id",
            String::class.java,
            Timestamp.from(from),
        )

    /**
     * 한 스테이션의 **가장 최근** 기록 [limit] 개를, 오래된 것부터.
     *
     * [of] 로도 같은 것을 얻을 수 있지만 그쪽은 전량을 읽는다. 화면은 꼬리만 보므로,
     * 스테이션 하나에 십만 건이 쌓인 상태에서 그것을 통째로 실어 나를 이유가 없다.
     * 정렬을 두 번 하는 것은 **DB 는 최신순으로 자르고 화면은 시간순으로 읽기** 때문이다.
     */
    fun recent(stationId: String, limit: Int): List<OcppEventRecord> =
        jdbc.query(
            """
            SELECT station_id, seq, direction, action, message_id, payload, occurred_at
            FROM ocpp_event
            WHERE station_id = ?
            ORDER BY seq DESC
            LIMIT ?
            """.trimIndent(),
            ::map,
            stationId,
            limit,
        ).reversed()

    /**
     * 스테이션별 기록 수를 **한 번에**.
     *
     * 목록 화면은 스테이션마다 건수를 보여 주는데, [countOf] 를 대수만큼 부르면 5 초마다
     * `1 + N` 번 왕복한다. 집계 하나로 끝낼 수 있는 것을 반복하지 않는다.
     */
    fun countsByStation(): Map<String, Int> =
        jdbc.query("SELECT station_id, COUNT(*) AS c FROM ocpp_event GROUP BY station_id") { rs, _ ->
            rs.getString("station_id") to rs.getInt("c")
        }.toMap()

    /** 한 스테이션에 쌓인 기록 수. 화면이 "몇 건 중 몇 건을 보고 있는지" 말할 수 있게 한다. */
    fun countOf(stationId: String): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM ocpp_event WHERE station_id = ?",
            Int::class.java,
            stationId,
        ) ?: 0

    fun size(): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM ocpp_event", Int::class.java) ?: 0

    fun deleteOlderThan(stationId: String, cutoff: Instant): Int =
        jdbc.update(
            "DELETE FROM ocpp_event WHERE station_id = ? AND occurred_at < ?",
            stationId,
            Timestamp.from(cutoff),
        )

    fun trimToMaxPerStation(stationId: String, max: Int): Int {
        val maxSeq = jdbc.queryForObject(
            "SELECT COALESCE(MAX(seq), 0) FROM ocpp_event WHERE station_id = ?",
            Long::class.java,
            stationId,
        ) ?: 0L
        val threshold = if (max <= 0) maxSeq else maxSeq - max
        if (threshold <= 0L) return 0
        return jdbc.update("DELETE FROM ocpp_event WHERE station_id = ? AND seq <= ?", stationId, threshold)
    }

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
