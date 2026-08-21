package dev.swapve.ocpp.swap

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.ocpp.schema.OcppSchemas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ★ **상수를 공식 스키마에게 물어본다.**
 *
 * `BatterySwapWire` 의 값들은 "표준이 허용하는 것 중 우리가 고른 것"이다. 그런데 그 주장을
 * 확인하는 것이 지금까지 사람뿐이었다 — 시뮬레이터와 CSMS 가 **같은 상수를 보므로, 값이
 * 틀려도 양쪽이 같이 틀리면 종단 시험은 초록이다.** `EnergyLimitReached` 자리를 한때
 * `EVCommunicationLost` 로 추정했던 것이 그렇게 통과하고 있었다.
 *
 * 이 시험은 그 대조를 기계에게 넘긴다. 잡는 것과 못 잡는 것을 분명히 해 둔다:
 *
 * - ✅ **표준에 없는 값**을 쓰고 있으면 잡는다.
 * - ✅ 자유 문자열 자리의 **길이 제한 위반**을 잡는다.
 * - ✅ 표준이 자유 문자열을 나중에 `enum` 으로 좁히면 잡는다.
 * - ❌ **허용된 값 중 잘못 고른 것**은 못 잡는다. 그건 스펙 원문을 읽어야 안다.
 */
class WireContractTest {

    private val mapper = ObjectMapper()

    /** 스키마 원문에서 `definitions.<이름>.enum` 을 읽는다. */
    private fun enumOf(schema: String, definition: String): Set<String> {
        val node = mapper.readTree(OcppSchemas.read(schema))
        val values = node.path("definitions").path(definition).path("enum")
        assertTrue(values.isArray, "$schema 에 $definition 의 enum 정의가 없다")
        return values.map(JsonNode::asText).toSet()
    }

    private fun assertInEnum(value: String, schema: String, definition: String) {
        assertTrue(
            value in enumOf(schema, definition),
            "'$value' 가 $schema 의 $definition 에 없다 — 표준이 허용하지 않는 값이다",
        )
    }

    /** 자유 문자열 자리의 `maxLength`. enum 이 아니라 길이만 제약받는 필드에 쓴다. */
    private fun maxLengthOf(schema: String, vararg path: String): Int {
        var node = mapper.readTree(OcppSchemas.read(schema))
        path.forEach { node = node.path(it) }
        val limit = node.path("maxLength")
        assertTrue(limit.isInt, "$schema 의 ${path.joinToString(".")} 에 maxLength 가 없다")
        return limit.asInt()
    }

    // ---------------------------------------------------------------- enum 대조

    @Test
    fun `배터리 교환 사건이 BatterySwapEventEnumType 안에 있다`() {
        listOf(BatterySwapWire.BATTERY_IN, BatterySwapWire.BATTERY_OUT, BatterySwapWire.BATTERY_OUT_TIMEOUT)
            .forEach { assertInEnum(it, "BatterySwapRequest", "BatterySwapEventEnumType") }
    }

    @Test
    fun `NotifyEvent 의 trigger 와 notification 이 표준 값이다`() {
        assertInEnum(BatterySwapWire.TRIGGER_DELTA, "NotifyEventRequest", "EventTriggerEnumType")
        assertInEnum(BatterySwapWire.NOTIFICATION_HARD_WIRED, "NotifyEventRequest", "EventNotificationEnumType")
    }

    @Test
    fun `부팅 사유가 BootReasonEnumType 안에 있다`() {
        listOf(BatterySwapWire.BOOT_REASON_POWER_UP, BatterySwapWire.BOOT_REASON_LOCAL_RESET)
            .forEach { assertInEnum(it, "BootNotificationRequest", "BootReasonEnumType") }
    }

    @Test
    fun `트랜잭션 사건 종류가 표준 값이다`() {
        listOf(BatterySwapWire.TX_STARTED, BatterySwapWire.TX_UPDATED, BatterySwapWire.TX_ENDED)
            .forEach { assertInEnum(it, "TransactionEventRequest", "TransactionEventEnumType") }
    }

