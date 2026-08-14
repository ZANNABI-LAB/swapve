package dev.swapve.ocpp.schema

import dev.swapve.ocpp.rpc.RpcErrorCode

/**
 * 페이로드 스키마 검증 결과.
 *
 * 검증기는 **판정과 정책 결정까지만 한다.** 실제로 CALLERROR 를 회신할지, 무엇을 기록할지는
 * 세션 계층(M4)의 몫이다. 프레이밍 계층의 `DecodeOutcome` 과 별개의 타입인 이유도 같다 —
 * 그쪽은 "프레임으로 읽히는가", 이쪽은 "페이로드가 스키마에 맞는가"라는 다른 질문에 답한다.
 */
sealed interface PayloadValidation {

    /** 공식 스키마를 통과했다. */
    data object Valid : PayloadValidation

    /**
     * 검증 대상이 아니다. `CALLERROR`/`CALLRESULTERROR` 는 페이로드 스키마가 없다 (Part 4 §4.2.3).
     *
     * 실패가 아니다. 호출자는 이 결과를 오류로 취급하면 안 된다.
     */
    data object NotApplicable : PayloadValidation

    /**
     * 스키마를 통과하지 못했다. 호출자는 [errorCode]/[errorDescription] 으로 CALLERROR 를
     * 회신할 수 있다 (Part 4 §4.3).
     *
     * [violations] 는 **발견된 위반 전량**을 보존한다 (PLAN §11.0 — 정보를 버리지 않는다).
     * [errorCode]/[errorDescription] 은 그중 대표 하나로 정해진 회신용 값일 뿐이다.
     * 스키마 자체가 없어 검증조차 못 한 경우(= 모르는 action)에는 [schemaName] 이 요청된
     * 이름이고 [violations] 는 비어 있다.
     */
    data class Invalid(
        val schemaName: String,
        val errorCode: RpcErrorCode,
        val errorDescription: String,
        val violations: List<SchemaViolation>,
    ) : PayloadValidation
}

/**
 * 스키마 위반 하나.
 *
 * @param schemaName 검증에 쓰인 공식 스키마 이름. 예: `BatterySwapRequest`
 * @param instancePath 위반 위치의 JSON 포인터 경로. 예: `$.batteryData[0].soC`
 * @param keyword 위반한 JSON Schema 키워드. 예: `required`, `type`, `enum`
 * @param message 검증기가 낸 사람이 읽을 설명
 */
data class SchemaViolation(
    val schemaName: String,
    val instancePath: String,
    val keyword: String,
    val message: String,
) {
    /** 이 위반 하나만 놓고 봤을 때 회신해야 할 코드 (Part 4 §4.3 Table 9). */
    val errorCode: RpcErrorCode get() = errorCodeOf(keyword)

    companion object {

        /**
         * JSON Schema 키워드 → RPC Framework Error Code (Part 4 §4.3 Table 9).
         *
         * 표의 **의미**에 맞춰 나눈다:
         * - `OccurrenceConstraintViolation` — "문법은 맞지만 카디널리티 제약 위반".
         *   필수 필드 누락과 배열/객체 개수 제약이 여기 든다.
         * - `TypeConstraintViolation` — "데이터 타입 제약 위반". `type` 하나뿐이다.
         * - `PropertyConstraintViolation` — "문법은 맞지만 값이 유효하지 않다".
         *   enum 밖의 값, 길이·범위 초과, pattern 불일치.
         * - `FormatViolation` — 위 어디에도 들지 않는 구조 위반. 미지의 키워드도 여기로 모은다.
         */
        fun errorCodeOf(keyword: String): RpcErrorCode = when (keyword) {
            "required", "dependencies", "dependentRequired",
            "minItems", "maxItems", "minProperties", "maxProperties",
            -> RpcErrorCode.OccurrenceConstraintViolation

            "type" -> RpcErrorCode.TypeConstraintViolation

            "enum", "const", "pattern", "format",
            "minLength", "maxLength",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "uniqueItems", "contains",
            -> RpcErrorCode.PropertyConstraintViolation

            // additionalProperties / additionalItems / oneOf / anyOf / allOf / not / 그 밖
            else -> RpcErrorCode.FormatViolation
        }

        /**
         * 대표 위반을 고를 때 쓰는 심각도 순위. 작을수록 먼저다.
         *
         * 구조가 성립해야 타입 검사가, 타입이 맞아야 값 검사가 의미를 가진다. 그래서
         * `Occurrence` → `Type` → `Property` → `Format` 순으로 본다. 원인에 가장 가까운
         * 위반을 회신하기 위한 순서다.
         */
        internal fun severityOf(code: RpcErrorCode): Int = when (code) {
            RpcErrorCode.OccurrenceConstraintViolation -> 0
            RpcErrorCode.TypeConstraintViolation -> 1
            RpcErrorCode.PropertyConstraintViolation -> 2
            RpcErrorCode.FormatViolation -> 3
            else -> 4
        }
    }
}
