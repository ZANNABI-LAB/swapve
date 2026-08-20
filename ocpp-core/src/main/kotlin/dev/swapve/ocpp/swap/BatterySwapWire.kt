package dev.swapve.ocpp.swap

/**
 * The **vocabulary on the wire** that battery swapping actually uses — action names and enum
 * values.
 *
 * ### Why collect them as constants
 *
 * Part 6 Tool validation demands **exact strings**: `trigger=Delta`,
 * `component.name="Connector"`, `variable.name="AvailabilityState"`,
 * `triggerReason=CablePluggedIn`. If a simulator and a CSMS each spell those out as literals, a
 * test only confirms that literal A equals literal B — never that either matches the contract.
 * Pointing both at the same constant turns the test into **a comparison against the contract**,
 * and leaves one place to edit when the standard moves.
 *
 * This does not stand in for the schemas. Whether a payload is correct is always decided by the
 * official schema; what lives here is only **the subset we chose** out of what those schemas
 * allow.
 *
 * Slot availability alone is pulled out into [AvailabilityState] — that one is not a value but
 * **an inverted meaning**.
 */
object BatterySwapWire {

    const val BOOT_NOTIFICATION = "BootNotification"
    const val HEARTBEAT = "Heartbeat"
    const val AUTHORIZE = "Authorize"
    const val NOTIFY_EVENT = "NotifyEvent"
    const val SECURITY_EVENT_NOTIFICATION = "SecurityEventNotification"
    const val TRANSACTION_EVENT = "TransactionEvent"
    const val BATTERY_SWAP = "BatterySwap"
    const val REQUEST_BATTERY_SWAP = "RequestBatterySwap"

    /** Reading and writing the device model. For the identifiers see [DeviceModelVariables]. */
    const val GET_VARIABLES = "GetVariables"
    const val SET_VARIABLES = "SetVariables"

    /**
     * Reporting the **full inventory** of the device model (`TC_S_104_CS`).
     *
     * `GetVariables` is for when the CSMS **already knows what to ask for**. A full listing
     * cannot be obtained that way — you cannot ask for variables you do not know. So the
     * direction reverses: the CSMS requests [GET_BASE_REPORT] and the station answers with
     * [NOTIFY_REPORT], **split across several messages**.
     */
    const val GET_BASE_REPORT = "GetBaseReport"
    const val NOTIFY_REPORT = "NotifyReport"

    /** A depleted battery **came in**. */
    const val BATTERY_IN = "BatteryIn"

    /** A fresh battery **went out**. */
    const val BATTERY_OUT = "BatteryOut"

    /** The offered battery was never collected (S03.FR.06). */
    const val BATTERY_OUT_TIMEOUT = "BatteryOutTimeout"

    /**
     * The component that carries slot state. One slot is one EVSE, and the connector inside it
     * reports the state.
     */
    const val COMPONENT_CONNECTOR = "Connector"

    /** The slot occupancy variable. For what the values mean see [AvailabilityState] — they invert. */
    const val VARIABLE_AVAILABILITY_STATE = "AvailabilityState"

    /** `EventTriggerEnumType` — sent because the state **changed**. */
    const val TRIGGER_DELTA = "Delta"

    /** `EventNotificationEnumType` — hard-wired into the device, not created by a monitoring setting. */
    const val NOTIFICATION_HARD_WIRED = "HardWiredNotification"

    /** The security event reported right after boot (Part 6 `BootedBatterySwapping`). */
    const val SECURITY_EVENT_STARTUP = "StartupOfTheDevice"

    /** A restart or reboot. Part 6 accepts either this or [SECURITY_EVENT_STARTUP]. */
    const val SECURITY_EVENT_RESET_OR_REBOOT = "ResetOrReboot"

    const val BOOT_REASON_POWER_UP = "PowerUp"

    /** The station restarted itself. The **S04.FR.11** reboot path arrives with this reason. */
    const val BOOT_REASON_LOCAL_RESET = "LocalReset"

    /** `TransactionEventEnumType` */
    const val TX_STARTED = "Started"
    const val TX_UPDATED = "Updated"
    const val TX_ENDED = "Ended"

    /** `TriggerReasonEnumType` — a battery was inserted. `TxStartPoint = EVConnected` (S04.FR.08). */
    const val TRIGGER_REASON_CABLE_PLUGGED_IN = "CablePluggedIn"

