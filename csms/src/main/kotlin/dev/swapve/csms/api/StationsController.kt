package dev.swapve.csms.api

import dev.swapve.csms.event.JdbcOcppEventLog
import dev.swapve.csms.station.StationRegistry
import dev.swapve.ocpp.session.SessionRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * ★ **운영 화면이 읽는 두 가지** — 지금 붙어 있는 스테이션과, 그들과 오간 프레임.
 *
 * ### 왜 새로 생겼나
 *
 * [SwapMetricsController] 는 *"대시보드는 만들지 않는다"* 고 적어 두었고 그것이 오랫동안
 * 맞는 판단이었다. 지표를 JSON 으로 내는 것으로 성공 기준 S5 는 충족되고, 그리는 일은
 * 범위 밖이었다. **그 결정이 2026-08-31 에 뒤집혔다** — 이 저장소가 내놓는 물건은
 * 라이브러리가 아니라 **시험 도구**이고, 도구는 무엇이 오갔는지 사람이 눈으로 볼 수 있어야
 * 쓸모가 있다. 프레임 원문은 이미 `ocpp_event` 에 다 있었고 꺼내 볼 길만 없었다.
 *
 * ### 이 컨트롤러가 말하지 않는 것
 *
 * [StationSummary.sessionRegistered] 는 **"보낼 수 있다"가 아니다.** [SessionRegistry] 의
 * KDoc 이 경고하는 그대로다 — 마지막 프레임과 세션 해제 사이에 소켓이 죽어도 이 값은 참으로
 * 남는다. 권위 있는 답은 실제로 보내 보고 받는 결과뿐이므로, 화면도 `connected` 가 아니라
 * `registered` 라고 적는다. **모르는 것을 아는 척하지 않는 것**이 이 화면의 규칙이다.
 *
 * ### 건수는 집계 한 번으로 받는다
 *
 * 화면이 5 초마다 이 목록을 다시 묻는다. 스테이션마다 건수를 따로 세면 그때마다 `1 + N` 번
 * 왕복하므로, [JdbcOcppEventLog.countsByStation] 이 `GROUP BY` 한 번으로 끝낸다.
 *
 * ### 목록의 출처가 둘인 이유
 *
 * [StationRegistry] 는 `BootNotification` 을 받은 스테이션만 안다. 그런데 프로세스를 다시
 * 띄우면 그 표는 비고 이벤트 로그만 남는다. 둘의 합집합을 내지 않으면 **재시작 직후 화면이
 * 텅 비어 "아무 일도 없었다"고 거짓말한다.** 등록 정보가 없는 항목은 `registration` 이
 * `null` 이고, 그 사실이 화면에 그대로 보인다.
 */
@RestController
class StationsController(
    private val stations: StationRegistry,
    private val sessions: SessionRegistry,
    private val events: JdbcOcppEventLog,
) {

    @GetMapping("/api/stations")
    fun stations(): List<StationSummary> {
        val registered = stations.all().associateBy { it.stationId.value }
        val live = sessions.registeredStationIds
        val counts = events.countsByStation()

        return (registered.keys + live + counts.keys).sorted().map { stationId ->
            val registration = registered[stationId]
            StationSummary(
                stationId = stationId,
                sessionRegistered = stationId in live,
                messageCount = counts[stationId] ?: 0,
                registration = registration?.let {
                    StationRegistrationView(
                        operatorId = it.operatorId.value,
                        vendorName = it.vendorName,
                        model = it.model,
                        serialNumber = it.serialNumber,
                        firmwareVersion = it.firmwareVersion,
                        bootReason = it.bootReason,
                        authMethod = it.authMethod.name,
                        bootedAt = it.bootedAt,
                    )
                },
            )
        }
    }

    /**
     * 한 스테이션과 오간 프레임의 **꼬리**를, 오래된 것부터.
     *
     * [limit] 의 상한이 있는 것은 이 응답이 프레임 **원문을 그대로** 싣기 때문이다. 한 건이
     * 수 KB 일 수 있어 제한이 없으면 화면 하나가 수십 MB 를 받는다. [total] 을 함께 주므로
     * 화면은 "몇 건 중 몇 건을 보고 있는지" 말할 수 있다.
     */
    @GetMapping("/api/stations/{stationId}/events")
    fun events(
        @PathVariable stationId: String,
        @RequestParam(required = false, defaultValue = "$DEFAULT_LIMIT") limit: Int,
    ): StationEvents {
        val capped = limit.coerceIn(1, MAX_LIMIT)
        return StationEvents(
            stationId = stationId,
            total = events.countOf(stationId),
            limit = capped,
            events = events.recent(stationId, capped).map {
                OcppEventView(
                    seq = it.seq,
                    direction = it.direction.name,
                    action = it.action,
                    messageId = it.messageId,
                    occurredAt = it.occurredAt,
                    payload = it.payload,
                )
            },
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 1000
    }
}

/**
 * 스테이션 한 대의 요약.
 *
 * @param sessionRegistered 세션이 등록돼 있는가. **보낼 수 있다는 뜻이 아니다** — 위 KDoc 참조.
 * @param registration `BootNotification` 을 받은 적이 있으면 그 내용. 프로세스를 다시 띄운
 *   뒤 아직 부팅 통보가 오지 않았으면 `null` 이고, 그 상태가 화면에 그대로 보여야 한다.
 */
data class StationSummary(
    val stationId: String,
    val sessionRegistered: Boolean,
    val messageCount: Int,
    val registration: StationRegistrationView?,
)

/** `BootNotification` 이 알려 온 것. 스테이션이 보낸 필드는 모르는 것까지 버리지 않는다. */
data class StationRegistrationView(
    val operatorId: String,
    val vendorName: String,
    val model: String,
    val serialNumber: String?,
    val firmwareVersion: String?,
    val bootReason: String,
    val authMethod: String,
    val bootedAt: Instant,
)

/**
 * 프레임 꼬리와, 그 꼬리가 전체 중 얼마인지.
 *
 * @param total 이 스테이션에 쌓인 전체 건수. [events] 의 크기와 다를 수 있고, 다르다는 사실을
 *   화면이 말해야 한다 — 200 건만 보여 주면서 "이게 전부"라고 하면 거짓이 된다.
 */
data class StationEvents(
    val stationId: String,
    val total: Int,
    val limit: Int,
    val events: List<OcppEventView>,
)

/**
 * 프레임 한 건.
 *
 * @param payload **원문 그대로**다. 다시 포맷하지 않는다 — 상대가 보낸 바이트를 보려고 여는
 *   화면인데 우리가 손대면 그 목적이 사라진다.
 * @param action SEND/CALLRESULT 처럼 action 이 없는 프레임에서는 `null` 이다.
 */
data class OcppEventView(
    val seq: Long,
    val direction: String,
    val action: String?,
    val messageId: String,
    val occurredAt: Instant,
    val payload: String,
)
