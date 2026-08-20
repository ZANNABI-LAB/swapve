package dev.swapve.station

import dev.swapve.ocpp.swap.DeviceModelVariables
import dev.swapve.ocpp.swap.VariableReading
import dev.swapve.ocpp.swap.VariableRef
import dev.swapve.ocpp.swap.VariableStatus
import dev.swapve.ocpp.swap.VariableWrite

/**
 * 스테이션이 들고 있는 디바이스 모델 (S04).
 *
 * ### ★ 제약 판정은 여기서 한다 — 스테이션이 디바이스 모델의 소유자다
 *
 * **S04.FR.06/10**: `MaxSoc` 은 `TargetSoC` 이상이어야 한다. 이 검사를 CSMS 가 아니라
 * 스테이션이 하는 이유는 [DeviceModelVariables] 의 KDoc 에 근거 셋으로 적어 두었다 —
 * 요약하면 (1) 표준이 판정의 자리를 `SetVariableResultType.attributeStatus` 하나로
 * 정해 두었고, (2) CSMS 가 아는 값은 마지막으로 조회한 낡은 값일 수 있으며,
 * (3) 앞에서 걸러 버리면 스테이션이 실제로 무엇을 받아들였는지가 기록에 남지 않는다.
 *
 * ### `BatteryCartridge` 는 저장하지 않는다 — 슬롯에서 파생한다
 *
 * `BatteryCartridge.SoC`/`SoH` 는 **꽂혀 있는 배터리의 성질**이지 설정값이 아니다
 * (S04.FR.12). 따로 저장해 두면 배터리가 바뀐 뒤에도 옛 값이 남아 조용히 거짓을 답하게
 * 된다. 빈 슬롯이면 **없는 값을 지어내지 않고** `UnknownComponent` 로 답한다 — CSMS 쪽
 * `SlotStateRegistry` 가 `NotifyEvent` 를 다루는 태도와 같다.
 *
 * ### 타임아웃은 변수 하나 + 인스턴스다
 *
 * `Timeout` 을 **인스턴스 없이** 조회하면 `UnknownVariable` 이다. 어느 타임아웃인지 정해지지
 * 않았기 때문이다 (주의 1). 이 동작이 "변수 2개로 잘못 모델링하지 않았다"를
 * 실행되는 검사로 만든다.
 *
 * 스레드 안전하다 — 세션이 수신 코루틴에서 부른다.
 */
