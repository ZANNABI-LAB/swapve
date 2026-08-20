package dev.swapve.ocpp.schema

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import dev.swapve.ocpp.rpc.MessageType
import dev.swapve.ocpp.rpc.OcppFrame
import dev.swapve.ocpp.rpc.OcppFrameCodec
import dev.swapve.ocpp.rpc.RpcErrorCode
import java.util.concurrent.ConcurrentHashMap

/**
 * 공식 OCPP 2.1 JSON Schema 로 페이로드를 검증하고, 실패 시 회신할 RPC 오류 코드를 정한다
 * (Part 4 §4.1.6, §4.2.4, §4.3).
 *
 * 스키마는 [OcppSchemas] 를 통해 클래스패스 원문에서만 읽는다 — 코드로 옮겨 적지 않는다
 * (설계원칙 2, CC BY-ND 4.0). 컴파일된 [JsonSchema] 는 이름별로 캐시하므로
 * 스키마 파싱은 이름당 한 번뿐이다.
 *
 * I/O 도 세션 상태도 없다. **CALLERROR 를 실제로 보내지 않는다** — 판정과 정책까지만 하고
 * 송신은 세션 계층(M4)의 몫이다 (설계원칙 1).
 *
 * 스레드 안전하다. 인스턴스 하나를 공유해 쓰는 것을 전제로 만들었다.
 */
class OcppPayloadValidator {

