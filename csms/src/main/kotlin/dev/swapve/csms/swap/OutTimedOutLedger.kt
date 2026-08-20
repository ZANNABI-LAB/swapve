package dev.swapve.csms.swap

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import dev.swapve.swap.BatteryData
import dev.swapve.swap.IdToken
import dev.swapve.swap.SlotId
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapKey
import dev.swapve.swap.SwapRequestId
import dev.swapve.swap.SwapTransaction
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * 영속된 장부 불균형 한 건.
 *
 * [SwapTransaction.OutTimedOut] 을 그대로 옮긴 것이라 필드가 대응한다. 도메인 타입을 그대로
 * 저장하지 않고 이 타입을 두는 이유는, **읽어 온 것과 메모리에 있는 것을 구분**하기 위해서다 —
 * 재시작 뒤에는 후자가 없다.
 */
data class OutTimedOutRecord(
    val key: SwapKey,
    val idToken: IdToken,
    val orphanBatteriesIn: List<BatteryData>,
    val authorizedAt: Instant,
    val startedAt: Instant,
    val timedOutAt: Instant,
) {
    /** 대응하는 출고가 없는 입고 배터리 수. 항상 양수다. */
    val ledgerImbalance: Int get() = orphanBatteriesIn.size
}

/**
 * `OUT_TIMED_OUT` 장부 — **유일하게 영속되는 상태** (실패 시나리오 F2).
 *
 * ### 왜 이것만 DB 인가
 *
 * 교환 상태도 슬롯 상태도 충전 트랜잭션도 전부 인메모리다. 그것들은 이벤트 로그에서
 * 재구성 가능한 **파생 상태**이기 때문이다. 그런데 `OUT_TIMED_OUT` 은 다르다:
 *
 * > **S03.FR.06** — *"Situation needs to be reported, because CSMS ends up with an
 * > **orphan BatteryIn for which a BatteryOut is missing**."*
 *
 * 이건 조회용 색인이 아니라 **보상해야 할 채무**다. 프로세스가 죽었다고 이용자가 넣은
 * 배터리가 사라지지 않는다. 불변식 가운데 이 한 줄에만 "영속 저장 필요"가
 * 달아 둔 이유이고, §6 기술선택이 H2 를 지목한 이유다.
 *
 * ### 최소로 끝낸다
 *
 * `JdbcTemplate` 하나에 테이블 하나다. JPA 도 마이그레이션 도구도 넣지 않는다 — 이 한 장부에
 * 필요한 최소를 넘는 순간 과설계다. 스키마는 `schema.sql` 한 장이 전부다.
 *
 * ### 멱등하게 쓴다
 *
 * 키가 `(station_id, request_id)` 라서 같은 교환이 두 번 저장되지 않는다. 재접속 재전송으로
 * 같은 `BatteryOutTimeout` 이 다시 와도(F6) 장부가 두 줄이 되지 않는다 — 상태머신이 이미
 * 종단 상태라 무시하지만, **저장 계층도 같은 사실을 독립적으로 보장**한다.
 */
@Component
class OutTimedOutLedger(private val jdbc: JdbcTemplate) {

    private val mapper = ObjectMapper()

    /** 장부 불균형을 남긴다. 같은 교환이 다시 오면 덮어쓴다 (같은 사실이므로 줄이 늘지 않는다). */
    fun save(state: SwapTransaction.OutTimedOut) {
        jdbc.update(
            """
            MERGE INTO swap_out_timed_out (
                station_id, request_id, id_token, id_token_type,
                ledger_imbalance, orphan_batteries,
                authorized_at, started_at, timed_out_at
            ) KEY (station_id, request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            state.key.stationId.value,
            state.key.requestId.value,
            state.idToken.idToken,
            state.idToken.type,
            state.ledgerImbalance,
            encode(state.orphanBatteriesIn),
            Timestamp.from(state.authorizedAt),
            Timestamp.from(state.startedAt),
            Timestamp.from(state.timedOutAt),
        )
    }

    /** 이 교환의 장부 불균형. 없으면 `null`. */
    fun find(key: SwapKey): OutTimedOutRecord? = jdbc.query(
        "SELECT * FROM swap_out_timed_out WHERE station_id = ? AND request_id = ?",
        ::map,
        key.stationId.value,
        key.requestId.value,
    ).firstOrNull()

    /** 남아 있는 장부 불균형 전부. 오래된 것부터. */
    fun all(): List<OutTimedOutRecord> =
        jdbc.query("SELECT * FROM swap_out_timed_out ORDER BY timed_out_at, station_id, request_id", ::map)

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = OutTimedOutRecord(
        key = SwapKey(
            StationId(rs.getString("station_id")),
            SwapRequestId(rs.getInt("request_id")),
        ),
        idToken = IdToken(rs.getString("id_token"), rs.getString("id_token_type")),
        orphanBatteriesIn = decode(rs.getString("orphan_batteries")),
        authorizedAt = rs.getTimestamp("authorized_at").toInstant(),
        startedAt = rs.getTimestamp("started_at").toInstant(),
        timedOutAt = rs.getTimestamp("timed_out_at").toInstant(),
    )

    /**
     * 배터리 세트를 JSON 배열 원문으로.
     *
     * 별도 테이블로 정규화하지 않는다. 이 장부는 조회 대상이 아니라 **보상 대기 목록**이고,
     * 배터리 단위로 질의할 일이 없다. 필요해지면 그때 원문에서 펼치면 된다 — 정보는
     * 버려지지 않았다.
     */
    private fun encode(batteries: List<BatteryData>): String {
        val array = JsonNodeFactory.instance.arrayNode()
        batteries.forEach { battery ->
            array.addObject().apply {
                put("evseId", battery.slotId.value)
                put("serialNumber", battery.serialNumber)
                put("soC", battery.soC)
                put("soH", battery.soH)
            }
        }
        return mapper.writeValueAsString(array)
    }

    private fun decode(json: String): List<BatteryData> = mapper.readTree(json).map { node ->
        BatteryData(
            slotId = SlotId(node.path("evseId").asInt()),
            serialNumber = node.path("serialNumber").asText(),
            soC = node.path("soC").asDouble(),
            soH = node.path("soH").asDouble(),
        )
    }
}