    /**
     * `triggerReason` 은 이 시험이 가장 필요한 자리다. 종료 사건이 요구하는 세 값
     * (`triggerReason`·`stoppedReason`·`chargingState`)이 **각각 다른 enum** 이라,
     * 한 자리에 다른 자리의 값을 넣어도 전부 유효해 보인다.
     */
    @Test
    fun `triggerReason 이 TriggerReasonEnumType 안에 있다`() {
        listOf(
            BatterySwapWire.TRIGGER_REASON_CABLE_PLUGGED_IN,
            BatterySwapWire.TRIGGER_REASON_CHARGING_STATE_CHANGED,
            BatterySwapWire.TRIGGER_REASON_ENERGY_LIMIT_REACHED,
            BatterySwapWire.TRIGGER_REASON_METER_VALUE_PERIODIC,
        ).forEach { assertInEnum(it, "TransactionEventRequest", "TriggerReasonEnumType") }
    }

    @Test
    fun `chargingState 가 ChargingStateEnumType 안에 있다`() {
        listOf(
            BatterySwapWire.CHARGING_STATE_EV_CONNECTED,
            BatterySwapWire.CHARGING_STATE_CHARGING,
            BatterySwapWire.CHARGING_STATE_SUSPENDED_EVSE,
            BatterySwapWire.CHARGING_STATE_IDLE,
        ).forEach { assertInEnum(it, "TransactionEventRequest", "ChargingStateEnumType") }
    }

    @Test
    fun `종료 사유가 ReasonEnumType 안에 있다`() {
        assertInEnum(BatterySwapWire.STOPPED_REASON_EV_DISCONNECTED, "TransactionEventRequest", "ReasonEnumType")
    }

    @Test
    fun `계량 값의 measurand 와 readingContext 가 표준 값이다`() {
        assertInEnum(BatterySwapWire.MEASURAND_SOC, "TransactionEventRequest", "MeasurandEnumType")
        assertInEnum(BatterySwapWire.READING_CONTEXT_SAMPLE_PERIODIC, "TransactionEventRequest", "ReadingContextEnumType")
    }

    @Test
    fun `응답 상태값이 각자의 enum 안에 있다`() {
        assertInEnum(BatterySwapWire.REGISTRATION_ACCEPTED, "BootNotificationResponse", "RegistrationStatusEnumType")
        assertInEnum(BatterySwapWire.AUTHORIZATION_ACCEPTED, "AuthorizeResponse", "AuthorizationStatusEnumType")
        assertInEnum(BatterySwapWire.GENERIC_ACCEPTED, "RequestBatterySwapResponse", "GenericStatusEnumType")
        assertInEnum(BatterySwapWire.GENERIC_REJECTED, "RequestBatterySwapResponse", "GenericStatusEnumType")
    }

    @Test
    fun `디바이스 모델 보고의 값들이 표준 값이다`() {
        assertInEnum(BatterySwapWire.REPORT_BASE_FULL_INVENTORY, "GetBaseReportRequest", "ReportBaseEnumType")
        assertInEnum(BatterySwapWire.DEVICE_MODEL_ACCEPTED, "GetBaseReportResponse", "GenericDeviceModelStatusEnumType")
        assertInEnum(BatterySwapWire.DEVICE_MODEL_NOT_SUPPORTED, "GetBaseReportResponse", "GenericDeviceModelStatusEnumType")
        assertInEnum(BatterySwapWire.ATTRIBUTE_ACTUAL, "NotifyReportRequest", "AttributeEnumType")
        assertInEnum(BatterySwapWire.MUTABILITY_READ_ONLY, "NotifyReportRequest", "MutabilityEnumType")
        assertInEnum(BatterySwapWire.MUTABILITY_READ_WRITE, "NotifyReportRequest", "MutabilityEnumType")
        listOf(
            BatterySwapWire.DATA_TYPE_INTEGER, BatterySwapWire.DATA_TYPE_DECIMAL,
            BatterySwapWire.DATA_TYPE_STRING, BatterySwapWire.DATA_TYPE_BOOLEAN,
            BatterySwapWire.DATA_TYPE_OPTION_LIST,
        ).forEach { assertInEnum(it, "NotifyReportRequest", "DataEnumType") }
    }

