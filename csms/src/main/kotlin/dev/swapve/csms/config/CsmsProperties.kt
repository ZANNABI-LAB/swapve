package dev.swapve.csms.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * CSMS 설정.
 *
 * 포트와 TLS 는 여기 없다 — `server.port` / `server.ssl.*` 로 Spring 이 이미 갖고 있고,
 * 같은 것을 두 곳에 두면 어느 쪽이 이기는지가 새로운 질문이 된다.
 * **wss 종료는 `server.ssl.enabled` 로 켜고 끈다** — 리버스 프록시가 앞단에서 종료해도 무방하다
 *.
 *
 * @param endpointPath WebSocket 엔드포인트 경로. 실제 연결 URL 은 여기에 `/` + 스테이션
 *   식별자가 붙는다 (Part 4 §3.1.1).
 * @param heartbeatInterval `BootNotificationResponse.interval` 로 회신할 하트비트 간격.
 * @param operatorId 스테이션 등록에 남길 소유 사업자. **값이 항상 하나여도 둔다** —
 *   단일 사업자를 전제로 박으면 나중에 로밍에서 전 데이터 마이그레이션이 필요해진다.
 * @param authorizedIdTokens 인가할 토큰 목록. MVP 의 인가 정책은 이 목록 조회가 전부다.
 * @param knownBatterySerials 이 CPO 가 아는 배터리 일련번호 (F3).
 *   **비어 있으면 배터리를 식별하지 않는다** — 빈 목록을 "전부 미등록"으로 읽으면 등록
 *   절차가 없는 지금 모든 교환이 거부된다. 자세한 근거는 `BatteryRegistry` KDoc.
 * @param maxTextMessageSize 텍스트 프레임 버퍼 상한. OCPP 페이로드는 작지만
 *   `GetCompositeSchedule` 류는 커질 수 있어 기본 64 KiB 로 둔다.
 * @param callTimeout CSMS 가 보낸 CALL 의 응답 대기 한도 (Part 4 §4.1.1).
 * @param retention 이벤트 로그 보존·복구 창 정책. 감사 원문 보존과 기동 리플레이 비용은
 *   서로 다른 목적이라 한 값으로 묶지 않는다.
 * @param security WebSocket 보안 프로파일. 기본은 BASIC 이다 — 무인증 서버를 기본으로
 *   띄우면 chargeBoxId 를 아는 누구나 이벤트를 주입할 수 있다.
 * @param api 앱/운영자가 부르는 REST API 보안 설정. OCPP 스테이션 자격증명과 의도적으로
 *   분리한다 — 스테이션 비밀번호가 앱 API 키처럼 재사용되면 권한 경계가 흐려진다.
 */
@ConfigurationProperties(prefix = "csms")
data class CsmsProperties(
    val endpointPath: String = "/ocpp",
    val heartbeatInterval: Duration = Duration.ofSeconds(300),
    val operatorId: String = "swapve",
    val authorizedIdTokens: List<AuthorizedIdToken> = emptyList(),
    val knownBatterySerials: List<String> = emptyList(),
    val maxTextMessageSize: Int = 64 * 1024,
    val callTimeout: Duration = Duration.ofSeconds(30),
    val retention: Retention = Retention(),
    val security: Security = Security(),
    val api: Api = Api(),
) {

    /**
     * 인가 목록의 한 줄 — `(idToken, type)`.
     *
     * **사용자 테이블의 FK 가 아니다**. 로밍 토큰은 애초에 우리 DB 에 없다.
     * 표준이 `IdTokenType` 에 `type` 을 필수로 두고 있으므로 설정도 그대로 둘을 함께 받는다.
     */
    data class AuthorizedIdToken(
        val idToken: String,
        val type: String,
    )

    /**
     * 이벤트 로그 보존 정책.
     *
     * `replayWindow` 는 기동 시간을 제한하는 값이고, `eventLog` 는 감사·분쟁에서 원문을
     * 확인할 수 있는 기간이다. 복구 7일과 감사 30일을 한 값으로 묶으면 부팅 비용을 줄이는
     * 결정이 곧 감사 원문을 버리는 결정이 되어 버린다.
     *
     * `maxEventsPerStation` 은 기간 정리 뒤에도 남은 비정상 잔량을 자르는 안전망이다.
     */
    data class Retention(
        val eventLog: Duration = Duration.ofDays(30),
        val replayWindow: Duration = Duration.ofDays(7),
        val maxEventsPerStation: Int = 100_000,
        val enabled: Boolean = true,
        val sweepInterval: Duration = Duration.ofHours(1),
    )

    /**
     * OCPP-J WebSocket 보안 설정.
     *
     * [profile] 의 기본값은 [SecurityProfile.BASIC] 이다. 개발 편의를 위해 NONE 을 지원하지만,
     * 기본 운영 설정이 무인증이면 실수 한 번으로 인터넷에 열린 CSMS 가 된다.
     */
    data class Security(
        val profile: SecurityProfile = SecurityProfile.BASIC,
        val stations: List<StationCredential> = emptyList(),
    )

    /** 프로파일 1 자격증명. `stationId` 는 URL 경로의 식별자 및 Basic username 과 같아야 한다. */
    data class StationCredential(
        val stationId: String,
        val passwordHash: String,
    )

    data class Api(
        val security: ApiSecurity = ApiSecurity(),
    )

    /**
     * REST API Basic 인증 설정.
     *
     * [users] 는 [Security.stations] 와 별개다. OCPP 경로의 stationId=username 결속을 REST 에
     * 가져오면 스테이션 자격증명이 앱/운영자 API 를 여는 비밀번호가 된다.
     */
    data class ApiSecurity(
        val enabled: Boolean = true,
        val realm: String = "swapve-api",
        val users: List<ApiCredential> = emptyList(),
    )

    data class ApiCredential(
        val username: String,
        val passwordHash: String,
    )

    enum class SecurityProfile {
        NONE,
        BASIC,
    }
}
