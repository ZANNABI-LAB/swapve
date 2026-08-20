package dev.swapve.csms.api

import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/**
 * 소요시간 분포 — **평균만으로는 쓸모가 적다** (S5).
 *
 * 교환 소요시간은 대칭 분포가 아니다. 대부분 수십 초에 끝나고 소수가 이용자 사정으로 길게
 * 끌린다. 그런 분포에서 평균 하나는 "빠른 것도 느린 것도 아닌, 아무도 겪지 않은 값"이
 * 되기 쉽다. 그래서 건수·최소·평균·최대에 더해 **백분위 두 개**를 함께 낸다.
 *
 * ### 백분위는 nearest-rank 다
 *
 * 정렬 후 `ceil(p/100 × n)` 번째 값을 그대로 쓴다. 보간하지 않는 이유는 **실제로 일어난 값
 * 하나를 가리키기 위해서**다 — 보간값은 어느 교환의 소요시간도 아니다. 표본이 적을 때
 * (지금 규모가 그렇다) 그 차이가 특히 크다.
 *
 * 표본이 없으면 건수만 0 이고 나머지는 전부 `null` 이다. **0 으로 채우지 않는다** — "0 밀리초
 * 만에 끝났다"와 "끝난 교환이 없다"는 완전히 다른 사실이고, 지표에서 그 둘을 섞으면 그래프가
 * 조용히 거짓말을 한다.
 */
