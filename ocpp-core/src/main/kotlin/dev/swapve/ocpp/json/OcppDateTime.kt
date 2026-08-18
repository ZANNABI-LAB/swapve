package dev.swapve.ocpp.json

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 공식 스키마의 `"format": "date-time"` 필드를 만드는 유일한 자리.
 *
 * **현재 시각을 조회하지 않는다** — [Instant] 를 받아 문자열로 바꿀 뿐이다. 시각은 언제나
 * 주입받은 `Clock` 에서 온다. 그래야 `BootNotificationResponse.currentTime` 이나
 * `NotifyEventRequest.generatedAt` 이 고정 시계로 결정적으로 시험된다.
 *
 * 밀리초로 절단하는 이유는 나노초 정밀도가 프로토콜에 아무 의미도 없는데다, 스테이션마다
 * 파서의 소수부 처리가 다른 것이 실무에서 흔한 사고이기 때문이다. RFC 3339 로는 양쪽 다
 * 유효하다.
 *
 * ### 왜 csms 가 아니라 여기인가 (M6 에서 옮겼다)
 *
 * M5 까지는 시각 문자열을 만드는 쪽이 CSMS 뿐이었다. M6 부터 시뮬레이터도 `timestamp` ·
 * `generatedAt` 을 만든다. 같은 포맷 규칙을 두 모듈이 각자 적으면 언젠가 한쪽만 정밀도가
 * 달라지고, 그건 상대 파서에서 터진다. 형식은 프로토콜의 성질이므로 프로토콜 모듈이 갖는다.
 */
object OcppDateTime {

    fun format(at: Instant): String = DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.MILLIS))

    /**
     * 받은 `date-time` 문자열을 읽는다. 읽을 수 없으면 `null`.
     *
     * 예외를 던지지 않는다. 스키마가 `format: date-time` 을 통과시켰어도 오프셋 표기나
     * 소수부가 우리 파서와 어긋날 수 있고, 그때 **메시지 전체를 실패로 만들 이유는 없다** —
     * 원문은 이벤트 로그에 그대로 남아 있으므로 (PLAN §11.1) 정보가 사라지지도 않는다.
     * 호출자는 읽지 못한 시각 자리에 자기 시계를 쓰면 된다.
     */
    fun parse(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
}
