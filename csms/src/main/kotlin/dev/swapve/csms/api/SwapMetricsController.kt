package dev.swapve.csms.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ★ **성공 기준 S5** — *"교환 성공률·소요시간·실패 사유가 REST 로 조회"*.
 *
 * ### 여기까지다 — 대시보드는 만들지 않는다
 *
 * 대시보드는 범위에서 명시적으로 제외했다. 이 엔드포인트가 JSON 을 내는 것으로 S5 는
 * 충족되고, 그것을 그리는 일은 이 프로젝트의 범위가 아니다. HTML 도 JS 도 차트도 없다.
 *
 * 계산은 전부 [SwapMetricsService] 가 한다 — **기존 기록에서 파생 계산**하며 지표를 위한
 * 저장소를 새로 만들지 않는다. 그 근거는 그쪽 KDoc 에 있다.
 *
 * ### 경로가 `/api/swaps/metrics` 가 아닌 이유
 *
 * `GET /api/swaps/{id}` 와 같은 자리에 두면 `metrics` 가 교환 식별자처럼 보인다. 지금은
 * 식별자 문법(`{stationId}:{requestId}`)이 달라 실제로 충돌하지 않지만, **읽는 사람이 규칙을
 * 알아야만 안 헷갈리는 배치**는 그 자체가 결함이다.
 */
@RestController
class SwapMetricsController(private val metrics: SwapMetricsService) {

    @GetMapping("/api/metrics/swaps")
    fun swapMetrics(): SwapMetrics = metrics.collect()
}
