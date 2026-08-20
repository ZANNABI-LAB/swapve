package dev.swapve.ocpp.swap

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The **three-part identifier** of one device model variable.
 *
 * ### ★ Why `(component, variable)` is not enough
 *
 * The OCPP device model keeps several copies of a same-named variable, **split by instance**.
 * The battery swap timeouts are exactly that case:
 *
 * | Component | Variable | Instance | Meaning |
 * |---|---|---|---|
 * | `BatterySwapCtrlr` | `Timeout` | `In` | waiting for a battery after authorization |
 * | `BatterySwapCtrlr` | `Timeout` | `Out` | waiting for the offered battery to be collected |
 *
 * **They are not two variables named `BatterySwapInTimeout` and `BatterySwapOutTimeout`.** That
 * spelling in the Part 2 prose is shorthand; the appendix `dm_components_vars.csv` is
 * authoritative. Modelling them as two variables disagrees with the station's actual device
 * model, and `GetVariables` then receives `UnknownVariable` forever. So this type offers only
 * **one name, `Timeout`, plus a [variableInstance]** — there is no place to build the
 * two-variable model in the first place.
 *
 * ### The canonical spelling is kept; comparison ignores case
 *
 * The canonical spelling is not consistent — `TargetSo`**`C`** but `MaxSo`**`c`**. "Correcting"
 * it on the way out means the peer no longer recognises it. So the constants in
 * [DeviceModelVariables] carry the canonical spelling verbatim, and **identity is decided by
 * [identity], which ignores case** — the official schema states `name` and `instance` are
 * *"Case Insensitive"* (`GetVariablesRequest.json`, `VariableType.name`). A peer answering
 * `maxsoc` means the same variable.
 *
 * @param evseId present only for components physically tied to an EVSE — `BatteryCartridge`
 *   means nothing without saying **which slot's** battery it is (S04.FR.12).
 */