data class DurationSummary(
    val count: Int,
    val minMillis: Long?,
    val meanMillis: Long?,
    val maxMillis: Long?,
    val p50Millis: Long?,
    val p95Millis: Long?,
) {
    companion object {

        val EMPTY = DurationSummary(0, null, null, null, null, null)

        fun of(samples: List<Duration>): DurationSummary {
            if (samples.isEmpty()) return EMPTY

            val sorted = samples.map { it.toMillis() }.sorted()
            return DurationSummary(
                count = sorted.size,
                minMillis = sorted.first(),
                meanMillis = sorted.sum() / sorted.size,
                maxMillis = sorted.last(),
                p50Millis = percentile(sorted, 50),
                p95Millis = percentile(sorted, 95),
            )
        }

        /** nearest-rank. `sorted` 는 비어 있지 않다. */
        private fun percentile(sorted: List<Long>, percentile: Int): Long {
            val rank = ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

/**
 * 교환 건수 — 성공률의 분자와 분모가 무엇인지 드러낸다.
 *
 * @param attempted **스테이션에 실제로 도달한** 개시. 열린 교환 전부 + 스테이션이 거부한 개시.
 * @param completed 균형 있게 끝난 교환 (`COMPLETED`).
 * @param inProgress 아직 끝나지 않은 교환 (`AUTHORIZED`/`HALF_IN`/`HALF_OUT`).
 * @param failed 실패로 끝난 것 — `OUT_TIMED_OUT` + 스테이션이 거부한 개시.
 * @param blockedStarts ★ **보내지 않은 시도** (S02.FR.03). [attempted] 에 **넣지 않는다** —
 *   `RequestBatterySwap` 이 전선에 나가지도 않은 것을 교환 시도로 세면 성공률이 왜곡된다.
 *   인가 정책이 막은 것은 교환의 실패가 아니라 교환이 시작되지 않은 것이다.
 */
data class SwapCounts(
    val attempted: Int,
    val completed: Int,
    val inProgress: Int,
    val failed: Int,
    val blockedStarts: Int,
)

/**
 * ★ **실패 사유 — "실패 12건"은 정보가 아니다** (S5).
 *
 * F1~F6 과 배터리 거부 `reasonCode` 두 축으로 각각 센다. 한 축으로만 세면
 * *"거부 3건"* 이 배터리 부족인지 미등록 배터리인지 알 수 없다.
 *
 * @param byScenario **실패인 시나리오만** — F1(배터리 부족)·F2(수령 타임아웃)·F3(미등록
 *   배터리)·F5(순서 위반). F4·F6 은 여기 없다 ([SwapIdempotencyMetrics] 참조).
 * @param byReasonCode 부록 `reason_codes.csv` 의 사유별. F1 의
 *   `RequestBatterySwapResponse.statusInfo` 와 F3 의 `customData.statusInfo` 가 **같은 표를
 *   쓰므로 한 map 에 모은다.**
 * @param byAnomalyReason F5 의 세부 — 인가 없음/키 불일치/수량 불일치 …
 * @param rejectedAuthorizations 인가되지 않은 토큰으로 들어온 시도.
 *   **S02 원격 개시가 막힌 것과 S01 로컬 인가가 거부된 것이 섞여 있다** — `AuthorizationRegistry`
 *   가 시도의 출처를 남기지 않기 때문이다. 구분이 필요해지면 고칠 자리는 그 기록이지 여기가
 *   아니다. 지표를 위해 별도 카운터를 두면 진실의 원본이 둘이 된다.
 */
data class SwapFailureMetrics(
    val total: Int,
    val byScenario: Map<String, Int>,
    val byReasonCode: Map<String, Int>,
    val byAnomalyReason: Map<String, Int>,
    val rejectedAuthorizations: Int,
)

/**
 * 멱등 처리 — **실패가 아니다.**
 *
 * F4(중복 `BatteryIn`)와 F6(재접속 재전송)은 정상적으로 일어나는 일이고, **두 번 반영되지
 * 않았다**는 것이 올바른 동작이다. 실패로 세면 성공률이 이유 없이 나빠지고, 아예 안 세면
 * 장부가 왜 안 늘었는지 설명할 수 없다. 그래서 별도 블록이다.
 *
 * ### F4 와 F6 은 **구분된다** — 걸린 층이 다르기 때문이다
 *
 * - [stateMachineIgnores] (F4) — 새 messageId 로 온 중복. 멱등 원장을 지나 **M3 상태머신**이
 *   `(stationId, requestId)` 로 잡았다. `SwapTransactionRegistry` 에 기록이 남는다.
 * - [sessionReplays] (F6) — 같은 messageId 재전송. **M4 멱등 원장**이 상위 계층을 부르지도
 *   않고 저장된 응답을 그대로 냈다. 그래서 상태머신 기록에는 아무것도 없고, 대신
 *   **이벤트 로그에 같은 `(stationId, messageId)` 의 수신 CALL 이 두 번** 남아 있다
 * (파생 상태는 이 로그에서 계산될 수 있어야 한다).
 */
data class SwapIdempotencyMetrics(
    val byScenario: Map<String, Int>,
    val stateMachineIgnores: Int,
    val byIgnoreReason: Map<String, Int>,
    val sessionReplays: Int,
)

/**
 * 영속 장부 — **다른 질문에 답한다.**
 *
 * 위의 모든 수치는 *"이 프로세스가 본 교환"* 에 대한 것이라 재시작하면 0 부터 다시 센다.
 * 이 블록만은 H2 에서 읽으므로 **재시작을 가로질러 남는다** (`OUT_TIMED_OUT` 만
 * 영속). 보상해야 할 채무의 총계이지 이번 관측 구간의 실패 수가 아니다. 두 값이 다르다고
 * 해서 어느 한쪽이 틀린 것이 아니다.
 */
data class SwapLedgerMetrics(
    val openImbalances: Int,
    val orphanBatteries: Int,
)

/**
 * `GET /api/metrics/swaps` — **성공 기준 S5**.
 *
 * > *"교환 성공률·소요시간·실패 사유가 REST 로 조회"*
 *
 * @param successRate `completed / attempted`. 시도가 없으면 `null` 이다 — 0.0 으로 답하면
 *   *"전부 실패했다"* 로 읽힌다.
 */
data class SwapMetrics(
    val generatedAt: Instant,
    val swaps: SwapCounts,
    val successRate: Double?,
    val duration: SwapDurationMetrics,
    val failures: SwapFailureMetrics,
    val idempotency: SwapIdempotencyMetrics,
    val ledger: SwapLedgerMetrics,
)

/**
 * 소요시간 — 완주한 교환과 반쪽으로 끝난 교환을 **섞지 않는다.**
 *
 * `OUT_TIMED_OUT` 의 소요시간은 이용자가 배터리를 꺼내가지 않고 버틴 시간이라 성격이 다르다.
 * 한 분포에 넣으면 완주 교환의 백분위가 그것 때문에 늘어난다.
 */
data class SwapDurationMetrics(
    val completed: DurationSummary,
    val outTimedOut: DurationSummary,
)
