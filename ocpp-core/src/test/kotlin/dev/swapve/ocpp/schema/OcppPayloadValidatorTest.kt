package dev.swapve.ocpp.schema

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.ocpp.rpc.MessageType
import dev.swapve.ocpp.rpc.OcppFrame
import dev.swapve.ocpp.rpc.OcppFrameCodec
import dev.swapve.ocpp.rpc.RpcErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M2 — 공식 스키마 검증과 CALLERROR 코드 결정 정책 (Part 4 §4.1.6, §4.2.4, §4.3).
 *
 * PLAN §7.3 L1 게이트의 "181개 공식 스키마 검증 통과" 항목에 해당한다.
 */
class OcppPayloadValidatorTest {

    private val mapper = ObjectMapper()
    private val validator = OcppPayloadValidator()

    private fun obj(json: String) = mapper.readTree(json) as ObjectNode

    /** `batteryData[i]` 를 손보기 위한 접근자 */
    private fun ObjectNode.battery(index: Int) = get("batteryData").get(index) as ObjectNode

    private fun invalid(validation: PayloadValidation): PayloadValidation.Invalid {
        assertIs<PayloadValidation.Invalid>(validation, "Invalid 로 판정됐어야 한다: $validation")
        return validation
    }

    /** PLAN §7.1 TC_S_103_CSMS step 11 — 배터리 2개 세트가 공식 테스트다. */
    private val batteryInPayload = obj(
        """
        {
          "eventType": "BatteryIn",
          "requestId": 1234,
          "idToken": {"idToken": "1234567890", "type": "ISO14443"},
          "batteryData": [
            {"evseId": 1, "serialNumber": "1234", "soC": 23, "soH": 85},
            {"evseId": 2, "serialNumber": "5678", "soC": 45, "soH": 87}
          ]
        }
        """.trimIndent()
    )

    // ------------------------------------------------------------ 통과해야 하는 것

    @Test
    fun `TC_S_103_CSMS 의 배터리 2개 BatterySwap 페이로드가 통과한다`() {
        assertEquals(PayloadValidation.Valid, validator.validateCall("BatterySwap", batteryInPayload))
    }

    @Test
    fun `TC_S_103_CSMS step 21 의 BatteryOut 페이로드도 통과한다`() {
        // 출고 슬롯(C,D)은 입고 슬롯(A,B)과 다르다 — PLAN §7.1 읽어낼 것 2
        val payload = obj(
            """
            {
              "eventType": "BatteryOut",
              "requestId": 1234,
              "idToken": {"idToken": "1234567890", "type": "ISO14443"},
              "batteryData": [
                {"evseId": 3, "serialNumber": "4321", "soC": 80, "soH": 95},
                {"evseId": 4, "serialNumber": "8765", "soC": 85, "soH": 78}
              ]
            }
            """.trimIndent()
        )

        assertEquals(PayloadValidation.Valid, validator.validateCall("BatterySwap", payload))
    }

    @Test
    fun `RequestBatterySwap 의 요청과 응답이 각자의 스키마로 통과한다`() {
        val request = obj("""{"requestId": 1234, "idToken": {"idToken": "1234567890", "type": "ISO14443"}}""")
        val response = obj("""{"status": "Accepted"}""")

        assertEquals(PayloadValidation.Valid, validator.validateCall("RequestBatterySwap", request))
        assertEquals(PayloadValidation.Valid, validator.validateCallResult("RequestBatterySwap", response))
    }

    @Test
    fun `SEND 는 action 에 접미사를 붙이지 않는다`() {
        // Part 4 §4.2.4 — 애초에 Request/Response 로 끝나지 않는 이름이다
        val payload = obj(
            """{"id": 1, "pending": 0, "basetime": "2026-08-14T00:00:00Z", "data": [{"t": 0.5, "v": "23"}]}"""
        )

        assertEquals("NotifyPeriodicEventStream", OcppSchemaNames.forSend("NotifyPeriodicEventStream"))
        assertEquals(PayloadValidation.Valid, validator.validateSend("NotifyPeriodicEventStream", payload))
    }

