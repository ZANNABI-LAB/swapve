package dev.swapve.swap

/**
 * 인가 토큰 — `(식별자, 종류)` 값 객체.
 *
 * **로컬 사용자 테이블의 FK 로 취급하지 않는다** (PLAN §11.3). 로밍 토큰은 우리 DB 에 없다.
 * FK 로 묶는 순간 "모든 토큰은 우리 사용자"라는 되돌릴 수 없는 전제가 코드에 박힌다.
 *
 * [type] 을 열거형이 아니라 자유 문자열로 두는 이유는 두 가지다.
 * 표준이 종류를 필수 필드로 두고 있으니 값 자체는 그대로 따르되, 프로토콜 열거형을
 * 도메인에 박지 않는다 (PLAN §6 원칙 4). 그리고 표에 없는 값이 와도 버리지 않고 기록한다
 * (PLAN §11.0 — 정보를 버리지 않는다).
 */
data class IdToken(
    val idToken: String,
    val type: String,
) {
    init {
        require(idToken.isNotBlank()) { "idToken 이 비어 있다" }
        require(type.isNotBlank()) { "idToken 의 종류가 비어 있다" }
    }
}