    /** `TriggerReasonEnumType` — the charging state changed. */
    const val TRIGGER_REASON_CHARGING_STATE_CHANGED = "ChargingStateChanged"

    /**
     * `TriggerReasonEnumType` — a charging limit was reached.
     *
     * ### ★ This is also the triggerReason when removing a battery ends the transaction
     *
     * That is what Part 6 `TC_S_103_CSMS` steps 13 and 17 say. An earlier design **guessed**
     * `EVCommunicationLost` here and was corrected against the text; the guessed constant was
     * deleted outright, because a constant left lying around gets used again.
     *
     * Ending a transaction demands three values **separately**: why it ended (`triggerReason`),
     * what disconnected ([STOPPED_REASON_EV_DISCONNECTED]), and the state afterwards
     * ([CHARGING_STATE_IDLE]). All three are valid enum members on their own, so **schema
     * validation cannot catch a wrong one.**
     */
    const val TRIGGER_REASON_ENERGY_LIMIT_REACHED = "EnergyLimitReached"

    /**
     * `TriggerReasonEnumType` — a periodic meter report (S04.FR.04).
     *
     * The occasion for the `TransactionEvent(Updated)` that reports SoC while charging. Nothing
     * changed state, so [TRIGGER_REASON_CHARGING_STATE_CHANGED] would be a false statement here
     * — `chargingState` is still `Charging`.
     */
    const val TRIGGER_REASON_METER_VALUE_PERIODIC = "MeterValuePeriodic"

    /** `ChargingStateEnumType` */
    const val CHARGING_STATE_EV_CONNECTED = "EVConnected"
    const val CHARGING_STATE_CHARGING = "Charging"

    /**
     * `ChargingStateEnumType` — **the station stopped supplying power on reaching the charging
     * ceiling (`MaxSoc`)** (S04.FR.06).
     *
     * ### The transaction does not end here
     *
     * What stopped is **the energy flow**, not the transaction. The battery is still in the slot
     * (`TxStopPoint = EVConnected`, S04.FR.09) and the transaction only closes as `Ended` when
     * someone takes that battery out. Closing it here would leave nothing to close on removal,
     * and the ledger would end in mid-air.
     *
     * It is the EVSE that stopped, so this rather than `SuspendedEV` — the battery did not
     * refuse anything.
     */
    const val CHARGING_STATE_SUSPENDED_EVSE = "SuspendedEVSE"

    /** `MeasurandEnumType` — battery state of charge, the value periodic reports carry. */
    const val MEASURAND_SOC = "SoC"

    /**
     * `SampledValue.unitOfMeasure.unit` — percent.
     *
     * The schema leaves this field a plain string rather than an enum, saying only *"SHALL use a
     * value from the list Standardized Units of Measurements in Part 2 Appendices"*. A typo
     * therefore passes schema validation untouched — which is exactly why it is a constant.
     */
    const val UNIT_PERCENT = "Percent"

    /** `ReadingContextEnumType` — a periodic sample. Same as the schema default, but stated rather than assumed. */
    const val READING_CONTEXT_SAMPLE_PERIODIC = "Sample.Periodic"

    /** `ChargingStateEnumType` — the state after a transaction ends (Part 6 `TC_S_103_CSMS` steps 13/17). */
    const val CHARGING_STATE_IDLE = "Idle"

    /** `ReasonEnumType` — the transaction ended because the battery was removed. */
    const val STOPPED_REASON_EV_DISCONNECTED = "EVDisconnected"

    /**
     * The kind of substitute token a charging transaction uses (S04.FR.02/03).
     *
     * Charging at a swap station is not started by a person presenting a card. Either the
     * configured `BatterySwapCtrlr.IdToken` travels as `Central`, or
     * [ID_TOKEN_TYPE_NO_AUTHORIZATION] says there is no token at all.
     */
    const val ID_TOKEN_TYPE_CENTRAL = "Central"

    /** An unauthorized transaction. `idToken.idToken` is then **the empty string** (Part 6 Tool validation). */
    const val ID_TOKEN_TYPE_NO_AUTHORIZATION = "NoAuthorization"

    /** `RegistrationStatusEnumType` */
    const val REGISTRATION_ACCEPTED = "Accepted"

    /** `AuthorizationStatusEnumType` */
    const val AUTHORIZATION_ACCEPTED = "Accepted"

    /** `GenericStatusEnumType` — the status of `RequestBatterySwapResponse` (S02). */
    const val GENERIC_ACCEPTED = "Accepted"
    const val GENERIC_REJECTED = "Rejected"

