package dev.swapve.ocpp.schema

import dev.swapve.ocpp.rpc.MessageType

/**
 * Maps an OCPP action to the name of its official schema file (Part 4 §4.1.6, §4.2.4).
 *
 * The naming rule differs per message type. **We are the ones who add the suffix** — what
 * arrives on the frame is the action without one.
 *
 * | Type | Rule | Example |
 * |---|---|---|
 * | `CALL` (2) | `action + "Request"` | `"BatterySwap"` → `BatterySwapRequest` |
 * | `CALLRESULT` (3) | `action + "Response"` | `"BatterySwap"` → `BatterySwapResponse` |
 * | `SEND` (6) | `action` as-is | `"NotifyPeriodicEventStream"` |
 * | `CALLERROR` (4) / `CALLRESULTERROR` (5) | no payload schema | — |
 *
 * SEND messages take no suffix because their names never end in `Request`/`Response` to begin
 * with (Part 4 §4.2.4).
 */
internal object OcppSchemaNames {

    /** The schema a `CALL` payload must satisfy (Part 4 §4.1.6). */
    fun forCall(action: String): String = action + REQUEST_SUFFIX

    /**
     * The schema a `CALLRESULT` payload must satisfy (Part 4 §4.1.6).
     *
     * **A CALLRESULT frame carries no action.** It is tied to its CALL by messageId alone, so
     * the caller has to look [callAction] up in its own pending-CALL table and pass it in.
     */
    fun forCallResult(callAction: String): String = callAction + RESPONSE_SUFFIX

    /** The schema a `SEND` payload must satisfy. No suffix is added (Part 4 §4.2.4). */
    fun forSend(action: String): String = action

    /**
     * The schema name for a message type, or `null` for the types that carry no payload schema
     * (`CALLERROR` / `CALLRESULTERROR`).
     *
     * For `CALLRESULT`, [action] is the action of the **originating CALL**.
     */
    fun forMessage(type: MessageType, action: String): String? = when (type) {
        MessageType.CALL -> forCall(action)
        MessageType.CALL_RESULT -> forCallResult(action)
        MessageType.SEND -> forSend(action)
        MessageType.CALL_ERROR, MessageType.CALL_RESULT_ERROR -> null
    }

    private const val REQUEST_SUFFIX = "Request"
    private const val RESPONSE_SUFFIX = "Response"
}
