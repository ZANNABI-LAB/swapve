package dev.swapve.station

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.ocpp.json.OcppDateTime
import dev.swapve.ocpp.swap.AvailabilityState
import dev.swapve.ocpp.swap.BatterySwapWire
import java.time.Instant

/**
 * 스테이션이 보내는 페이로드를 만든다.
 *
 * ### 문자열 상수를 여기서 짓지 않는다
 *
 * `trigger`·`component.name`·`variable.name`·`triggerReason` 같은 값은 전부
 * [BatterySwapWire] 에서 온다. Part 6 Tool validation 이 검사하는 바로 그 값들이라,
 * 시뮬레이터와 CSMS 가 각자 리터럴로 적으면 시험이 계약이 아니라 리터럴을 대조하게 된다
 * (PLAN §7.2).
 *
 * 슬롯 가용성만은 [AvailabilityState] 를 지난다 — 값이 아니라 **반전된 의미**를 다루기
 * 때문이다 (PLAN §4.2). 이 파일 어디에도 `"Occupied"` 라는 리터럴은 없다.
 */
internal object SimPayloads {

    fun bootNotification(config: StationSimConfig): ObjectNode = node().apply {
        put("reason", config.bootReason)
        putObject("chargingStation").apply {
            put("vendorName", config.vendorName)
            put("model", config.model)
            config.serialNumber?.let { put("serialNumber", it) }
            config.firmwareVersion?.let { put("firmwareVersion", it) }
        }
    }

    fun authorize(idToken: String, type: String): ObjectNode = node().apply {
        putObject("idToken").apply {
            put("idToken", idToken)
            put("type", type)
        }
    }

    /**
     * 슬롯 점유 상태 보고 (S03.FR.02/04).
     *
     * `StatusNotificationRequest` 가 아니라 `NotifyEventRequest` 를 쓴다 — 전자는 2.1 에서
     * **deprecated** 다 (Part 2 S03 Remark, PLAN §4.5).
     *
     * @param holdsBattery 슬롯에 배터리가 있는가. **있으면 `Occupied` 가 나간다** — 직관과
     *   반대이므로 변환을 [AvailabilityState] 에 맡긴다.
     */
    fun slotStatus(
        eventId: Int,
        seqNo: Int,
        slotId: Int,
        connectorId: Int,
        holdsBattery: Boolean,
        at: Instant,
    ): ObjectNode = node().apply {
        val timestamp = OcppDateTime.format(at)
        put("generatedAt", timestamp)
        put("seqNo", seqNo)
        put("tbc", false)
        putArray("eventData").addObject().apply {
            put("eventId", eventId)
            put("timestamp", timestamp)
            put("trigger", BatterySwapWire.TRIGGER_DELTA)
            put("actualValue", AvailabilityState.wireOf(holdsBattery))
            put("eventNotificationType", BatterySwapWire.NOTIFICATION_HARD_WIRED)
            putObject("component").apply {
                put("name", BatterySwapWire.COMPONENT_CONNECTOR)
                putObject("evse").apply {
                    put("id", slotId)
                    put("connectorId", connectorId)
                }
            }
            putObject("variable").put("name", BatterySwapWire.VARIABLE_AVAILABILITY_STATE)
        }
    }

    /** 부팅 보안 이벤트 (Part 6 `BootedBatterySwapping` 5·6단계). */
    fun securityEvent(type: String, at: Instant): ObjectNode = node().apply {
        put("type", type)
        put("timestamp", OcppDateTime.format(at))
    }

    /**
     * 충전 트랜잭션 사건 (S04). **교환 트랜잭션과 별개다** (PLAN §5.1).
     *
     * @param chargingState 상태가 바뀌었을 때만 싣는다. 종료 사건에서는 대신
     *   [stoppedReason] 을 싣는다.
     * @param idToken `null` 이면 인가 없는 트랜잭션으로 보고한다 — `type = NoAuthorization`,
     *   `idToken = ""` (Part 6 Tool validation).
     */
    fun transactionEvent(
        eventType: String,
        triggerReason: String,
        seqNo: Int,
        transactionId: String,
        slotId: Int,
        connectorId: Int,
        at: Instant,
        chargingState: String? = null,
        stoppedReason: String? = null,
        idToken: String? = null,
    ): ObjectNode = node().apply {
        put("eventType", eventType)
        put("timestamp", OcppDateTime.format(at))
        put("triggerReason", triggerReason)
        put("seqNo", seqNo)
        putObject("transactionInfo").apply {
            put("transactionId", transactionId)
            chargingState?.let { put("chargingState", it) }
            stoppedReason?.let { put("stoppedReason", it) }
        }
        putObject("evse").apply {
            // Part 6 Tool validation: evse 와 evse.connectorId 가 **둘 다** 있어야 한다.
            put("id", slotId)
            put("connectorId", connectorId)
        }
        putObject("idToken").apply {
            put("idToken", idToken ?: "")
            put(
                "type",
                if (idToken == null) {
                    BatterySwapWire.ID_TOKEN_TYPE_NO_AUTHORIZATION
                } else {
                    BatterySwapWire.ID_TOKEN_TYPE_CENTRAL
                },
            )
        }
    }

    /**
     * 교환 사건 (PLAN §4.3).
     *
     * [batteries] 는 **투입/반출된 배터리마다** 한 항목이고, 항목마다 `evseId`·
     * `serialNumber`·`soC`·`soH` 네 필드가 전부 있어야 한다 (Part 6 Tool validation).
     *
     * [requestId] 는 입고와 출고가 **같은 값을 쓴다(SHALL)** (S02.FR.02).
     */
    fun batterySwap(
        eventType: String,
        requestId: Int,
        idToken: String,
        idTokenType: String,
        batteries: List<Pair<Int, SimBattery>>,
    ): ObjectNode = node().apply {
        put("eventType", eventType)
        put("requestId", requestId)
        putObject("idToken").apply {
            put("idToken", idToken)
            put("type", idTokenType)
        }
        val array = putArray("batteryData")
        batteries.forEach { (slotId, battery) ->
            array.addObject().apply {
                put("evseId", slotId)
                put("serialNumber", battery.serialNumber)
                put("soC", battery.soC)
                put("soH", battery.soH)
            }
        }
    }

    private fun node(): ObjectNode = JsonNodeFactory.instance.objectNode()
}