    /** `ReportBaseEnumType` — **the whole** device model. The value `TC_S_104_CS` requires. */
    const val REPORT_BASE_FULL_INVENTORY = "FullInventory"

    /** `GenericDeviceModelStatusEnumType` — the status of `GetBaseReportResponse`. */
    const val DEVICE_MODEL_ACCEPTED = "Accepted"

    /** A kind of report we cannot produce was requested. Distinct from `Rejected`, which means "will not". */
    const val DEVICE_MODEL_NOT_SUPPORTED = "NotSupported"

    /**
     * `AttributeEnumType` — **the value actually held right now**.
     *
     * One variable can carry up to four attributes, including `Target`, `MinSet` and `MaxSet`. A
     * receiver that reads whichever attribute comes first would mistake a target for a current
     * reading, so both reporting and selective reading go through this constant.
     */
    const val ATTRIBUTE_ACTUAL = "Actual"

    /** `MutabilityEnumType` — observed, never set (`BatteryCartridge`, `SwapOrder`). */
    const val MUTABILITY_READ_ONLY = "ReadOnly"

    /** `MutabilityEnumType` — changeable through `SetVariables`. */
    const val MUTABILITY_READ_WRITE = "ReadWrite"

    /** `DataEnumType` — `VariableCharacteristics.dataType`, a required field. */
    const val DATA_TYPE_INTEGER = "integer"
    const val DATA_TYPE_DECIMAL = "decimal"
    const val DATA_TYPE_STRING = "string"
    const val DATA_TYPE_BOOLEAN = "boolean"

    /** The value is one of a fixed list. `valuesList` is then **required** (official schema). */
    const val DATA_TYPE_OPTION_LIST = "OptionList"

    /** `VariableCharacteristics.unit` — seconds. The same position as [UNIT_PERCENT]. */
    const val UNIT_SECONDS = "seconds"

    /**
     * The vendor identifier used to refuse a battery through `BatterySwapResponse`
     * (Part 2 S03 Error handling).
     *
     * `BatterySwapResponse` is *"Empty response by CSMS to confirm receipt"* and **cannot carry
     * a refusal**. OCA defined an official way around that limit: the response carries
     * `customData` holding `status` and `statusInfo`. The response is still an acknowledgement
     * in nature, so it is **not** turned into a CALLERROR.
     *
     * Support is advertised in the device model as
     * `CustomizationCtrlr.CustomImplementationEnabled[this vendorId] = true`.
     */
    const val VENDOR_ID_BATTERY_SWAP_RESPONSE = "org.openchargealliance.batteryswapresponse"
}

/**
 * Reason codes for refusing a battery — the appendix `reason_codes.csv` is authoritative.
 *
 * These six are **predefined by the standard**, not invented here. Scattered as string literals
 * a typo would pass silently: `statusInfo.reasonCode` is only checked against `maxLength`, and
 * the list of valid values is nowhere in the schema.
 *
 * @param wireValue the value on the wire. Renaming an enum constant does not disturb it.
 * @param appliesToRequestBatterySwap whether it may also appear in
 *   `RequestBatterySwapResponse.statusInfo`. In the appendix table only [NO_BATTERY_AVAILABLE]
 *   appears there (S02.FR.04).
 */
enum class BatteryRejectionReason(
    val wireValue: String,
    val appliesToRequestBatterySwap: Boolean = false,
) {

    /** State of health is too low. */
    BATTERY_SOH_LOW("BatterySoHLow"),

    /** The state of charge is unsuitable. */
    BATTERY_SOC("BatterySoC"),

    /** The battery is damaged. */
    BATTERY_DAMAGED("BatteryDamaged"),

    /** A serial number we do not know. */
    BATTERY_UNKNOWN("BatteryUnknown"),

    /** A battery type that is not allowed. */
    BATTERY_TYPE("BatteryType"),

    /**
     * There is no battery to hand out (S02.FR.04).
     *
     * **Stock is judged by the station.** A CSMS never produces this code — it receives it and
     * records it. Part 6 `TC_S_102_CSMS` is that scenario.
     */
    NO_BATTERY_AVAILABLE("NoBatteryAvailable", appliesToRequestBatterySwap = true);

    companion object {
        /** Looks one up by its wire value, ignoring case — *"The string is case-insensitive"* (schema). */
        fun ofWire(value: String?): BatteryRejectionReason? =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) }
    }
}
