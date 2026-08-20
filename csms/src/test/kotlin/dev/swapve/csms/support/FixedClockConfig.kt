package dev.swapve.csms.support

import dev.swapve.ocpp.json.OcppDateTime
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 시각을 고정한다.
 *
 * `ocpp-core` 가 벽시계를 직접 조회하지 않고 `Clock` 을 주입받는 덕에, **빈 하나를 바꾸는
 * 것으로 CSMS 전체가 결정적**이 된다. `BootNotificationResponse.currentTime` 과
 * `HeartbeatResponse.currentTime` 을 문자열 비교로 시험할 수 있는 이유가 그것이다.
 */
@TestConfiguration
class FixedClockConfig {

    @Bean
    @Primary
    fun fixedClock(): Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)

    companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-08-18T09:30:00Z")

        /**
         * [FIXED_NOW] 를 스키마의 `date-time` 형식으로 적은 것.
         *
         * 손으로 적지 않고 `OcppDateTime` 에서 얻는다 — 형식을 두 곳에 적으면 언젠가
         * 한쪽만 달라지고, 그때 시험은 **어긋난 쪽을 초록으로 통과시킨다.**
         */
        val FIXED_NOW_TEXT: String = OcppDateTime.format(FIXED_NOW)
    }
}