    @Test
    fun `공식 스키마 181개가 모두 draft-06 으로 컴파일된다`() {
        // PLAN §7.3 L1 — 하나라도 컴파일되지 않으면 그 action 은 런타임에 NotImplemented 로 오해된다
        OcppSchemas.names.forEach { name ->
            val validation = validator.validateAgainst(name, mapper.createObjectNode())
            val notImplemented = validation is PayloadValidation.Invalid &&
                validation.errorCode == RpcErrorCode.NotImplemented
            assertTrue(!notImplemented, "$name 스키마를 읽지 못했다")
        }
    }

    // ------------------------------------------------------ CALLERROR 코드 결정 (§4.3)

    @Test
    fun `필수 필드 누락은 OccurrenceConstraintViolation 이다`() {
        val payload = batteryInPayload.deepCopy().apply { remove("requestId") }

        val result = invalid(validator.validateCall("BatterySwap", payload))

        assertEquals(RpcErrorCode.OccurrenceConstraintViolation, result.errorCode)
        assertTrue(result.errorDescription.contains("requestId"), result.errorDescription)
    }

    @Test
    fun `배열 최소 개수 위반도 OccurrenceConstraintViolation 이다`() {
        // batteryData 는 minItems 1 — 카디널리티 제약이다
        val payload = batteryInPayload.deepCopy().apply { putArray("batteryData") }

        assertEquals(
            RpcErrorCode.OccurrenceConstraintViolation,
            invalid(validator.validateCall("BatterySwap", payload)).errorCode,
        )
    }

    @Test
    fun `soC 를 문자열로 주면 TypeConstraintViolation 이다`() {
        val payload = batteryInPayload.deepCopy()
        payload.battery(0).put("soC", "23")

        val result = invalid(validator.validateCall("BatterySwap", payload))

        assertEquals(RpcErrorCode.TypeConstraintViolation, result.errorCode)
        assertTrue(result.errorDescription.contains("soC"), result.errorDescription)
    }

    @Test
    fun `enum 밖의 eventType 은 PropertyConstraintViolation 이다`() {
        val payload = batteryInPayload.deepCopy().put("eventType", "BatteryFlip")

        val result = invalid(validator.validateCall("BatterySwap", payload))

        assertEquals(RpcErrorCode.PropertyConstraintViolation, result.errorCode)
    }

    @Test
    fun `길이 제약 위반도 PropertyConstraintViolation 이다`() {
        // serialNumber 는 maxLength 50
        val payload = batteryInPayload.deepCopy()
        payload.battery(0).put("serialNumber", "S".repeat(51))

        assertEquals(
            RpcErrorCode.PropertyConstraintViolation,
            invalid(validator.validateCall("BatterySwap", payload)).errorCode,
        )
    }

    @Test
    fun `그 밖의 구조 위반은 FormatViolation 이다`() {
        // additionalProperties false — 스키마에 없는 필드
        val payload = batteryInPayload.deepCopy().put("swapDirection", "InThenOut")

        assertEquals(
            RpcErrorCode.FormatViolation,
            invalid(validator.validateCall("BatterySwap", payload)).errorCode,
        )
    }

    @Test
    fun `알 수 없는 action 은 NotImplemented 다`() {
        val result = invalid(validator.validateCall("SwapTheBattery", mapper.createObjectNode()))

        assertEquals(RpcErrorCode.NotImplemented, result.errorCode)
        assertEquals("SwapTheBatteryRequest", result.schemaName)
        assertTrue(result.violations.isEmpty(), "검증조차 못 했으므로 위반 목록은 비어 있다")
    }

    // ------------------------------------------------------------------ 결정성

    @Test
    fun `위반이 여러 개면 결정적으로 하나를 고른다`() {
        // 누락(Occurrence) + enum 위반(Property) + 미지 필드(Format) 를 한꺼번에
        val payload = batteryInPayload.deepCopy()
            .apply { remove("requestId") }
            .put("eventType", "BatteryFlip")
            .put("swapDirection", "InThenOut")

        val results = List(20) { invalid(validator.validateCall("BatterySwap", payload)) }

        // 심각도 순위: Occurrence > Type > Property > Format
        assertEquals(RpcErrorCode.OccurrenceConstraintViolation, results.first().errorCode)
        assertTrue(results.all { it.errorCode == results.first().errorCode })
        assertTrue(results.all { it.errorDescription == results.first().errorDescription })
        assertTrue(results.first().violations.size >= 3, "위반 전량이 보존돼야 한다")
    }