    // 공식 스키마의 $schema 는 전부 draft-06 이다. $ref 도 전부 #/definitions/... 로컬 참조라
    // 외부 스키마 로더가 필요 없다.
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V6)

    private val compiled = ConcurrentHashMap<String, JsonSchema>()
    private val unknownNames = ConcurrentHashMap.newKeySet<String>()

    /**
     * `CALL` 페이로드 검증. [action] 은 접미사가 없는 프레임의 action 이다 (Part 4 §4.1.6).
     *
     * 예: `"BatterySwap"` → `BatterySwapRequest` 스키마
     */
    fun validateCall(action: String, payload: JsonNode): PayloadValidation =
        validateAgainst(OcppSchemaNames.forCall(action), payload)

    /**
     * `CALLRESULT` 페이로드 검증.
     *
     * **CALLRESULT 프레임에는 action 이 없다** (Part 4 §4.1.6). messageId 로만 원래 CALL 과
     * 대응되므로, 호출자(M4 의 pending CALL 테이블)가 그 CALL 의 action 을 [callAction] 으로
     * 넘겨야 한다.
     *
     * 예: `callAction = "RequestBatterySwap"` → `RequestBatterySwapResponse` 스키마
     */
    fun validateCallResult(callAction: String, payload: JsonNode): PayloadValidation =
        validateAgainst(OcppSchemaNames.forCallResult(callAction), payload)

    /** `SEND` 페이로드 검증. action 에 접미사를 붙이지 않는다 (Part 4 §4.2.4). */
    fun validateSend(action: String, payload: JsonNode): PayloadValidation =
        validateAgainst(OcppSchemaNames.forSend(action), payload)

    /**
     * 디코딩된 프레임 검증.
     *
     * [OcppFrame.CallResult] 는 프레임만으로 스키마를 정할 수 없어 [callAction] 이 필요하다.
     * 넘기지 않으면 [IllegalArgumentException] — 호출자의 프로그래밍 오류이지 스테이션의
     * 프로토콜 오류가 아니므로 CALLERROR 로 바꾸지 않는다.
     *
     * `CALLERROR`/`CALLRESULTERROR` 는 [PayloadValidation.NotApplicable].
     */
    fun validate(frame: OcppFrame, callAction: String? = null): PayloadValidation = when (frame) {
        is OcppFrame.Call -> validateCall(frame.action, frame.payload)
        is OcppFrame.Send -> validateSend(frame.action, frame.payload)
        is OcppFrame.CallResult -> {
            require(callAction != null) {
                "CALLRESULT 검증에는 원래 CALL 의 action 이 필요하다 (Part 4 §4.1.6): messageId=${frame.messageId}"
            }
            validateCallResult(callAction, frame.payload)
        }

        is OcppFrame.CallError, is OcppFrame.CallResultError -> PayloadValidation.NotApplicable
    }

    /**
     * 스키마 이름을 직접 지정해 검증한다.
     *
     * 알 수 없는 이름이면 [RpcErrorCode.NotImplemented] — 우리가 모르는 action 이라는 뜻이다
     * (Part 4 §4.3 "Requested Action is not known by receiver").
     *
     * 위반이 여러 개일 때의 대표 선정은 결정적이다. [representativeOf] 참조.
     */
    fun validateAgainst(schemaName: String, payload: JsonNode): PayloadValidation {
        val schema = schemaOf(schemaName)
            ?: return PayloadValidation.Invalid(
                schemaName = schemaName,
                errorCode = RpcErrorCode.NotImplemented,
                errorDescription = truncate("unknown action: no schema named $schemaName"),
                violations = emptyList(),
            )

        val messages = schema.validate(payload)
        if (messages.isEmpty()) return PayloadValidation.Valid

        val violations = messages.map { it.toViolation(schemaName) }
        val representative = representativeOf(violations)

        return PayloadValidation.Invalid(
            schemaName = schemaName,
            errorCode = representative.errorCode,
            errorDescription = truncate("$schemaName ${representative.instancePath}: ${representative.message}"),
            violations = violations.sortedWith(VIOLATION_ORDER),
        )
    }

    /** 스키마 이름이 클래스패스에 있으면 컴파일해 캐시하고, 없으면 `null`. */
    private fun schemaOf(name: String): JsonSchema? {
        if (name in unknownNames) return null
        compiled[name]?.let { return it }
        if (!OcppSchemas.contains(name)) {
            // 모르는 이름도 기억해 둔다. 같은 오타 action 이 반복돼도 클래스패스를 다시 뒤지지 않는다.
            unknownNames += name
            return null
        }
        return compiled.computeIfAbsent(name) { factory.getSchema(OcppSchemas.read(it)) }
    }

    private fun ValidationMessage.toViolation(schemaName: String) = SchemaViolation(
        schemaName = schemaName,
        instancePath = instanceLocation.toString(),
        keyword = keywordOf(evaluationPath.toString()),
        message = message,
    )

    companion object {

        /**
         * 대표 위반 선정 — **같은 입력이면 늘 같은 결과**여야 한다. 검증기가 위반을 어떤 순서로
         * 돌려주든(집합이다) 회신하는 CALLERROR 가 흔들리면 안 되기 때문이다.
         *
         * 다음 순서로 정렬해 첫 번째를 고른다:
         * 1. **심각도** — `Occurrence` → `Type` → `Property` → `Format`.
         *    구조가 성립해야 타입 검사가, 타입이 맞아야 값 검사가 의미를 가진다.
         * 2. **instancePath 사전순** — 같은 심각도면 문서 앞쪽(얕고 이른 필드)이 원인에 가깝다.
         * 3. **keyword 사전순**
         * 4. **message 사전순** — 위 셋이 모두 같아도 하나로 확정되도록 남기는 최종 기준.
         */
        fun representativeOf(violations: List<SchemaViolation>): SchemaViolation =
            violations.minWithOrNull(VIOLATION_ORDER)
                ?: error("위반이 없는데 대표를 고를 수 없다")

        private val VIOLATION_ORDER: Comparator<SchemaViolation> =
            compareBy({ SchemaViolation.severityOf(it.errorCode) }, { it.instancePath }, { it.keyword }, { it.message })

        /**
         * 검증기의 evaluationPath 끝 토큰이 위반한 키워드다.
         *
         * 예: `$.properties.batteryData.items.$ref.properties.soC.type` → `type`,
         * `$.required` → `required`
         */
        private fun keywordOf(evaluationPath: String): String =
            evaluationPath.substringAfterLast('.').ifBlank { evaluationPath }

        /**
         * `errorDescription` 은 최대 255자다 (Part 4 §4.2.3 Table 7).
         *
         * 넘치면 잘렸다는 사실이 보이도록 말줄임을 붙이되, 붙인 뒤에도 상한을 넘지 않게 한다.
         */
        internal fun truncate(description: String): String {
            val limit = OcppFrameCodec.MAX_ERROR_DESCRIPTION_LENGTH
            if (description.length <= limit) return description
            return description.take(limit - ELLIPSIS.length) + ELLIPSIS
        }

        private const val ELLIPSIS = "..."
    }
}

/** [MessageType] 만 아는 호출자를 위한 편의 — 검증 대상이 아닌 타입이면 `null`. */
fun OcppPayloadValidator.validateByType(
    type: MessageType,
    action: String,
    payload: JsonNode,
): PayloadValidation {
    val schemaName = OcppSchemaNames.forMessage(type, action) ?: return PayloadValidation.NotApplicable
    return validateAgainst(schemaName, payload)
}
