package dev.swapve.csms.ocpp

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 공식 스키마의 `"format": "date-time"` 필드를 만드는 유일한 자리.
 *
 * **현재 시각을 조회하지 않는다** — [Instant] 를 받아 문자열로 바꿀 뿐이다. 시각은 언제나
 * 주입받은 `Clock` 에서 온다. 그래야 `BootNotificationResponse.currentTime` 과
 * `HeartbeatResponse.currentTime` 이 고정 시계로 결정적으로 시험된다.
 *
 * 밀리초로 절단하는 이유는 나노초 정밀도가 프로토콜에 아무 의미도 없는데다, 스테이션마다
 * 파서의 소수부 처리가 다른 것이 실무에서 흔한 사고이기 때문이다. RFC 3339 로는 양쪽 다
 * 유효하다.
 */
object OcppDateTime {

    fun format(at: Instant): String = DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.MILLIS))
}
