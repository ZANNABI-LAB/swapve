package dev.swapve.ocpp.swap

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * 디바이스 모델 변수 하나를 가리키는 **3요소 식별자**.
 *
 * ### ★ 왜 `(component, variable)` 두 개로는 부족한가
 *
 * OCPP 의 디바이스 모델은 같은 이름의 변수를 **인스턴스로 갈라** 여러 벌 갖는다. 배터리
 * 교환의 타임아웃이 정확히 그 경우다:
 *
 * | Component | Variable | Instance | 뜻 |
 * |---|---|---|---|
 * | `BatterySwapCtrlr` | `Timeout` | `In` | 인가 후 배터리 삽입 대기 |
 * | `BatterySwapCtrlr` | `Timeout` | `Out` | 제공된 배터리 수령 대기 → `BatteryOutTimeout` |
 *
 * **`BatterySwapInTimeout` / `BatterySwapOutTimeout` 이라는 변수 두 개가 아니다.** Part 2
 * 본문의 그 표기는 축약형이고, 정본은 부록 `dm_components_vars.csv` 다 (주의 1).
 * 변수 두 개로 모델링하면 스테이션의 실제 디바이스 모델과 어긋나 `GetVariables` 가 영영
 * `UnknownVariable` 을 받는다. 그래서 이 타입에는 **`Timeout` 이라는 이름 하나에
 * [variableInstance] 를 붙이는 길밖에 없다** — 두 개짜리 모델을 만들 자리를 아예 두지 않았다.
 *
 * ### 대소문자는 정본 그대로 쓰되, 비교는 대소문자를 가리지 않는다
 *
 * 정본의 표기가 일관되지 않다 — `TargetSo`**`C`** 인데 `MaxSo`**`c`** 다 (주의 2).
 * 우리가 "고쳐서" 보내면 상대가 못 알아듣는다. 그래서 [DeviceModelVariables] 의 상수는
 * 정본 철자를 그대로 들고 있고, 대신 **동일성 판정은 [identity] 로 대소문자를 무시**한다 —
 * 공식 스키마가 `name`/`instance` 를 두고 *"Case Insensitive"* 라고 못박았기 때문이다
 * (`GetVariablesRequest.json` `VariableType.name`). 상대가 `maxsoc` 로 답해도 같은 변수다.
 *
 * @param evseId 물리적으로 EVSE 에 매인 컴포넌트일 때만 있다 — `BatteryCartridge` 는 **어느
 *   슬롯의** 배터리인지가 없으면 의미가 없다 (S04.FR.12).
 */
data class VariableRef(
    val component: String,
    val variable: String,
    val componentInstance: String? = null,
    val variableInstance: String? = null,
    val evseId: Int? = null,
) {

    init {
        require(component.isNotBlank()) { "component 이름이 비어 있다" }
        require(variable.isNotBlank()) { "variable 이름이 비어 있다" }
        // EVSE id 0 은 "충전소 전체"를 가리키는 예약값이라 슬롯이 될 수 없다 (Part 2 §K).
        require(evseId == null || evseId >= 1) { "EVSE 번호는 1 이상이어야 한다: $evseId" }
    }

    /**
     * 대소문자를 무시한 동일성 키. 저장소의 키로 쓴다.
     *
     * [equals] 를 직접 손보지 않는 이유는, 그러면 이 값이 **전선에 나갈 철자를 잃기** 때문이다.
     * 표기는 정본 그대로 보존하고, 같은지 여부만 이 키로 판정한다.
     */
    val identity: String
        get() = listOf(component, componentInstance, variable, variableInstance, evseId?.toString())
            .joinToString("|") { it?.lowercase().orEmpty() }

    /** 사람이 읽는 표기 — `BatterySwapCtrlr.Timeout(In)`, `BatteryCartridge[3].SoC`. */
    override fun toString(): String = buildString {
        append(component)
        componentInstance?.let { append('(').append(it).append(')') }
        evseId?.let { append('[').append(it).append(']') }
        append('.').append(variable)
        variableInstance?.let { append('(').append(it).append(')') }
    }

    /**
     * `component` 와 `variable` 두 객체를 [target] 에 적는다.
     *
     * `GetVariableDataType` · `SetVariableDataType` · `GetVariableResultType` ·
     * `SetVariableResultType` 이 **전부 같은 두 필드**를 요구한다 (공식 스키마). 그 인코딩을
     * 한 곳에 두는 것이 요점이다 — 양쪽이 각자 손으로 적으면 인스턴스를 빠뜨린 쪽이 조용히
     * 다른 변수를 가리키게 된다.
     */
    fun writeTo(target: ObjectNode): ObjectNode = target.apply {
        putObject("component").apply {
            put("name", component)
            componentInstance?.let { put("instance", it) }
            evseId?.let { putObject("evse").put("id", it) }
        }
        putObject("variable").apply {
            put("name", variable)
            variableInstance?.let { put("instance", it) }
        }
    }

    companion object {

        /**
         * `component`/`variable` 두 객체를 가진 노드에서 읽는다. 이름이 없으면 `null`.
         *
         * 스키마가 `name` 을 필수로 두므로 `null` 은 "스키마를 통과한 페이로드가 아니다"라는
         * 뜻이고, 실제로는 발생하지 않는다. 그래도 예외를 던지지 않는다 — 읽는 쪽이
         * `UnknownComponent` 로 답할 수 있어야 하기 때문이다.
         */
        fun read(node: JsonNode): VariableRef? {
            val component = node.path("component")
            val variable = node.path("variable")
            val componentName = component.path("name").takeIf { it.isTextual }?.asText() ?: return null
            val variableName = variable.path("name").takeIf { it.isTextual }?.asText() ?: return null

            return VariableRef(
                component = componentName,
                variable = variableName,
                componentInstance = component.path("instance").takeIf { it.isTextual }?.asText(),
                variableInstance = variable.path("instance").takeIf { it.isTextual }?.asText(),
                evseId = component.path("evse").path("id").takeIf { it.isInt }?.asInt(),
            )
        }
    }
}