data class VariableRef(
    val component: String,
    val variable: String,
    val componentInstance: String? = null,
    val variableInstance: String? = null,
    val evseId: Int? = null,
) {

    init {
        require(component.isNotBlank()) { "component name is blank" }
        require(variable.isNotBlank()) { "variable name is blank" }
        // EVSE id 0 is reserved for the charging station as a whole, so it cannot be a slot (Part 2 §K).
        require(evseId == null || evseId >= 1) { "EVSE id must be at least 1: $evseId" }
    }

    /**
     * A case-insensitive identity key, used as a map key.
     *
     * [equals] is deliberately left alone: overriding it would cost this value **the spelling it
     * has to put on the wire**. The spelling stays canonical, and only sameness is decided here.
     */
    val identity: String
        get() = listOf(component, componentInstance, variable, variableInstance, evseId?.toString())
            .joinToString("|") { it?.lowercase().orEmpty() }

    /** How a human reads it — `BatterySwapCtrlr.Timeout(In)`, `BatteryCartridge[3].SoC`. */
    override fun toString(): String = buildString {
        append(component)
        componentInstance?.let { append('(').append(it).append(')') }
        evseId?.let { append('[').append(it).append(']') }
        append('.').append(variable)
        variableInstance?.let { append('(').append(it).append(')') }
    }

    /**
     * Writes the `component` and `variable` objects into [target].
     *
     * `GetVariableDataType`, `SetVariableDataType`, `GetVariableResultType` and
     * `SetVariableResultType` all require **the same two fields** (official schema). Keeping
     * that encoding in one place is the point — spelled out by hand on both sides, whichever
     * side forgets the instance quietly addresses a different variable.
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
         * Reads from a node holding `component` and `variable` objects. `null` if a name is
         * missing.
         *
         * The schema makes `name` required, so `null` means "this payload never passed a
         * schema" and does not occur in practice. It still does not throw — the reader has to be
         * able to answer `UnknownComponent`.
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
 * The outcome of reading or writing a variable.
 *
 * The values shared by `GetVariableStatusEnumType` and `SetVariableStatusEnumType`.
 * `RebootRequired`, which only `SetVariableStatusEnumType` has, is **deliberately absent** — no
 * variable here needs a reboot, and a constant that exists without a reason eventually gets
 * used.
 */
enum class VariableStatus(val wireValue: String) {

    ACCEPTED("Accepted"),

    /** The value broke a constraint. **For who decides that, see [DeviceModelVariables].** */
    REJECTED("Rejected"),

    UNKNOWN_COMPONENT("UnknownComponent"),
    UNKNOWN_VARIABLE("UnknownVariable"),
    NOT_SUPPORTED_ATTRIBUTE_TYPE("NotSupportedAttributeType");

    companion object {
        /** Looks one up by its wire value. `null` if unrecognised. */
        fun ofWire(value: String?): VariableStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * **The result of reading** one variable (`GetVariableResultType`).
 *
 * Produced by a station, read by a CSMS. Both sides sharing the type is the point — pulling the
 * fields out by hand, whichever side forgets the instance quietly reads another variable's
 * answer.
 *
 * @param value `null` when the read was not accepted. The schema says so —
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
        /** Reads one `GetVariableResultType` entry. `null` if the identifier is unreadable. */
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
 * **The result of writing** one variable (`SetVariableResultType`).
 *
 * A refusal such as `MaxSoc < TargetSoC` arrives here (S04.FR.06/10). Why the station is the one
 * deciding that is set out in the [DeviceModelVariables] documentation.
 */
data class VariableWrite(
    val ref: VariableRef,
    val status: VariableStatus,
    val reasonCode: String? = null,
    val additionalInfo: String? = null,
) {
    val isAccepted: Boolean get() = status == VariableStatus.ACCEPTED

    companion object {
        /** Reads one `SetVariableResultType` entry. `null` if the identifier is unreadable. */
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
 * The device model variables battery swapping uses — **the appendix `dm_components_vars.csv`
 * is authoritative**.
 *
 * | Component | Variable | Instance | Purpose |
 * |---|---|---|---|
 * | `BatterySwapCtrlr` | `TargetSoC` | — | the SoC at which a battery may be swapped (**S04.FR.05**) |
 * | `BatterySwapCtrlr` | `MaxSoc` | — | the charging ceiling, **`≥ TargetSoC`** (**S04.FR.06/10**) |
 * | `BatterySwapCtrlr` | `IdToken` | — | substitute token for charging transactions (**S04.FR.02/03**) |
 * | `BatterySwapCtrlr` | `Timeout` | `In` | waiting for a battery after authorization |
 * | `BatterySwapCtrlr` | `Timeout` | `Out` | waiting for the offered battery to be collected |
 * | `BatterySwapCtrlr` | `Available` | — | whether swapping is supported (a precondition of every TC) |
 * | `BatteryCartridge` | `SoC` | — | SoC of the battery in the slot (**S04.FR.12**) |
 * | `BatteryCartridge` | `SoH` | — | SoH of that battery |
 *
 * ### ★ A setting that breaks `MaxSoc ≥ TargetSoC` is refused **by the station**
 *
 * The CSMS does not enforce the S04.FR.06/10 constraint up front. Three reasons:
 *
 * 1. **`SetVariables` is a CSMS→CS command, and the only place a verdict fits is the station's
 *    response.** `SetVariableResultType.attributeStatus` is a required field of the official
 *    schema (`SetVariablesResponse.json`), and the station fills it. The standard already chose
 *    where the decision belongs.
 * 2. **The station owns the device model** — the same reason stock is judged there. What a CSMS
 *    knows as `TargetSoC` is **the value it last read**, and another CSMS or a local setting may
 *    have changed it since. Refusing on a stale value blocks a legitimate setting.
 * 3. **Hiding the refusal is worse.** Filtering it out at the CSMS leaves no record of what the
 *    station actually accepted. Send it, take the answer, record it.
 *
 * ### ★ `SwapOrder` is here although it is **not in the authoritative list**
 *
 * `BatterySwapCtrlr.SwapOrder` is **absent from the appendix CSV and appears only in the Part 2
 * prose** (S03 Remark, S03.FR.07) — its grounding differs in kind from every other row above.
 *
 * [swapOrder] follows the prose anyway and reports it in `GetBaseReport(FullInventory)`, because
 * the prose is explicit — *"this **must** be reported as
 * `BatterySwapCtrlr.SwapOrder = "Out-In"`"* — and appendices can be revised separately from a
 * release. **The discrepancy is recorded rather than smoothed over**: this paragraph is what
 * gets deleted once the appendix carries the variable.
 */
object DeviceModelVariables {

    const val COMPONENT_BATTERY_SWAP_CTRLR = "BatterySwapCtrlr"

    /** The battery in the slot itself. **Meaningless without an EVSE number** (S04.FR.12). */
    const val COMPONENT_BATTERY_CARTRIDGE = "BatteryCartridge"

    /** ⚠️ Ends in a capital `C`, unlike [VARIABLE_MAX_SOC] below. The canonical spelling does that. */
    const val VARIABLE_TARGET_SOC = "TargetSoC"

    /** ⚠️ Ends in a lowercase `c`, unlike [VARIABLE_TARGET_SOC] above. The canonical spelling does that. */
    const val VARIABLE_MAX_SOC = "MaxSoc"

    const val VARIABLE_ID_TOKEN = "IdToken"

    /** **One variable.** `In` and `Out` separate it by instance — [INSTANCE_IN] and [INSTANCE_OUT]. */
    const val VARIABLE_TIMEOUT = "Timeout"

    const val VARIABLE_AVAILABLE = "Available"
    const val VARIABLE_SOC = "SoC"
    const val VARIABLE_SOH = "SoH"

    /** ⚠️ Not in the appendix CSV. For the grounding and that discrepancy see this object's documentation. */
    const val VARIABLE_SWAP_ORDER = "SwapOrder"

    /** Waiting for a battery after authorization. **The CSMS is not told** when it expires. */
    const val INSTANCE_IN = "In"

    /** Waiting for the offered battery to be collected. On expiry a `BatterySwapRequest(BatteryOutTimeout)` arrives (S03.FR.06). */
    const val INSTANCE_OUT = "Out"

    /** The SoC at which a battery may be swapped (S04.FR.05). */
    fun targetSoC() = VariableRef(COMPONENT_BATTERY_SWAP_CTRLR, VARIABLE_TARGET_SOC)

    /** The charging ceiling (S04.FR.06/10). Must always be at least [targetSoC]. */
    fun maxSoc() = VariableRef(COMPONENT_BATTERY_SWAP_CTRLR, VARIABLE_MAX_SOC)

    /** The substitute token for charging transactions (S04.FR.02/03). Unset means `NoAuthorization`. */
    fun idToken() = VariableRef(COMPONENT_BATTERY_SWAP_CTRLR, VARIABLE_ID_TOKEN)

    fun timeoutIn() = VariableRef(COMPONENT_BATTERY_SWAP_CTRLR, VARIABLE_TIMEOUT, variableInstance = INSTANCE_IN)

    fun timeoutOut() = VariableRef(COMPONENT_BATTERY_SWAP_CTRLR, VARIABLE_TIMEOUT, variableInstance = INSTANCE_OUT)

    fun available() = VariableRef(COMPONENT_BATTERY_SWAP_CTRLR, VARIABLE_AVAILABLE)

    /**
     * The swap order (S03.FR.07). A station that runs it in reverse **must report** `"Out-In"`.
     *
     * The set of values belongs to the simulator, not here — which order a station moves in is a
     * property of that station, not protocol vocabulary.
     */
    fun swapOrder() = VariableRef(COMPONENT_BATTERY_SWAP_CTRLR, VARIABLE_SWAP_ORDER)

    /** SoC of the battery in slot [evseId] (S04.FR.12). */
    fun batterySoC(evseId: Int) = VariableRef(COMPONENT_BATTERY_CARTRIDGE, VARIABLE_SOC, evseId = evseId)

    /** SoH of the battery in slot [evseId]. */
    fun batterySoH(evseId: Int) = VariableRef(COMPONENT_BATTERY_CARTRIDGE, VARIABLE_SOH, evseId = evseId)
}
