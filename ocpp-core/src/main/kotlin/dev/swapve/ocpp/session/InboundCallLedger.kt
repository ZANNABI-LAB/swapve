package dev.swapve.ocpp.session

/**
 * Identifies one received CALL.
 *
 * **Keyed by station, not by connection.** A messageId must differ from every value that sender
 * has used toward the same station identifier, and a new connection does not reset that
 * (Part 4 §4.1.4). Put the connection in the key and a retransmission after reconnect reads as
 * a brand-new message — which, for a battery swap, double-counts the ledger.
 */
data class InboundCallKey(
    val stationId: String,
    val messageId: String,
)

/** What [InboundCallLedger.claim] decided. */
sealed interface CallClaim {

    /** Not seen before. Go ahead and process it. */
    data object Fresh : CallClaim

    /**
     * Already processed. **Do not call the layer above again** — send [responseText] as it is.
     * Side effects happen exactly once.
     */
    data class AlreadyAnswered(val responseText: String) : CallClaim

    /**
     * Still being processed. Answer with a CALLERROR — *"already processing a message with this
     * unique identifier"* is a CALLERROR reason the spec names explicitly (Part 4 §4.2.3).
     *
     * Waiting and handing back the same answer would also be defensible, but a retransmission
     * means the peer has already timed out, and there is no reason to hold that connection any
     * longer.
     */
    data object InFlight : CallClaim
}

/**
 * Idempotency ledger for received CALLs.
 *
 * **It outlives the session.** A session lasts one connection; this ledger follows the station.
 * The situation it exists for is exactly a WebSocket dropping, reconnecting, and the station
 * retransmitting its `BatterySwap` CALL — and if idempotency breaks there, the battery ledger
 * is counted twice.
 *
 * ### Bound
 *
 * Memory cannot grow without limit. Past [maxEntries], the **least recently used** entry is
 * dropped.
 *
 * Why [DEFAULT_MAX_ENTRIES] is 10,000:
 * - The load target is 20 stations, and a single swap exchanges around 20 OCPP messages. So
 *   20 × 25 swaps × 20 messages ≈ 10,000 covers **every station running 25 concurrent swaps**.
 * - Retransmissions only follow a timeout on the peer (Part 4 §4.1.1). An entry evicted long
 *   after that window was never a retransmission candidate to begin with.
 * - An entry is a key and a response string, so 10,000 of them stay within a few MB.
 *
 * Thread-safe.
 */
class InboundCallLedger(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    init {
        require(maxEntries > 0) { "the ledger bound must be at least 1: $maxEntries" }
    }

    private sealed interface Entry {
        data object InProgress : Entry
        data class Answered(val responseText: String) : Entry
    }

    private val lock = Any()

    private val entries = object : LinkedHashMap<InboundCallKey, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InboundCallKey, Entry>): Boolean =
            size > maxEntries
    }

    /**
     * Decides whether this message may be processed now, marking it in progress if it is new.
     *
     * Deciding and marking happen in one critical section. Split apart, two copies of the same
     * messageId could both come back [CallClaim.Fresh] and the layer above would run twice.
     */
    fun claim(key: InboundCallKey): CallClaim = synchronized(lock) {
        when (val existing = entries[key]) {
            null -> {
                entries[key] = Entry.InProgress
                CallClaim.Fresh
            }

            is Entry.InProgress -> CallClaim.InFlight
            is Entry.Answered -> CallClaim.AlreadyAnswered(existing.responseText)
        }
    }

    /**
     * Records the **raw frame** that was sent in answer.
     *
     * The encoded line is kept whole rather than the payload, so that a retransmission resends
     * it verbatim instead of rebuilding it — **the second answer is then byte-for-byte the
     * first**. Rebuilding could differ if the code changed in between.
     */
    fun complete(key: InboundCallKey, responseText: String) = synchronized(lock) {
        entries[key] = Entry.Answered(responseText)
    }

    /**
     * Forgets the attempt. Used when the layer above ended in an exception.
     *
     * Without this the messageId would stay [CallClaim.InFlight] forever and block every retry.
     * A failed attempt has to be retriable by retransmission.
     */
    fun release(key: InboundCallKey) = synchronized(lock) {
        entries.remove(key)
        Unit
    }

    /** How many messages are remembered. For confirming the bound actually applies. */
    fun size(): Int = synchronized(lock) { entries.size }

    companion object {
        /** The reasoning is in the "Bound" section of the class documentation. */
        const val DEFAULT_MAX_ENTRIES = 10_000

        private const val INITIAL_CAPACITY = 256
        private const val LOAD_FACTOR = 0.75f
    }
}
