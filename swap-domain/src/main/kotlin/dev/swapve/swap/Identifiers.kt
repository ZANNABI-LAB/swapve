package dev.swapve.swap

/**
 * A station identifier.
 *
 * Absent from OCPP frames — it is a property of the connection, settled at the WebSocket
 * handshake. The domain **must** have it: a swap correlation key is only unique within a station.
 */
@JvmInline
value class StationId(val value: String) {
    init {
        require(value.isNotBlank()) { "stationId is blank" }
    }

    override fun toString(): String = value
}

/**
 * The operator that owns a station.
 *
 * Kept even while there is only ever one value. Baking a single operator into the code as an
 * assumption would mean migrating every row the day roaming (OCPI) arrives.
 */
@JvmInline
value class OperatorId(val value: String) {
    init {
        require(value.isNotBlank()) { "operatorId is blank" }
    }

    override fun toString(): String = value
}

/**
 * A slot identifier. One slot corresponds to one EVSE in the protocol.
 *
 * The protocol field is a non-negative integer; the boundary layer converts it into this type.
 */
@JvmInline
value class SlotId(val value: Int) {
    init {
        require(value >= 0) { "slot number must be non-negative: $value" }
    }

    override fun toString(): String = value.toString()
}

/**
 * The correlation number of one swap.
 *
 * **It does not identify a swap on its own.** The standard requires no global uniqueness of it,
 * so it is unique only within a station. Wherever a correlation key is needed, use [SwapKey].
 */
@JvmInline
value class SwapRequestId(val value: Int) {
    override fun toString(): String = value.toString()
}

/**
 * The correlation key of one swap — the composite `(station, correlation number)`.
 *
 * Two stations reusing the same [SwapRequestId] do not collide.
 */
data class SwapKey(
    val stationId: StationId,
    val requestId: SwapRequestId,
) {
    override fun toString(): String = "$stationId/$requestId"
}
