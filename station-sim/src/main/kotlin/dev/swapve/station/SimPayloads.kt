package dev.swapve.station

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.ocpp.json.OcppDateTime
import dev.swapve.ocpp.swap.AvailabilityState
import dev.swapve.ocpp.swap.BatteryRejectionReason
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.ocpp.swap.DeviceModelVariables
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
 *.
 *
 * 슬롯 가용성만은 [AvailabilityState] 를 지난다 — 값이 아니라 **반전된 의미**를 다루기
 * 때문이다. 이 파일 어디에도 `"Occupied"` 라는 리터럴은 없다.
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
     * **deprecated** 다 (Part 2 S03 Remark).
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
     * 충전 트랜잭션 사건 (S04). **교환 트랜잭션과 별개다**.
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
     * `GetVariablesResponse` (S04.FR.12).
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

    /**
     * `GetBaseReportResponse` (B03, `TC_S_104_CS`).
     *
     * **보고 자체는 여기서 나가지 않는다.** 이 응답은 "그 청을 받아들였다"까지이고, 실제
     * 목록은 이어지는 [notifyReport] 들이 나른다. 그 둘을 한 메시지로 합칠 수 없는 것이
     * 이 경로의 성격이다 — 목록이 한 프레임에 담긴다는 보장이 없다.
     */
    fun getBaseReportResponse(accepted: Boolean, reasonCode: String? = null, additionalInfo: String? = null): ObjectNode =
        node().apply {
            put(
                "status",
                if (accepted) {
                    BatterySwapWire.DEVICE_MODEL_ACCEPTED
                } else {
                    BatterySwapWire.DEVICE_MODEL_NOT_SUPPORTED
                },
            )
            if (reasonCode != null) {
                putObject("statusInfo").apply {
                    put("reasonCode", reasonCode)
                    additionalInfo?.let { put("additionalInfo", it) }
                }
            }
        }

    /**
     * ★ **`NotifyReport` 한 조각** (B03, `TC_S_104_CS`).
     *
     * ### 조각이라는 것이 이 메시지의 본질이다
     *
     * [seqNo] 는 0 부터 1 씩 오르고, 뒤에 더 올 것이 있으면 [tbc] 가 참이다. 마지막 조각은
     * `tbc` 를 싣지 않는다 — 스키마의 기본값이 거짓이라 **없는 것이 곧 "여기서 끝"** 이다.
     * 굳이 `false` 를 적어 보내지 않는 이유는, 받는 쪽이 "없음"과 "거짓"을 다르게 다루면
     * 그 자리가 곧 버그이기 때문이다. 기본값대로 두면 그 구분이 생길 수 없다.
     *
     * ### 속성과 특성을 매번 함께 싣는다
     *
     * `variableAttribute` 는 **필수**이고 (`minItems: 1`), 이 스테이션이 보고하는 것은
     * 언제나 `Actual` 하나다. `variableCharacteristics` 는 선택이지만 [characteristics] 로
     * 함께 싣는다 — 받는 쪽이 `"80"` 이라는 문자열만 보고 그것이 퍼센트인지 초인지
     * 짐작하게 두면, 짐작이 곧 계약이 된다.
     */
    fun notifyReport(
        requestId: Int,
        seqNo: Int,
        tbc: Boolean,
        at: Instant,
        readings: List<VariableReading>,
    ): ObjectNode = node().apply {
        put("requestId", requestId)
        put("generatedAt", OcppDateTime.format(at))
        put("seqNo", seqNo)
        // 마지막 조각에는 아예 싣지 않는다 (스키마 기본값 = false).
        if (tbc) put("tbc", true)

        val array = putArray("reportData")
        readings.forEach { reading ->
            array.addObject().apply {
                // 식별자 인코딩은 언제나 이 한 곳을 지난다 — 조회와 보고가 같은 철자를 써야
                // 재조립한 쪽이 두 경로의 답을 같은 변수로 볼 수 있다.
                reading.ref.writeTo(this)
                putArray("variableAttribute").addObject().apply {
                    put("type", BatterySwapWire.ATTRIBUTE_ACTUAL)
                    reading.value?.let { put("value", it) }
                    put("mutability", mutabilityOf(reading.ref))
                }
                characteristics(reading.ref)
            }
        }
    }

    /**
     * `BatteryCartridge` 와 `SwapOrder` 는 **관측되는 값**이라 읽기 전용이다.
     *
     * 같은 판정이 `SimDeviceModel.write` 에도 있다. 거기서는 거부의 근거이고 여기서는 그
     * 사실의 보고다 — 둘이 어긋나면 "설정할 수 있다고 보고해 놓고 거부하는" 스테이션이 된다.
     */
    private fun mutabilityOf(ref: VariableRef): String = when {
        ref.component.equals(DeviceModelVariables.COMPONENT_BATTERY_CARTRIDGE, ignoreCase = true) ->
            BatterySwapWire.MUTABILITY_READ_ONLY

        ref.variable.equals(DeviceModelVariables.VARIABLE_SWAP_ORDER, ignoreCase = true) ->
            BatterySwapWire.MUTABILITY_READ_ONLY

        else -> BatterySwapWire.MUTABILITY_READ_WRITE
    }

    /**
     * `VariableCharacteristicsType` — `dataType` 과 `supportsMonitoring` 이 **필수**다.
     *
     * `supportsMonitoring = false` 로 고정한다. 변수 감시(`SetVariableMonitoring`)를 하나도
     * 구현하지 않았으므로, 지원한다고 보고하면 CSMS 가 걸 수 있다고 믿는 감시를 우리가
     * 받지 못한다 (없는 기능을 있는 척하지 않는다).
     */
    private fun ObjectNode.characteristics(ref: VariableRef) {
        putObject("variableCharacteristics").apply {
            when {
                ref.variable.equals(DeviceModelVariables.VARIABLE_AVAILABLE, ignoreCase = true) ->
                    put("dataType", BatterySwapWire.DATA_TYPE_BOOLEAN)

                ref.variable.equals(DeviceModelVariables.VARIABLE_ID_TOKEN, ignoreCase = true) ->
                    put("dataType", BatterySwapWire.DATA_TYPE_STRING)

                ref.variable.equals(DeviceModelVariables.VARIABLE_TIMEOUT, ignoreCase = true) -> {
                    put("dataType", BatterySwapWire.DATA_TYPE_INTEGER)
                    put("unit", BatterySwapWire.UNIT_SECONDS)
                    put("minLimit", 0)
                }

                ref.variable.equals(DeviceModelVariables.VARIABLE_SWAP_ORDER, ignoreCase = true) -> {
                    // OptionList 는 valuesList 가 필수다 (공식 스키마). 값의 집합을 아는 것은
                    // 이 열거형 하나뿐이라 리터럴로 적지 않는다.
                    put("dataType", BatterySwapWire.DATA_TYPE_OPTION_LIST)
                    put("valuesList", SwapOrder.entries.joinToString(",") { it.wireValue })
                }

                // 남은 것은 전부 퍼센트다 — TargetSoC · MaxSoc · 카트리지의 SoC/SoH.
                else -> {
                    put("dataType", BatterySwapWire.DATA_TYPE_DECIMAL)
                    put("unit", BatterySwapWire.UNIT_PERCENT)
                    put("minLimit", 0)
                    put("maxLimit", 100)
                }
            }
            put("supportsMonitoring", false)
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
     * 교환 사건.
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
     * ### 재고 판정은 스테이션이 한다
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
