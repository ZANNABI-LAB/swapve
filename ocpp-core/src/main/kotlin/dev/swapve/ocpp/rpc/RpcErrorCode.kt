package dev.swapve.ocpp.rpc

/**
 * RPC Framework Error Codes (Part 4 §4.3, Table 9).
 *
 * `CALLERROR` / `CALLRESULTERROR` 의 `errorCode` 는 이 표의 값이어야 한다(MUST).
 * 다만 **수신 시에는 원문 문자열을 보존**한다 — [OcppFrame.CallError.errorCode] 참조.
 */
enum class RpcErrorCode {
    /** Action 페이로드의 문법이 틀렸다 */
    FormatViolation,

    /** 더 구체적인 코드로 덮이지 않는 그 밖의 오류 */
    GenericError,

    /** 수신측 내부 오류로 Action을 처리하지 못했다 */
    InternalError,

    /**
     * 지원하지 않는 Message Type Number.
     *
     * ⚠️ **errata 2026-06 §4.3에서 deprecated.** 2.1부터 미지의 메시지 타입은 조용히 무시한다.
     * 수신 호환을 위해 열거값은 남기되, **직접 만들어 보내지 않는다.**
     */
    @Deprecated("errata 2026-06 §4.3 — 미지의 메시지 타입은 CALLERROR 없이 무시한다")
    MessageTypeNotSupported,

    /** 수신측이 모르는 Action */
    NotImplemented,

    /** 아는 Action이지만 지원하지 않는다 */
    NotSupported,

    /** 문법은 맞지만 카디널리티 제약 위반 */
    OccurrenceConstraintViolation,

    /** 문법은 맞지만 값이 유효하지 않다 */
    PropertyConstraintViolation,

    /** 페이로드가 PDU 구조를 따르지 않는다 */
    ProtocolError,

    /** RPC 요청 자체가 유효하지 않다. 예: messageId를 읽을 수 없다 */
    RpcFrameworkError,

    /** 처리 중 보안 문제가 발생했다 */
    SecurityError,

    /** 문법은 맞지만 데이터 타입 제약 위반 */
    TypeConstraintViolation,
    ;

    companion object {
        private val byName = entries.associateBy(RpcErrorCode::name)

        /** 표에 없는 문자열이면 `null`. 수신 시 원문을 버리지 않기 위해 예외를 던지지 않는다. */
        fun parse(raw: String): RpcErrorCode? = byName[raw]
    }
}
