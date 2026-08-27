package dev.swapve.ocpp.schema

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import dev.swapve.ocpp.rpc.OcppFrame
import dev.swapve.ocpp.rpc.OcppFrameCodec
import dev.swapve.ocpp.rpc.RpcErrorCode
import java.util.concurrent.ConcurrentHashMap

/**
 * Validates payloads against the official OCPP 2.1 JSON Schemas and decides which RPC error code
 * to answer with when they fail (Part 4 §4.1.6, §4.2.4, §4.3).
 *
 * Schemas are read only as documents from the classpath, through [OcppSchemas] — never
 * transcribed into code (CC BY-ND 4.0 forbids derivatives). Compiled [JsonSchema] instances are
 * cached by name, so each schema is parsed once.
 *
 * No I/O, no session state. **It does not send the CALLERROR** — it judges and decides policy,
 * and transmission belongs to the session layer.
 *
 * Thread-safe, and **built to be shared as a single instance**: constructing one per session
 * would reparse all 181 schemas that many times.
 */
class OcppPayloadValidator {

    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V6)
    private val compiled = ConcurrentHashMap<String, JsonSchema>()

    /**
     * Validates a `CALL` payload. [action] is the frame's action, without a suffix
     * (Part 4 §4.1.6) — `"BatterySwap"` selects the `BatterySwapRequest` schema.
     */
    fun validateCall(action: String, payload: JsonNode): PayloadValidation =
        validateAgainst(OcppSchemaNames.forCall(action), payload)

    /**
     * Validates a `CALLRESULT` payload.
     *
     * **A CALLRESULT frame carries no action** (Part 4 §4.1.6). It is tied to its CALL by
     * messageId alone, so the caller has to look that CALL's action up in its own pending-CALL
     * table and pass it as [callAction] — `"RequestBatterySwap"` selects the
     * `RequestBatterySwapResponse` schema.
     */
    fun validateCallResult(callAction: String, payload: JsonNode): PayloadValidation =
        validateAgainst(OcppSchemaNames.forCallResult(callAction), payload)

    /** Validates a `SEND` payload. No suffix is added to the action (Part 4 §4.2.4). */
    fun validateSend(action: String, payload: JsonNode): PayloadValidation =
        validateAgainst(OcppSchemaNames.forSend(action), payload)

    /**
     * Validates a decoded frame.
     *
     * [OcppFrame.CallResult] needs [callAction] because the frame alone cannot select a schema.
     * Omitting it raises [IllegalArgumentException] — that is a programming error on the caller's
     * side, not a protocol error by the station, so it is not turned into a CALLERROR.
     *
     * `CALLERROR` / `CALLRESULTERROR` yield [PayloadValidation.NotApplicable].
     */
    fun validate(frame: OcppFrame, callAction: String? = null): PayloadValidation = when (frame) {
        is OcppFrame.Call -> validateCall(frame.action, frame.payload)
        is OcppFrame.Send -> validateSend(frame.action, frame.payload)
        is OcppFrame.CallResult -> {
            require(callAction != null) {
                "validating a CALLRESULT needs the originating CALL's action (Part 4 §4.1.6): messageId=${frame.messageId}"
            }
            validateCallResult(callAction, frame.payload)
        }

        is OcppFrame.CallError, is OcppFrame.CallResultError -> PayloadValidation.NotApplicable
    }

    /**
     * Validates against a schema named directly.
     *
     * An unknown name yields [RpcErrorCode.NotImplemented] — it means an action we do not know
     * (Part 4 §4.3, "Requested Action is not known by receiver").
     *
     * When there are several violations, picking the representative one is deterministic. See
     * [representativeOf].
     */
    fun validateAgainst(schemaName: String, payload: JsonNode): PayloadValidation {
        val schema = schemaOf(schemaName)
            ?: return PayloadValidation.Invalid(
                schemaName = schemaName,
                errorCode = RpcErrorCode.NotImplemented,
                errorDescription = truncate("unknown action: no schema named $schemaName"),
                violations = emptyList(),
            )

        val messages = schema.validate(payload)
        if (messages.isEmpty()) return PayloadValidation.Valid

        val violations = messages.map { it.toViolation(schemaName) }
        val representative = representativeOf(violations)

        return PayloadValidation.Invalid(
            schemaName = schemaName,
            errorCode = representative.errorCode,
            errorDescription = truncate("$schemaName ${representative.instancePath}: ${representative.message}"),
            violations = violations.sortedWith(VIOLATION_ORDER),
        )
    }

    /** Compiles and caches the schema if the name is on the classpath, `null` otherwise. */
    private fun schemaOf(name: String): JsonSchema? {
        compiled[name]?.let { return it }
        // Unknown names are deliberately not remembered. The name comes off the wire, so a
        // cache of the misses would grow with whatever a peer chooses to send; [OcppSchemas]
        // answers this in constant time, which is what makes remembering unnecessary.
        if (!OcppSchemas.contains(name)) return null
        return compiled.computeIfAbsent(name) { factory.getSchema(OcppSchemas.read(it)) }
    }

    private fun ValidationMessage.toViolation(schemaName: String) = SchemaViolation(
        schemaName = schemaName,
        instancePath = instanceLocation.toString(),
        keyword = keywordOf(evaluationPath.toString()),
        message = message,
    )

    companion object {

        /**
         * Picks the representative violation — **the same input must always give the same one**.
         * The validator returns violations as a set, in no particular order, and the CALLERROR
         * that goes back must not wobble between runs.
         *
         * Sorted by, and the first taken:
         * 1. **Severity** — `Occurrence` → `Type` → `Property` → `Format`. Structure has to hold
         *    before a type check means anything, and the type before a value check does.
         * 2. **instancePath**, lexicographically — at equal severity, the shallower and earlier
         *    field sits closer to the cause.
         * 3. **keyword**, lexicographically.
         * 4. **message**, lexicographically — the final tiebreak, so one always wins.
         */
        fun representativeOf(violations: List<SchemaViolation>): SchemaViolation =
            violations.minWithOrNull(VIOLATION_ORDER)
                ?: error("cannot pick a representative when there are no violations")

        private val VIOLATION_ORDER: Comparator<SchemaViolation> =
            compareBy({ SchemaViolation.severityOf(it.errorCode) }, { it.instancePath }, { it.keyword }, { it.message })

        /**
         * The last token of the validator's evaluationPath is the keyword that was violated.
         *
         * `$.properties.batteryData.items.$ref.properties.soC.type` → `type`,
         * `$.required` → `required`
         */
        private fun keywordOf(evaluationPath: String): String =
            evaluationPath.substringAfterLast('.').ifBlank { evaluationPath }

        /**
         * `errorDescription` is at most 255 characters (Part 4 §4.2.3 Table 7).
         *
         * Overlong text is elided so the truncation is visible, and the ellipsis is counted
         * against the limit rather than added on top of it.
         */
        internal fun truncate(description: String): String {
            val limit = OcppFrameCodec.MAX_ERROR_DESCRIPTION_LENGTH
            if (description.length <= limit) return description
            return description.take(limit - ELLIPSIS.length) + ELLIPSIS
        }

        private const val ELLIPSIS = "..."
    }
}
