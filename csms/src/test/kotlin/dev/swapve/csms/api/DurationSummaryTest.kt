package dev.swapve.csms.api

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 소요시간 분포의 순수 계산 (S5).
 *
 * Spring 도 소켓도 없다. 백분위 같은 계산은 종단 시험에서 눈으로 맞히기 어렵고, 틀려도
 * 그럴듯한 값이 나와 조용히 지나간다 — 그래서 여기서 값 하나하나를 못박는다.
 */
class DurationSummaryTest {

    @Test
    fun `표본이 없으면 건수만 0 이고 나머지는 없다`() {
        val summary = DurationSummary.of(emptyList())

        assertEquals(0, summary.count)
        // ★ 0 으로 채우지 않는다. "0 밀리초 만에 끝났다"와 "끝난 교환이 없다"는 다른 사실이다.
        assertNull(summary.meanMillis)
        assertNull(summary.minMillis)
        assertNull(summary.maxMillis)
        assertNull(summary.p50Millis)
        assertNull(summary.p95Millis)
    }

    @Test
    fun `건수·최소·평균·최대를 낸다`() {
        val summary = DurationSummary.of(
            listOf(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(20)),
        )

        assertEquals(3, summary.count)
        assertEquals(10_000, summary.minMillis)
        assertEquals(20_000, summary.meanMillis)
        assertEquals(30_000, summary.maxMillis)
    }

    /**
     * ★ **백분위는 실제로 일어난 값 하나를 가리킨다** (nearest-rank).
     *
     * 1..10 초에서 p50 은 `ceil(0.5 × 10) = 5` 번째, 즉 5 초다. 보간했다면 5.5 초가 나오는데
     * 그건 **어느 교환의 소요시간도 아니다.**
     */
    @Test
    fun `백분위는 보간하지 않고 실제 표본을 고른다`() {
        val summary = DurationSummary.of((1..10).map { Duration.ofSeconds(it.toLong()) })

        assertEquals(5_000, summary.p50Millis)
        // ceil(0.95 × 10) = 10 번째
        assertEquals(10_000, summary.p95Millis)
    }

    /**
     * 평균이 가리지 못하는 꼬리를 백분위가 드러낸다 — 그것이 분포를 함께 내는 이유다.
     *
     * 아홉 건이 10 초, 한 건이 600 초. 평균은 69 초로 **아무도 겪지 않은 값**이지만,
     * p50 은 10 초이고 최대는 600 초라 실제 모양이 읽힌다.
     */
    @Test
    fun `꼬리가 긴 분포에서 평균 하나로는 읽히지 않는다`() {
        val samples = List(9) { Duration.ofSeconds(10) } + Duration.ofSeconds(600)
        val summary = DurationSummary.of(samples)

        assertEquals(69_000, summary.meanMillis)
        assertEquals(10_000, summary.p50Millis)
        assertEquals(600_000, summary.p95Millis)
        assertEquals(600_000, summary.maxMillis)
    }

    @Test
    fun `표본이 하나면 전부 같은 값이다`() {
        val summary = DurationSummary.of(listOf(Duration.ofMillis(1_234)))

        assertEquals(1, summary.count)
        assertEquals(1_234, summary.minMillis)
        assertEquals(1_234, summary.meanMillis)
        assertEquals(1_234, summary.maxMillis)
        assertEquals(1_234, summary.p50Millis)
        assertEquals(1_234, summary.p95Millis)
    }
}
