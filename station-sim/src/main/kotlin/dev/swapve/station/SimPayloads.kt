package dev.swapve.station

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.ocpp.json.OcppDateTime
import dev.swapve.ocpp.swap.AvailabilityState
import dev.swapve.ocpp.swap.BatteryRejectionReason
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.ocpp.swap.VariableReading
import dev.swapve.ocpp.swap.VariableRef
import dev.swapve.ocpp.swap.VariableWrite
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

    /**
     * @param reason 부팅 사유. 재부팅 경로(S04.FR.11)는 `PowerUp` 이 아니라 `LocalReset` 으로
     *   온다 — 무엇이 일어났는지를 CSMS 가 구분할 수 있어야 한다.
     */
    fun bootNotification(config: StationSimConfig, reason: String = config.bootReason): ObjectNode = node().apply {
        put("reason", reason)
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
     * @param socPercent 실려 나갈 배터리 충전 상태 (**S04.FR.04** 주기 보고). `null` 이면
     *   `meterValue` 를 아예 싣지 않는다 — 값이 없는데 배열만 붙이면 스키마의 `minItems: 1`
     *   에 걸리고, 애초에 측정하지 않은 것을 측정한 척하는 셈이다.
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
        socPercent: Double? = null,
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
        socPercent?.let { soc ->
            // 계량값의 시각은 사건의 시각과 같다 — 같은 순간에 표본을 떴다.
            putArray("meterValue").addObject().apply {
                put("timestamp", OcppDateTime.format(at))
                putArray("sampledValue").addObject().apply {
                    put("value", soc)
                    put("measurand", BatterySwapWire.MEASURAND_SOC)
                    put("context", BatterySwapWire.READING_CONTEXT_SAMPLE_PERIODIC)
                    putObject("unitOfMeasure").put("unit", BatterySwapWire.UNIT_PERCENT)
                }
            }
        }
    }

    /**
     * `GetVariablesResponse` (S04.FR.12, PLAN §4.5).
     *
     * 항목 하나하나가 **어느 변수에 대한 답인지를 다시 싣는다** — 스키마가
     * `component`/`variable` 을 필수로 두기 때문이다. 그 인코딩은 [VariableRef.writeTo] 한
     * 곳에만 있다: 요청을 읽을 때와 응답을 쓸 때가 같은 코드를 지나야 인스턴스를 빠뜨린
     * 쪽이 조용히 다른 변수를 가리키는 일이 없다.
     */
    fun getVariablesResponse(readings: List<VariableReading>): ObjectNode = node().apply {
        val array = putArray("getVariableResult")
        readings.forEach { reading ->
            array.addObject().apply {
                put("attributeStatus", reading.status.wireValue)
                // 스키마: *"This field can only be empty when the given status is NOT accepted."*
                reading.value?.let { put("attributeValue", it) }
                statusInfo(reading.reasonCode, reading.additionalInfo)
                reading.ref.writeTo(this)
            }
        }
    }

    /**
     * `SetVariablesResponse` (S04.FR.06/10).
     *
     * **거부는 여기서 나간다.** `MaxSoc ≥ TargetSoC` 판정의 주체가 스테이션인 근거는
     * `ocpp-core` 의 `DeviceModelVariables` KDoc 에 있다 — 표준이 판정의 자리를
     * `SetVariableResultType.attributeStatus` 하나로 정해 두었기 때문이다.
     */
    fun setVariablesResponse(writes: List<VariableWrite>): ObjectNode = node().apply {
        val array = putArray("setVariableResult")
        writes.forEach { write ->
            array.addObject().apply {
                put("attributeStatus", write.status.wireValue)
                statusInfo(write.reasonCode, write.additionalInfo)
                write.ref.writeTo(this)
            }
        }
    }

    /** `StatusInfoType` 은 `reasonCode` 가 있어야 성립한다. 사유가 없으면 아예 싣지 않는다. */
    private fun ObjectNode.statusInfo(reasonCode: String?, additionalInfo: String?) {
        if (reasonCode == null) return
        putObject("attributeStatusInfo").apply {
            put("reasonCode", reasonCode)
            additionalInfo?.let { put("additionalInfo", it) }
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

    /**
     * S02 원격 개시에 대한 스테이션의 답 (`RequestBatterySwapResponse`).
     *
     * ### 재고 판정은 스테이션이 한다 (PLAN §4.5)
     *
     * **S02.FR.04**: 배터리가 부족하면 **Charging Station 이** `Rejected` +
     * `statusInfo.reasonCode = "NoBatteryAvailable"` 로 답한다. CSMS 는 재고를 몰라도 된다 —
     * 그게 `TC_S_102_CSMS` 가 시험하는 것이고, v2 의 "CSMS 가 재고를 알아야 한다"는 잘못된
     * 전제였다.
     *
     * @param reason 거부 사유. `Accepted` 면 `null` 이다 — `statusInfo` 는 선택 필드이고,
     *   받아들이면서 사유를 다는 것은 의미가 없다.
     */
    fun requestBatterySwapResponse(
        accepted: Boolean,
        reason: BatteryRejectionReason? = null,
        additionalInfo: String? = null,
    ): ObjectNode = node().apply {
        put(
            "status",
            if (accepted) BatterySwapWire.GENERIC_ACCEPTED else BatterySwapWire.GENERIC_REJECTED,
        )
        reason?.let {
            putObject("statusInfo").apply {
                put("reasonCode", it.wireValue)
                additionalInfo?.let { text -> put("additionalInfo", text) }
            }
        }
    }

    private fun node(): ObjectNode = JsonNodeFactory.instance.objectNode()
}