    // ---------------------------------------------------------------- action 이름

    /**
     * action 은 enum 이 아니라 **스키마 파일 이름**으로 확인한다 (Part 4 §4.1.6).
     * 오타가 있으면 그 action 은 영영 `NotImplemented` 로 거절된다.
     */
    @Test
    fun `모든 action 에 요청과 응답 스키마가 있다`() {
        listOf(
            BatterySwapWire.BOOT_NOTIFICATION, BatterySwapWire.HEARTBEAT, BatterySwapWire.AUTHORIZE,
            BatterySwapWire.NOTIFY_EVENT, BatterySwapWire.SECURITY_EVENT_NOTIFICATION,
            BatterySwapWire.TRANSACTION_EVENT, BatterySwapWire.BATTERY_SWAP,
            BatterySwapWire.REQUEST_BATTERY_SWAP, BatterySwapWire.GET_VARIABLES,
            BatterySwapWire.SET_VARIABLES, BatterySwapWire.GET_BASE_REPORT, BatterySwapWire.NOTIFY_REPORT,
        ).forEach { action ->
            assertTrue(OcppSchemas.contains("${action}Request"), "${action}Request 스키마가 없다")
            assertTrue(OcppSchemas.contains("${action}Response"), "${action}Response 스키마가 없다")
        }
    }

    // ---------------------------------------------------------------- 자유 문자열 자리

    /**
     * ⚠️ 여기 있는 값들은 **스키마가 enum 으로 좁히지 않는 자리**다. 오타가 스키마 검증을
     * 그대로 통과하므로 상수로 모은 것이고, 그렇다면 최소한 길이는 확인해야 한다.
     */
    @Test
    fun `자유 문자열 값이 길이 제한 안에 있다`() {
        fun assertFits(value: String, limit: Int, where: String) =
            assertTrue(value.length <= limit, "'$value' 가 $where 의 $limit 자를 넘는다 (${value.length}자)")

        assertFits(
            BatterySwapWire.UNIT_PERCENT,
            maxLengthOf("TransactionEventRequest", "definitions", "UnitOfMeasureType", "properties", "unit"),
            "unitOfMeasure.unit",
        )
        assertFits(
            BatterySwapWire.UNIT_SECONDS,
            maxLengthOf("NotifyReportRequest", "definitions", "VariableCharacteristicsType", "properties", "unit"),
            "VariableCharacteristics.unit",
        )
        listOf(BatterySwapWire.ID_TOKEN_TYPE_CENTRAL, BatterySwapWire.ID_TOKEN_TYPE_NO_AUTHORIZATION).forEach {
            assertFits(it, maxLengthOf("AuthorizeRequest", "definitions", "IdTokenType", "properties", "type"), "idToken.type")
        }
        listOf(BatterySwapWire.SECURITY_EVENT_STARTUP, BatterySwapWire.SECURITY_EVENT_RESET_OR_REBOOT).forEach {
            assertFits(it, maxLengthOf("SecurityEventNotificationRequest", "properties", "type"), "securityEvent.type")
        }
        assertFits(
            BatterySwapWire.COMPONENT_CONNECTOR,
            maxLengthOf("NotifyEventRequest", "definitions", "ComponentType", "properties", "name"),
            "component.name",
        )
        assertFits(
            BatterySwapWire.VARIABLE_AVAILABILITY_STATE,
            maxLengthOf("GetVariablesRequest", "definitions", "VariableType", "properties", "name"),
            "variable.name",
        )
    }

    /**
     * ★ 거부 사유는 `maxLength` 여유가 두 글자뿐이다 — `NoBatteryAvailable` 이 18자이고
     * 한도가 20자다. 표준이 새 사유를 추가하면 이 시험이 먼저 말해 준다.
     */
    @Test
    fun `거부 사유가 reasonCode 길이 제한 안에 있다`() {
        val limit = maxLengthOf("RequestBatterySwapResponse", "definitions", "StatusInfoType", "properties", "reasonCode")
        BatteryRejectionReason.entries.forEach {
            assertTrue(
                it.wireValue.length <= limit,
                "'${it.wireValue}' 가 reasonCode 의 $limit 자를 넘는다 (${it.wireValue.length}자)",
            )
        }
    }

