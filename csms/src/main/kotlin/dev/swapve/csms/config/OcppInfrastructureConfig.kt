package dev.swapve.csms.config

import dev.swapve.csms.event.JdbcOcppEventLog
import dev.swapve.ocpp.rpc.OcppFrameCodec
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.session.InboundCallLedger
import dev.swapve.ocpp.session.LocalStationCommandBus
import dev.swapve.ocpp.session.SessionRegistry
import dev.swapve.ocpp.session.StationCommandBus
import dev.swapve.ocpp.session.StationSerializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock

/**
 * M4 세션 계층의 조립 — **공유되는 것들을 싱글턴으로 한 번만 만든다.**
 *
 * 여기 있는 것은 전부 `ocpp-core` 의 클래스다. 그 모듈은 Spring 을 모르고
 * (PLAN §6 설계원칙 1), Spring 은 그 사실을 모른 채로 조립만 한다. 어느 쪽도 서로의 애노테이션을
 * 필요로 하지 않는다는 것이 요점이다 — 그래서 `ocpp-core` 의 `checkNoFrameworkImports` 가
 * 계속 통과한다.
 */
@Configuration
class OcppInfrastructureConfig {

    /**
     * **현재 시각의 유일한 출처.**
     *
     * `ocpp-core` 는 `Instant.now()` 도 `System.currentTimeMillis()` 도 부르지 않는다 —
     * 그 모듈의 빌드 검사가 그것을 금지한다. 그래서 시각은 전부 이 빈을 타고 흐르고,
     * 테스트는 `Clock.fixed` 하나를 끼워 넣는 것으로 전체를 결정적으로 만든다.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** 상태가 없다. 전 세션이 공유한다. */
    @Bean
    fun ocppFrameCodec(): OcppFrameCodec = OcppFrameCodec()

    /**
     * 공식 스키마 181 개를 이름별로 컴파일해 캐시한다.
     *
     * **인스턴스 하나를 공유하는 것을 전제로 만들어졌다** — 연결마다 새로 만들면 스키마를
     * 연결 수만큼 다시 파싱한다.
     */
    @Bean
    fun ocppPayloadValidator(): OcppPayloadValidator = OcppPayloadValidator()

    /**
     * 수신 CALL 멱등 원장.
     *
     * 키가 `(stationId, messageId)` 라서 **재접속으로 세션이 바뀌어도 이어진다.** 그것이
     * 재전송된 CALL 이 부수효과를 두 번 일으키지 않는 이유다 (PLAN §5.4 F6).
     */
    @Bean
    fun inboundCallLedger(): InboundCallLedger = InboundCallLedger()

    /** per-station 메시지 직렬화. 락 키가 `stationId` 다 — 훗날의 파티셔닝 키와 같다 (PLAN §11.5). */
    @Bean
    fun stationSerializer(): StationSerializer = StationSerializer()

    /**
     * 추가 전용 이벤트 로그 (PLAN §11.1).
     *
     * JDBC 로 영속한다. 파생 상태 복구의 원장은 이 원문 로그다.
     */
    @Bean
    fun ocppEventLog(jdbc: JdbcTemplate): JdbcOcppEventLog = JdbcOcppEventLog(jdbc)

    @Bean
    fun sessionRegistry(): SessionRegistry = SessionRegistry()

    /**
     * 스테이션에 명령을 보내는 **유일한** 통로 (PLAN §11.5).
     *
     * 상위 계층은 세션 객체를 받지 못하고 언제나 `stationId` 로 지시한다. 분산이 필요해지면
     * 이 빈만 바뀐다.
     */
    @Bean
    fun stationCommandBus(sessionRegistry: SessionRegistry): StationCommandBus =
        LocalStationCommandBus(sessionRegistry)
}
