package dev.swapve.csms.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 앞으로 **밀 수 있는** 시계.
 *
 * [FixedClockConfig] 는 시각을 한 점에 고정한다. 그것으로 충분한 시험이 대부분이지만,
 * **소요시간 지표**(S5)는 고정 시계에서 언제나 0 이 나와 아무것도 증명하지 못한다.
 * 그렇다고 실제로 기다리면 시험이 느려지고 결정성을 잃는다.
 *
 * 그래서 시각을 **시험이 직접 정한다.** `Thread.sleep` 없이 "교환에 90초가 걸렸다"를 만들 수 있고,
 * 단언은 정확한 값으로 쓸 수 있다.
 */
class MutableClock(
    var now: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now

    /** 시각을 앞으로 민다. */
    fun advance(millis: Long) {
        now = now.plusMillis(millis)
    }
}

/**
 * 밀 수 있는 시계를 CSMS 전체에 끼운다.
 *
 * `ocpp-core` 가 벽시계를 직접 조회하지 않고 `Clock` 을 주입받는 덕에 (그 모듈의 빌드 검사가
 * 그것을 금지한다) **빈 하나를 바꾸는 것으로** 교환 시각 전체가 시험의 통제 아래 들어온다.
 *
 * 이 설정을 `@Import` 하는 시험 클래스는 [FixedClockConfig] 를 쓰는 클래스들과 **다른 Spring
 * 컨텍스트**를 받는다. 지표 시험에는 그 격리가 필요하다 — 인메모리 보관소가 다른 시험이 남긴
 * 교환으로 오염되면 "성공률이 정확히 2/6" 같은 단언을 할 수 없다.
 */
@TestConfiguration
class MutableClockConfig {

    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock(FixedClockConfig.FIXED_NOW)
}
