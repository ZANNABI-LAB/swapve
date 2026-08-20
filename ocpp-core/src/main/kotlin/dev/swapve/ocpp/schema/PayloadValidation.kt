package dev.swapve.ocpp.schema

import dev.swapve.ocpp.rpc.RpcErrorCode

/**
 * The verdict of validating a payload against its schema.
 *
 * The validator **judges and decides policy, and stops there.** Whether a CALLERROR is actually
 * sent, and what gets recorded, belongs to the session layer. That is also why this is a
 * separate type from the framing layer's `DecodeOutcome` — that one answers "does this read as
 * a frame", this one answers "does the payload match its schema".
 */
sealed interface PayloadValidation {

    /** The payload satisfies the official schema. */
    data object Valid : PayloadValidation

    /**
     * Nothing to validate. `CALLERROR` / `CALLRESULTERROR` have no payload schema
     * (Part 4 §4.2.3).
     *
     * **Not a failure.** Callers must not treat it as one.
     */
    data object NotApplicable : PayloadValidation

    /**
     * The payload did not satisfy its schema. The caller may answer with a CALLERROR built from
     * [errorCode] / [errorDescription] (Part 4 §4.3).
     *
     * [violations] keeps **every violation found** — nothing is discarded. [errorCode] and
     * [errorDescription] are just the one chosen to answer with. When no schema existed to
     * validate against (an action we do not know), [schemaName] is the name that was asked for
     * and [violations] is empty.
     */
    data class Invalid(
        val schemaName: String,
        val errorCode: RpcErrorCode,
        val errorDescription: String,
        val violations: List<SchemaViolation>,
    ) : PayloadValidation
}

/**
 * A single schema violation.
 *
 * @param schemaName the official schema used, e.g. `BatterySwapRequest`
 * @param instancePath JSON pointer to where it happened, e.g. `$.batteryData[0].soC`
 * @param keyword the JSON Schema keyword violated, e.g. `required`, `type`, `enum`
 * @param message the human-readable explanation the validator produced
 */
data class SchemaViolation(
    val schemaName: String,
    val instancePath: String,
    val keyword: String,
    val message: String,
) {
    /** The code to answer with, considering this violation alone (Part 4 §4.3 Table 9). */
    val errorCode: RpcErrorCode get() = errorCodeOf(keyword)

    companion object {

        /**
         * JSON Schema keyword → RPC framework error code (Part 4 §4.3 Table 9).
         *
         * Split along what the table **means**:
         * - `OccurrenceConstraintViolation` — "syntactically correct, but violates a cardinality
         *   constraint". Missing required fields and array/object count limits land here.
         * - `TypeConstraintViolation` — "violates a data type constraint". Just `type`.
         * - `PropertyConstraintViolation` — "syntactically correct, but the value is invalid":
         *   values outside an enum, length and range limits, pattern mismatches.
         * - `FormatViolation` — structural violations that fit none of the above. Unrecognised
         *   keywords collect here too.
         */
        fun errorCodeOf(keyword: String): RpcErrorCode = when (keyword) {
            "required", "dependencies", "dependentRequired",
            "minItems", "maxItems", "minProperties", "maxProperties",
            -> RpcErrorCode.OccurrenceConstraintViolation

            "type" -> RpcErrorCode.TypeConstraintViolation

            "enum", "const", "pattern", "format",
            "minLength", "maxLength",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "uniqueItems", "contains",
            -> RpcErrorCode.PropertyConstraintViolation

            else -> RpcErrorCode.FormatViolation
        }

        /**
         * Severity rank used when picking the representative violation. Lower comes first.
         *
         * Structure has to hold before a type check means anything, and the type has to be right
         * before a value check does. Hence `Occurrence` → `Type` → `Property` → `Format` — the
         * order that answers with the violation closest to the cause.
         */
        internal fun severityOf(code: RpcErrorCode): Int = when (code) {
            RpcErrorCode.OccurrenceConstraintViolation -> 0
            RpcErrorCode.TypeConstraintViolation -> 1
            RpcErrorCode.PropertyConstraintViolation -> 2
            RpcErrorCode.FormatViolation -> 3
            else -> 4
        }
    }
}