    @Test
    fun `errorDescription 은 255자를 넘지 않는다`() {
        // Part 4 §4.2.3 Table 7 — errorDescription: string[255]
        val payload = batteryInPayload.deepCopy().put("x".repeat(400), 1)

        val result = invalid(validator.validateCall("BatterySwap", payload))

        assertTrue(
            result.errorDescription.length <= OcppFrameCodec.MAX_ERROR_DESCRIPTION_LENGTH,
            "길이 ${result.errorDescription.length}: ${result.errorDescription}",
        )
        assertTrue(result.errorDescription.endsWith("..."), "잘렸다는 사실이 보여야 한다")
    }

    // --------------------------------------------------------- 프레임 단위 검증

    @Test
    fun `CALLRESULT 는 원래 CALL 의 action 으로 검증된다`() {
        val payload = obj("""{"status": "Accepted"}""")
        val frame = OcppFrame.CallResult(messageId = "19223201", payload = payload)

        // CALLRESULT 프레임에는 action 이 없다 (Part 4 §4.1.6) — 호출자가 넘겨준다
        assertEquals(PayloadValidation.Valid, validator.validate(frame, callAction = "RequestBatterySwap"))
        assertEquals("RequestBatterySwapResponse", OcppSchemaNames.forCallResult("RequestBatterySwap"))

        // 같은 페이로드를 Request 스키마로 보면 통과하지 못한다 — 접미사를 잘못 붙이면 드러난다
        assertIs<PayloadValidation.Invalid>(validator.validateCall("RequestBatterySwap", payload))
    }

    @Test
    fun `CALLRESULT 를 원래 action 없이 검증하려 하면 거부한다`() {
        val frame = OcppFrame.CallResult(messageId = "19223201", payload = obj("""{"status": "Accepted"}"""))

        assertFailsWith<IllegalArgumentException> { validator.validate(frame) }
    }

    @Test
    fun `CALL 과 SEND 는 프레임에서 바로 검증된다`() {
        val call = OcppFrame.Call("19223201", "BatterySwap", batteryInPayload)

        assertEquals(PayloadValidation.Valid, validator.validate(call))
        assertEquals(
            RpcErrorCode.NotImplemented,
            invalid(validator.validate(OcppFrame.Send("1", "NoSuchStream", mapper.createObjectNode()))).errorCode,
        )
    }

    @Test
    fun `CALLERROR 는 검증 대상이 아니다`() {
        // Part 4 §4.2.3 — 페이로드 스키마가 없다. 실패가 아니라 대상 밖이다
        val callError = OcppFrame.CallError("19223201", "NotSupported", "not supported", mapper.createObjectNode())
        val callResultError =
            OcppFrame.CallResultError("19223201", "FormatViolation", "bad", mapper.createObjectNode())

        assertEquals(PayloadValidation.NotApplicable, validator.validate(callError))
        assertEquals(PayloadValidation.NotApplicable, validator.validate(callResultError))
        assertEquals(null, OcppSchemaNames.forMessage(MessageType.CALL_ERROR, "BatterySwap"))
    }

    @Test
    fun `같은 스키마를 반복 검증해도 결과가 같다`() {
        // 컴파일된 스키마 캐시가 상태를 오염시키지 않는지 — 캐시 적중 경로를 두 번 이상 태운다
        repeat(3) {
            assertEquals(PayloadValidation.Valid, validator.validateCall("BatterySwap", batteryInPayload))
        }
        repeat(3) {
            assertEquals(
                RpcErrorCode.NotImplemented,
                invalid(validator.validateCall("SwapTheBattery", mapper.createObjectNode())).errorCode,
            )
        }
    }
}
