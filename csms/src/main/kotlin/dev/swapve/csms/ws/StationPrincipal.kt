package dev.swapve.csms.ws

/**
 * 핸드셰이크에서 **결정된** 스테이션 신원 (PLAN §11.4).
 *
 * ### 왜 `String` 이 아닌가
 *
 * OCPP-J 는 `wss://host/ocpp/<stationId>` 로 붙는다. 경로에서 읽은 문자열을 그대로 신뢰해
 * 온 코드에 흘리면 **"스테이션 신원 = URL 경로"** 라는 전제가 전 핸들러에 박힌다. 그 전제는
 * 되돌리기 어렵다 — 보안 프로파일 2/3 을 붙일 때 손댈 곳이 한 군데가 아니게 된다.
 *
 * 스펙도 같은 것을 권고한다 (Part 4 Edition 2 §3.1.1):
 * *"CSMS 가 연결 URL 에만 의존해 스테이션을 식별하지 말고, 인증 자격증명과 대조해 이중
 * 확인할 것을 권고한다(RECOMMENDED)."*
 *
 * 지금 구현은 경로에서 얻는다. 하지만 **그 사실이 [authMethod] 에 값으로 남으므로**,
 * 나중에 BASIC/CLIENT_CERT 를 끼울 자리는 이 데이터 타입 하나뿐이다.
 *
 * PLAN §11.0 은 구현체 하나짜리 추상화를 금하지만 이건 인터페이스가 아니라 **데이터 타입
 * 하나**다. §11.4 가 명시적으로 요구한 유일한 경첩이고, 비용은 사실상 0 이다.
 *
 * @param stationId 퍼센트 디코딩까지 끝난 identifierString (Part 4 §3.1.1). 최대 48자, 콜론 불가.
 * @param authMethod 이 신원이 **무엇으로** 확인됐는지. 지금은 언제나 [AuthMethod.NONE] 이다.
 */
data class StationPrincipal(
    val stationId: String,
    val authMethod: AuthMethod,
)

/**
 * 신원 확인 수단 (PLAN §11.4 — 보안 프로파일 1/2/3).
 *
 * 인증서 발급·CSR·키 저장소·`SecurityCtrlr` 은 **만들지 않는다** (§11.4 "지금 하지 않을 것").
 * 여기 열거된 것은 훗날 값이 채워질 자리일 뿐 구현이 아니다.
 */
enum class AuthMethod {
    /** 확인하지 않았다. 신원은 연결 URL 경로에서만 왔다. MVP 가 여기 있다. */
    NONE,

    /** HTTP Basic 인증 (프로파일 1/2). 사용자명이 곧 스테이션 식별자다 — 그래서 §3.1.1 이 콜론을 금한다. */
    BASIC,

    /** TLS 클라이언트 인증서 (프로파일 3). */
    CLIENT_CERT,
}