    @Test
    fun `슬롯 가용성 값이 변수 값 길이 제한 안에 있다`() {
        val limit = maxLengthOf("NotifyEventRequest", "definitions", "EventDataType", "properties", "actualValue")
        AvailabilityState.entries.forEach {
            assertTrue(it.wireValue.length <= limit, "'${it.wireValue}' 가 actualValue 의 $limit 자를 넘는다")
        }
    }

    /**
     * ★ **스키마가 제약하지 않는 상수에는 ⚠️ 표시가 붙어 있어야 한다.**
     *
     * 이 파일의 나머지 시험이 지키는 것은 "표준에 있는 값인가"뿐이다. 스키마가 `enum` 으로
     * 좁히지 않는 자리는 그 검사가 성립하지 않고 **사람이 스펙을 읽어야만** 옳은지 알 수
     * 있다. 소비자가 그 둘을 구분하려면 표시가 사실과 맞아야 한다.
     *
     * 산문으로 적어 두면 지켜졌는지 알 수 없으므로 소스를 직접 읽어 대조한다. 상수를 새로
     * 넣는 사람은 그것이 어느 부류인지 판정하게 된다.
     */
    @Test
    fun `제약 없는 상수와 표시가 일치한다`() {
        val source = java.io.File("src/main/kotlin/dev/swapve/ocpp/swap/BatterySwapWire.kt")
        assertTrue(source.isFile, "원본을 찾지 못했다: ${source.absolutePath}")
        val text = source.readText()

        val marked = Regex("""/\*\*(?:(?!\*/)[\s\S])*?⚠️(?:(?!\*/)[\s\S])*?\*/\s*\n\s*const val (\w+)""")
            .findAll(text).map { it.groupValues[1] }.toSet()

        val unconstrained = Regex("""const val (\w+) = "([^"]*)"""").findAll(text)
            .filterNot { OcppSchemas.contains(it.groupValues[2] + "Request") }
            .filterNot { isInAnyEnum(it.groupValues[2]) }
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            unconstrained, marked,
            "표시가 사실과 어긋난다. 누락=${unconstrained - marked}, 과잉=${marked - unconstrained}",
        )
    }

    /** 어느 공식 스키마의 enum 에든 들어 있는 값인가. */
    private fun isInAnyEnum(value: String): Boolean = OcppSchemas.names.any { name ->
        mapper.readTree(OcppSchemas.read(name)).path("definitions").any { definition ->
            definition.path("enum").any { it.asText() == value }
        }
    }

    /**
     * 위 자리들이 **여전히 자유 문자열**임을 고정한다.
     *
     * 표준이 이들을 `enum` 으로 좁히는 날, 우리 값이 그 목록에 없을 수 있다. 그때 이 시험이
     * 빨개져서 대조가 필요하다는 사실을 알려 준다 — 조용히 지나가면 런타임에 거절당한다.
     */
    @Test
    fun `자유 문자열 자리가 아직 enum 이 아니다`() {
        fun assertStillFreeString(schema: String, vararg path: String) {
            var node = mapper.readTree(OcppSchemas.read(schema))
            path.forEach { node = node.path(it) }
            assertEquals("string", node.path("type").asText(), "${path.joinToString(".")} 의 타입이 바뀌었다")
            assertTrue(node.path("enum").isMissingNode, "${path.joinToString(".")} 가 이제 enum 이다 — 우리 상수를 대조해야 한다")
        }

        assertStillFreeString("AuthorizeRequest", "definitions", "IdTokenType", "properties", "type")
        assertStillFreeString("SecurityEventNotificationRequest", "properties", "type")
        assertStillFreeString("TransactionEventRequest", "definitions", "UnitOfMeasureType", "properties", "unit")
        assertStillFreeString("NotifyEventRequest", "definitions", "EventDataType", "properties", "actualValue")
    }
}