class SimDeviceModel(
    settings: Map<VariableRef, String>,
    /**
     * 이 스테이션이 가진 슬롯 번호 전부.
     *
     * [batteryAt] 만으로는 **무엇을 물어야 할지** 알 수 없다 — 그건 "이 번호의 배터리를
     * 다오"에 답할 뿐이다. [fullInventory] 는 묻지 않은 것까지 열거해야 하므로 슬롯의
     * 집합 자체가 필요하다.
     */
    private val slotIds: List<Int> = emptyList(),
    private val batteryAt: (Int) -> SimBattery?,
) {

    private class Entry(val ref: VariableRef, var value: String)

    private val lock = Any()

    /** 대소문자를 무시한 [VariableRef.identity] 로 색인한다 — 스키마가 이름을 case-insensitive 로 규정한다. */
    private val entries: MutableMap<String, Entry> =
        settings.entries.associateTo(LinkedHashMap()) { (ref, value) -> ref.identity to Entry(ref, value) }

    // ------------------------------------------------------------------ 조회

    /** `GetVariables` 한 항목. */
    fun read(ref: VariableRef): VariableReading = synchronized(lock) {
        if (isCartridge(ref)) return cartridgeReading(ref)
        if (!isKnownComponent(ref)) return unknownComponent(ref)

        val entry = entries[ref.identity]
            ?: return VariableReading(
                ref,
                VariableStatus.UNKNOWN_VARIABLE,
                reasonCode = REASON_UNKNOWN_VARIABLE,
                additionalInfo = unknownVariableHint(ref),
            )

        VariableReading(ref, VariableStatus.ACCEPTED, value = entry.value)
    }

    /** 설정된 값. 없거나 조회할 수 없는 변수면 `null`. 시뮬레이터 내부가 쓰는 지름길이다. */
    fun valueOf(ref: VariableRef): String? = read(ref).takeIf { it.status == VariableStatus.ACCEPTED }?.value

    /** 정수로 읽는다. 값이 없거나 정수가 아니면 `null`. */
    fun intOf(ref: VariableRef): Int? = valueOf(ref)?.toIntOrNull()

    /**
     * ★ **전체 재고** — `GetBaseReport(FullInventory)` 가 보고할 목록 (B03, `TC_S_104_CS`).
     *
     * ### 저장된 것 + 파생되는 것을 여기서 합친다
     *
     * [read] 는 "이 변수를 아는가"에 답하고, 이 함수는 "무엇을 아는가"에 답한다. 둘의
     * 대상이 어긋나면 보고에 실린 변수를 조회했을 때 `UnknownVariable` 이 나오는
     * 모순이 생기므로, `BatteryCartridge` 는 여기서도 [cartridgeReading] 을 지난다 —
     * 답을 만드는 코드가 한 곳이어야 두 경로가 같은 말을 한다.
     *
     * ### 빈 슬롯은 싣지 않는다
     *
     * 카트리지가 없는 슬롯은 보고할 카트리지 변수도 없다. 상태를 `UnknownComponent` 로
     * 실어 보내면 "이 변수가 이런 값이다"라는 보고에 "이 변수는 없다"를 섞는 셈이다 —
     * 없는 값을 지어내지 않는 [cartridgeReading] 의 태도와 같은 이유로 아예 뺀다.
     *
     * 순서는 설정 순서 → 슬롯 번호 순이다. 보고가 여러 건으로 쪼개져도 재조립한 쪽이
     * 같은 목록을 보게 하려면 열거 순서가 정해져 있어야 한다.
     */
    fun fullInventory(): List<VariableReading> = synchronized(lock) {
        val stored = entries.values.map { VariableReading(it.ref, VariableStatus.ACCEPTED, value = it.value) }
        val cartridges = slotIds.sorted()
            .filter { batteryAt(it) != null }
            .flatMap { evseId ->
                listOf(
                    DeviceModelVariables.batterySoC(evseId),
                    DeviceModelVariables.batterySoH(evseId),
                ).map { cartridgeReading(it) }
            }
            .filter { it.isAccepted }

        stored + cartridges
    }

    // ------------------------------------------------------------------ 설정

    /**
     * `SetVariables` 한 항목.
     *
     * 거부는 셋 중 하나다:
     * - **`MaxSoc < TargetSoC`** — S04.FR.06/10 위반. 어느 쪽을 바꾸든 결과가 그러면 거부한다.
     * - 값의 모양이 그 변수의 타입과 다르다 (정수 자리에 문자열 등).
     * - 애초에 설정할 수 없는 변수다 (`BatteryCartridge` — 배터리의 SoC 를 CSMS 가 정할 수 없다).
     */
    fun write(ref: VariableRef, value: String): VariableWrite = synchronized(lock) {
        if (isCartridge(ref)) {
            return VariableWrite(
                ref,
                VariableStatus.REJECTED,
                reasonCode = REASON_READ_ONLY,
                additionalInfo = "배터리의 값은 관측되는 것이지 설정되는 것이 아니다: $ref",
            )
        }
        if (!isKnownComponent(ref)) return unknownComponentWrite(ref)

        val entry = entries[ref.identity]
            ?: return VariableWrite(
                ref,
                VariableStatus.UNKNOWN_VARIABLE,
                reasonCode = REASON_UNKNOWN_VARIABLE,
                additionalInfo = unknownVariableHint(ref),
            )

        validate(ref, value)?.let { return it }

        entry.value = value
        VariableWrite(ref, VariableStatus.ACCEPTED)
    }

    // ------------------------------------------------------------------ 내부

    /**
     * ★ **S04.FR.06/10 — `MaxSoc ≥ TargetSoC`.**
     *
     * 어느 쪽을 설정하든 **적용 후의 두 값**으로 판정한다. `TargetSoC` 를 올려서 상한을
     * 넘어서는 경우도 같은 위반이기 때문이다 — 한쪽만 검사하면 반대 방향으로 우회된다.
     */
    private fun validate(ref: VariableRef, value: String): VariableWrite? = when {
        ref.identity == DeviceModelVariables.targetSoC().identity -> {
            val target = percentOrNull(value)
            when {
                target == null -> rejectPercent(ref, value)
                target > currentInt(DeviceModelVariables.maxSoc(), DEFAULT_MAX_SOC) ->
                    rejectSocOrder(ref, target, currentInt(DeviceModelVariables.maxSoc(), DEFAULT_MAX_SOC))

                else -> null
            }
        }

        ref.identity == DeviceModelVariables.maxSoc().identity -> {
            val max = percentOrNull(value)
            when {
                max == null -> rejectPercent(ref, value)
                max < currentInt(DeviceModelVariables.targetSoC(), 0) ->
                    rejectSocOrder(ref, currentInt(DeviceModelVariables.targetSoC(), 0), max)

                else -> null
            }
        }

        // 교환 순서는 **기계의 성질**이지 설정이 아니다. CSMS 가 정해 줄 수 있는 것이었다면
        // S03.FR.07 이 "보고해야 한다"가 아니라 "설정한다"라고 적혔을 것이다.
        ref.variable.equals(DeviceModelVariables.VARIABLE_SWAP_ORDER, ignoreCase = true) ->
            VariableWrite(
                ref,
                VariableStatus.REJECTED,
                reasonCode = REASON_READ_ONLY,
                additionalInfo = "교환 순서는 스테이션의 성질이라 설정할 수 없다 (S03.FR.07)",
            )

        // 이름 비교는 언제나 대소문자를 무시한다 — 스키마가 그렇게 규정한다.
        ref.variable.equals(DeviceModelVariables.VARIABLE_TIMEOUT, ignoreCase = true) ->
            if (value.toIntOrNull()?.let { it >= 0 } == true) {
                null
            } else {
                VariableWrite(
                    ref,
                    VariableStatus.REJECTED,
                    reasonCode = REASON_INVALID_VALUE,
                    additionalInfo = "타임아웃은 0 이상의 정수 초여야 한다: $value",
                )
            }

        ref.variable.equals(DeviceModelVariables.VARIABLE_AVAILABLE, ignoreCase = true) ->
            if (value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true)) {
                null
            } else {
                VariableWrite(
                    ref,
                    VariableStatus.REJECTED,
                    reasonCode = REASON_INVALID_VALUE,
                    additionalInfo = "boolean 이어야 한다: $value",
                )
            }

        // IdToken 은 임의의 문자열이다. **빈 문자열도 정상**이다 — "설정하지 않음"을 뜻한다
        // (TC_S_103_CSMS 의 전제조건이 그 상태다).
        else -> null
    }

    private fun rejectSocOrder(ref: VariableRef, target: Int, max: Int) = VariableWrite(
        ref,
        VariableStatus.REJECTED,
        reasonCode = REASON_SOC_ORDER,
        additionalInfo = "S04.FR.06/10 — MaxSoc($max) 은 TargetSoC($target) 이상이어야 한다",
    )

    private fun rejectPercent(ref: VariableRef, value: String) = VariableWrite(
        ref,
        VariableStatus.REJECTED,
        reasonCode = REASON_INVALID_VALUE,
        additionalInfo = "0..100 의 정수 퍼센트여야 한다: $value",
    )

    private fun currentInt(ref: VariableRef, fallback: Int): Int =
        entries[ref.identity]?.value?.toIntOrNull() ?: fallback

    private fun percentOrNull(value: String): Int? = value.toIntOrNull()?.takeIf { it in 0..100 }

    private fun isKnownComponent(ref: VariableRef): Boolean =
        ref.component.equals(DeviceModelVariables.COMPONENT_BATTERY_SWAP_CTRLR, ignoreCase = true)

    private fun isCartridge(ref: VariableRef): Boolean =
        ref.component.equals(DeviceModelVariables.COMPONENT_BATTERY_CARTRIDGE, ignoreCase = true)

    /**
     * `BatteryCartridge` 는 **슬롯에서 파생한다** (S04.FR.12).
     *
     * EVSE 번호가 없으면 어느 배터리인지 정해지지 않고, 빈 슬롯이면 카트리지 자체가 없다.
     * 둘 다 `UnknownComponent` 다 — 없는 값을 지어내지 않는다.
     */
    private fun cartridgeReading(ref: VariableRef): VariableReading {
        val evseId = ref.evseId ?: return VariableReading(
            ref,
            VariableStatus.UNKNOWN_COMPONENT,
            reasonCode = REASON_UNKNOWN_COMPONENT,
            additionalInfo = "BatteryCartridge 는 evse.id 없이는 어느 배터리인지 정해지지 않는다 (S04.FR.12)",
        )

        val battery = batteryAt(evseId) ?: return VariableReading(
            ref,
            VariableStatus.UNKNOWN_COMPONENT,
            reasonCode = REASON_UNKNOWN_COMPONENT,
            additionalInfo = "슬롯 $evseId 에 배터리가 없다",
        )

        return when {
            ref.variable.equals(DeviceModelVariables.VARIABLE_SOC, ignoreCase = true) ->
                VariableReading(ref, VariableStatus.ACCEPTED, value = percentText(battery.soC))

            ref.variable.equals(DeviceModelVariables.VARIABLE_SOH, ignoreCase = true) ->
                VariableReading(ref, VariableStatus.ACCEPTED, value = percentText(battery.soH))

            else -> VariableReading(
                ref,
                VariableStatus.UNKNOWN_VARIABLE,
                reasonCode = REASON_UNKNOWN_VARIABLE,
                additionalInfo = "BatteryCartridge 가 갖는 변수가 아니다: ${ref.variable}",
            )
        }
    }

    private fun unknownComponent(ref: VariableRef) = VariableReading(
        ref,
        VariableStatus.UNKNOWN_COMPONENT,
        reasonCode = REASON_UNKNOWN_COMPONENT,
        additionalInfo = "이 스테이션이 갖지 않은 컴포넌트다: ${ref.component}",
    )

    private fun unknownComponentWrite(ref: VariableRef) = VariableWrite(
        ref,
        VariableStatus.UNKNOWN_COMPONENT,
        reasonCode = REASON_UNKNOWN_COMPONENT,
        additionalInfo = "이 스테이션이 갖지 않은 컴포넌트다: ${ref.component}",
    )

    /** `Timeout` 을 인스턴스 없이 물었을 때 그 사실을 말해 준다 (주의 1). */
    private fun unknownVariableHint(ref: VariableRef): String =
        if (ref.variable.equals(DeviceModelVariables.VARIABLE_TIMEOUT, ignoreCase = true) &&
            ref.variableInstance == null
        ) {
            "Timeout 은 instance 로 In/Out 을 정해야 한다 (변수 두 개가 아니다)"
        } else {
            "이 컴포넌트가 갖지 않은 변수다: ${ref.variable}"
        }

    /** 정수면 정수로 적는다 — `80.0` 이 아니라 `80` 이다. 상대가 `%` 정수로 읽는다. */
    private fun percentText(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    companion object {

        /** `MaxSoc` 이 설정돼 있지 않을 때의 상한. 충전은 100% 까지 가능하다고 본다. */
        const val DEFAULT_MAX_SOC = 100

        // statusInfo.reasonCode 는 20자 제한이다 (공식 스키마). 짧게 둔다.
        const val REASON_SOC_ORDER = "MaxSocBelowTarget"
        const val REASON_INVALID_VALUE = "InvalidValue"
        const val REASON_READ_ONLY = "ReadOnly"
        const val REASON_UNKNOWN_COMPONENT = "UnknownComponent"
        const val REASON_UNKNOWN_VARIABLE = "UnknownVariable"

        /**
         * [StationSimConfig] 가 정한 값으로 디바이스 모델을 만든다.
         *
         * `IdToken` 은 **언제나 존재하되 비어 있을 수 있다.** 빈 문자열이 곧 *"설정하지 않음"*
         * 이고, 그때 충전 트랜잭션은 `NoAuthorization` 으로 보고된다 (S04.FR.02/03) —
         * `TC_S_103_CSMS` 의 전제조건이 정확히 그 상태다.
         */
        fun of(config: StationSimConfig, batteryAt: (Int) -> SimBattery?): SimDeviceModel = SimDeviceModel(
            settings = linkedMapOf(
                DeviceModelVariables.available() to config.swapAvailable.toString(),
                DeviceModelVariables.targetSoC() to config.targetSoC.toString(),
                DeviceModelVariables.maxSoc() to config.maxSoc.toString(),
                DeviceModelVariables.idToken() to config.chargingIdToken.orEmpty(),
                DeviceModelVariables.timeoutIn() to config.batteryInTimeout.seconds.toString(),
                DeviceModelVariables.timeoutOut() to config.batteryOutTimeout.seconds.toString(),
                // S03.FR.07 — 역순으로 도는 스테이션은 이 값을 **보고해야 한다**. 기본값인
                // In-Out 도 함께 싣는다: "보고하지 않음"과 "In-Out 이다"를 받는 쪽이
                // 구분할 수 없으면, 순서를 아는 유일한 수단이 메시지 순서 추측이 된다.
                DeviceModelVariables.swapOrder() to config.swapOrder.wireValue,
            ),
            slotIds = config.slots.map { it.slotId },
            batteryAt = batteryAt,
        )
    }
}