/**
 * 변수 조회·설정의 결과 상태.
 *
 * `GetVariableStatusEnumType` 과 `SetVariableStatusEnumType` 이 공유하는 값들이다.
 * `SetVariableStatusEnumType` 에만 있는 `RebootRequired` 는 **넣지 않았다** — 재부팅이
 * 필요한 변수를 우리가 하나도 갖고 있지 않은데 값을 만들어 두면, 그걸 언젠가 쓰게 된다
 *.
 */
enum class VariableStatus(val wireValue: String) {

    ACCEPTED("Accepted"),

    /** 값이 제약을 어겼다. **누가 판정하는지는 [dev.swapve.ocpp.swap.DeviceModelVariables] 참조.** */
    REJECTED("Rejected"),

    UNKNOWN_COMPONENT("UnknownComponent"),
    UNKNOWN_VARIABLE("UnknownVariable"),
    NOT_SUPPORTED_ATTRIBUTE_TYPE("NotSupportedAttributeType");

    companion object {
        /** 전선 위의 값으로 찾는다. 모르는 값이면 `null`. */
        fun ofWire(value: String?): VariableStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * 변수 하나에 대한 **조회 결과** (`GetVariableResultType`).
 *
 * 스테이션이 만들고 CSMS 가 읽는다. 두 모듈이 같은 타입을 쓰는 것이 요점이다 — 각자
 * 손으로 필드를 꺼내면 인스턴스를 빠뜨린 쪽이 조용히 다른 변수의 답을 읽게 된다.
 *
 * @param value 받아들여지지 않았으면 `null` 이다. 스키마가 그렇게 규정한다 —
 *   *"This field can only be empty when the given status is NOT accepted."*
 */
data class VariableReading(
    val ref: VariableRef,
    val status: VariableStatus,
    val value: String? = null,
    val reasonCode: String? = null,
    val additionalInfo: String? = null,
) {
    val isAccepted: Boolean get() = status == VariableStatus.ACCEPTED

    companion object {
        /** `GetVariableResultType` 한 항목을 읽는다. 식별자를 읽을 수 없으면 `null`. */
        fun read(node: JsonNode): VariableReading? {
            val ref = VariableRef.read(node) ?: return null
            val statusInfo = node.path("attributeStatusInfo")
            return VariableReading(
                ref = ref,
                status = VariableStatus.ofWire(node.path("attributeStatus").asText())
                    ?: VariableStatus.REJECTED,
                value = node.path("attributeValue").takeIf { it.isTextual }?.asText(),
                reasonCode = statusInfo.path("reasonCode").takeIf { it.isTextual }?.asText(),
                additionalInfo = statusInfo.path("additionalInfo").takeIf { it.isTextual }?.asText(),
            )
        }
    }
}

/**
 * 변수 하나에 대한 **설정 결과** (`SetVariableResultType`).
 *
 * `MaxSoc < TargetSoC` 같은 거부가 여기로 온다 (S04.FR.06/10). 판정 주체가 스테이션인
 * 근거는 [DeviceModelVariables] KDoc 에 있다.
 */
data class VariableWrite(
    val ref: VariableRef,
    val status: VariableStatus,
    val reasonCode: String? = null,
    val additionalInfo: String? = null,
) {
    val isAccepted: Boolean get() = status == VariableStatus.ACCEPTED

    companion object {
        /** `SetVariableResultType` 한 항목을 읽는다. 식별자를 읽을 수 없으면 `null`. */
        fun read(node: JsonNode): VariableWrite? {
            val ref = VariableRef.read(node) ?: return null
            val statusInfo = node.path("attributeStatusInfo")
            return VariableWrite(
                ref = ref,
                status = VariableStatus.ofWire(node.path("attributeStatus").asText())
                    ?: VariableStatus.REJECTED,
                reasonCode = statusInfo.path("reasonCode").takeIf { it.isTextual }?.asText(),
                additionalInfo = statusInfo.path("additionalInfo").takeIf { it.isTextual }?.asText(),
            )
        }
    }
}

/**
 * 배터리 교환이 쓰는 디바이스 모델 변수 — **정본은 부록 `dm_components_vars.csv`**.
 *
 * | Component | Variable | Instance | 용도 |
 * |---|---|---|---|
 * | `BatterySwapCtrlr` | `TargetSoC` | — | 교환 가능 기준 SoC (**S04.FR.05**) |
 * | `BatterySwapCtrlr` | `MaxSoc` | — | 충전 상한, **`≥ TargetSoC`** (**S04.FR.06/10**) |
 * | `BatterySwapCtrlr` | `IdToken` | — | 충전 트랜잭션용 대체 토큰 (**S04.FR.02/03**) |
 * | `BatterySwapCtrlr` | `Timeout` | `In` | 인가 후 배터리 삽입 대기 (§4.7) |
 * | `BatterySwapCtrlr` | `Timeout` | `Out` | 제공된 배터리 수령 대기 → `BatteryOutTimeout` (§4.7) |
 * | `BatterySwapCtrlr` | `Available` | — | 스왑 지원 여부 (모든 TC 의 전제조건) |
 * | `BatteryCartridge` | `SoC` | — | 슬롯에 꽂힌 배터리의 SoC (**S04.FR.12**) |
 * | `BatteryCartridge` | `SoH` | — | 배터리 SoH |
 *
 * ### ★ `MaxSoc ≥ TargetSoC` 를 어기는 설정은 **스테이션이** 거부한다
 *
 * S04.FR.06/10 의 제약을 CSMS 가 미리 막지 않는다. 근거는 셋이다:
 *
 * 1. **`SetVariables` 는 CSMS→CS 명령이고, 판정 자리는 스테이션의 응답 하나뿐이다.**
 *    공식 스키마의 `SetVariableResultType.attributeStatus` 는 필수 필드이고
 *    (`SetVariablesResponse.json`), 그 값을 채우는 쪽은 스테이션이다. 표준이 판정의 자리를
 *    이미 정해 두었다.
 * 2. **디바이스 모델의 소유자가 스테이션이다.** 재고 판정을 스테이션이 하는 것과 같은
 * 이유다 (v2 의 "CSMS 가 알아야 한다"는 잘못된 전제였다). CSMS 가 아는
 *    `TargetSoC` 는 **마지막으로 조회한 값**이고, 그 사이 다른 CSMS 나 로컬 설정이 바꿨을 수
 *    있다. 낡은 값에 근거해 미리 거부하면 정상 설정이 막힌다.
 * 3. **거부를 못 보게 되는 것이 더 나쁘다.** CSMS 가 앞에서 걸러 버리면 스테이션이 실제로
 *    무엇을 받아들였는지가 기록에 남지 않는다. 보내고, 답을 받고, 기록한다.
 *
 * → CSMS 쪽 구현은 `DeviceModelClient`, 스테이션 쪽 판정은 `SimDeviceModel` 에 있다.
 *
 * ### ★ `SwapOrder` 는 **정본 목록에 없는데도** 여기 있다
 *
 * `BatterySwapCtrlr.SwapOrder` 는 **부록 CSV(`dm_components_vars.csv`)에 없고 Part 2
 * 본문(S03 Remark, S03.FR.07)에만 있다** (주의 3). 위 표의 다른 변수들과 근거의
 * 층이 다르다는 뜻이다.
 *
 * 그래도 본문을 따라 [swapOrder] 를 두고 `GetBaseReport(FullInventory)` 보고에 싣는다.
 * 본문이 *"this **must** be reported as `BatterySwapCtrlr.SwapOrder = "Out-In"`"* 라고
 * 못박았고, 부록은 릴리스와 별개로 갱신될 수 있기 때문이다. **불일치를 지우지 않고
 * 여기 남긴다** — 나중에 부록이 이 변수를 담게 되면 이 절이 지워질 자리다. 같은 주석이
 * `station-sim` 의 `SwapOrder` 열거형에도 있다.
 */
object DeviceModelVariables {

    const val COMPONENT_BATTERY_SWAP_CTRLR = "BatterySwapCtrlr"

    /** 슬롯에 꽂힌 배터리 그 자체. **EVSE 번호 없이는 의미가 없다** (S04.FR.12). */
    const val COMPONENT_BATTERY_CARTRIDGE = "BatteryCartridge"

    /** ⚠️ 끝이 대문자 `C` 다. 아래 [VARIABLE_MAX_SOC] 와 다르다 — 정본이 그렇다. */
    const val VARIABLE_TARGET_SOC = "TargetSoC"

    /** ⚠️ 끝이 소문자 `c` 다. 위 [VARIABLE_TARGET_SOC] 와 다르다 — 정본이 그렇다. */
    const val VARIABLE_MAX_SOC = "MaxSoc"

    const val VARIABLE_ID_TOKEN = "IdToken"

    /** **변수 하나**다. `In`/`Out` 은 [INSTANCE_IN]/[INSTANCE_OUT] 인스턴스로 갈린다 (디바이스 모델 — 주의 1). */
    const val VARIABLE_TIMEOUT = "Timeout"

    const val VARIABLE_AVAILABLE = "Available"
    const val VARIABLE_SOC = "SoC"
    const val VARIABLE_SOH = "SoH"

    /** ⚠️ 부록 CSV 에 없는 변수다. 근거와 그 불일치는 이 객체의 KDoc 을 보라 (§4.9 주의 3). */
    const val VARIABLE_SWAP_ORDER = "SwapOrder"

    /** 인가 후 배터리 삽입 대기. 만료돼도 **CSMS 는 통보받지 못한다**. */
    const val INSTANCE_IN = "In"

    /** 제공된 배터리 수령 대기. 만료되면 `BatterySwapRequest(BatteryOutTimeout)` 가 온다 (S03.FR.06). */
    const val INSTANCE_OUT = "Out"

    /** 교환 가능 기준 SoC (S04.FR.05). */
    fun targetSoC() = VariableRef(COMPONENT_BATTERY_SWAP_CTRLR, VARIABLE_TARGET_SOC)

    /** 충전 상한 (S04.FR.06/10). 언제나 [targetSoC] 이상이어야 한다. */
    fun maxSoc() = VariableRef(COMPONENT_BATTERY_SWAP_CTRLR, VARIABLE_MAX_SOC)

    /** 충전 트랜잭션용 대체 토큰 (S04.FR.02/03). 설정하지 않으면 `NoAuthorization` 이다. */
    fun idToken() = VariableRef(COMPONENT_BATTERY_SWAP_CTRLR, VARIABLE_ID_TOKEN)

    fun timeoutIn() = VariableRef(COMPONENT_BATTERY_SWAP_CTRLR, VARIABLE_TIMEOUT, variableInstance = INSTANCE_IN)

    fun timeoutOut() = VariableRef(COMPONENT_BATTERY_SWAP_CTRLR, VARIABLE_TIMEOUT, variableInstance = INSTANCE_OUT)

    fun available() = VariableRef(COMPONENT_BATTERY_SWAP_CTRLR, VARIABLE_AVAILABLE)

    /**
     * 교환 순서 (S03.FR.07). 역순으로 도는 스테이션은 `"Out-In"` 으로 **보고해야 한다**.
     *
     * 값의 집합은 `station-sim` 의 `SwapOrder` 열거형이 들고 있다 — 어느 순서로 움직일지는
     * 스테이션의 성질이지 프로토콜 어휘가 아니다.
     */
    fun swapOrder() = VariableRef(COMPONENT_BATTERY_SWAP_CTRLR, VARIABLE_SWAP_ORDER)

    /** [evseId] 슬롯에 꽂힌 배터리의 SoC (S04.FR.12). */
    fun batterySoC(evseId: Int) = VariableRef(COMPONENT_BATTERY_CARTRIDGE, VARIABLE_SOC, evseId = evseId)

    /** [evseId] 슬롯에 꽂힌 배터리의 SoH. */
    fun batterySoH(evseId: Int) = VariableRef(COMPONENT_BATTERY_CARTRIDGE, VARIABLE_SOH, evseId = evseId)
}
