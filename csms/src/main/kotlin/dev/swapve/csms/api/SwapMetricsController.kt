package dev.swapve.csms.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ★ **성공 기준 S5** — *"교환 성공률·소요시간·실패 사유가 REST 로 조회"*.
 *
 * ### 여기는 여전히 JSON 만 낸다 — 다만 "그리지 않는다"는 더 이상 사실이 아니다
 *
 * 오랫동안 이 자리에 *"대시보드는 만들지 않는다. HTML 도 JS 도 차트도 없다"* 고 적혀 있었고,
 * 지표를 JSON 으로 내는 것으로 성공 기준 S5 는 충족되므로 그 판단은 맞았다.
 * **2026-08-31 에 그 결정이 뒤집혔다** — 이 저장소가 내놓는 것은 라이브러리가 아니라
 * **시험 도구**이고, 도구는 무엇이 오갔는지 사람이 눈으로 볼 수 있어야 쓸모가 있다.
 * 운영 화면은 [StationsController] 와 `static/index.html` 이 맡는다.
 *
 * **이 컨트롤러는 그대로다.** 화면이 생겼다고 지표 계산을 화면 쪽으로 옮기지 않는다 —
 * 그러면 REST 로 조회된다는 S5 의 근거가 화면에 딸린 부산물이 된다. 차트도 여전히 없다.
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
