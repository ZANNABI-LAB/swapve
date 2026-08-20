package dev.swapve.ocpp.session

import java.time.Instant

/** Which way a message travelled. */
enum class MessageDirection {
    /** Station → us. */
    INBOUND,

    /** Us → station. */
    OUTBOUND,
}

/**
 * An append-only record of one OCPP message that passed through.
 *
 * The one rule this log carries is that **derived state must be reconstructible from it**. So
 * [payload] holds the message **verbatim** rather than anything parsed out of it — fields we do
 * not understand today still have to be readable later.
 *
 * @param seq an increasing sequence **within a station**. Not comparable across stations —
 *   promising a global order over messages from separate connections is a promise that cannot
 *   be kept once this spreads across nodes.
 * @param action `BatterySwap`, `TransactionEvent`, … For a CALLRESULT or CALLERROR this is the
 *   action of the originating CALL, or `null` when no match was found — not knowing is a fact
 *   worth recording too.
 * @param messageId the OCPP-J uniqueId. When even the message type was unreadable this holds
 *   [dev.swapve.ocpp.rpc.OcppFrameCodec.UNREADABLE_MESSAGE_ID].
 * @param payload the **entire raw frame line**, not just the payload — `[2,"id","Action",{…}]`.
 */
data class OcppEventRecord(
    val seq: Long,
    val stationId: String,
    val direction: MessageDirection,
    val action: String?,
    val messageId: String,
    val payload: String,
    val occurredAt: Instant,
)

/**
 * Where events are written.
 *
 * This project does not add interfaces that have a single implementation, and this is the
 * deliberate exception: **the event log needs exactly one interception point**, and persistence
 * is a consumer's decision, not this module's. An in-memory implementation ships with it; a
 * durable one plugs in here.
 *
 * Assigning `seq` belongs to the implementation. Knowing a station's order is the log's job,
 * not the session's.
 */
fun interface OcppEventSink {

    /** Records the event and returns it with its sequence number assigned. */
    fun append(
        stationId: String,
        direction: MessageDirection,
        action: String?,
        messageId: String,
        payload: String,
        occurredAt: Instant,
    ): OcppEventRecord
}

/**
 * An in-memory event log.
 *
 * **Not durable, on purpose.** What this module has to establish is that derived state can be
 * reconstructed from the log at all, and that is independent of the storage medium — which is a
 * consumer's choice. Implement [OcppEventSink] to persist.
 *
 * Thread-safe: `seq` assignment and insertion happen in one critical section, so the numbering
 * never disagrees with the insertion order.
 */
class InMemoryOcppEventLog : OcppEventSink {

    private val lock = Any()
    private val byStation = LinkedHashMap<String, MutableList<OcppEventRecord>>()
    private val arrivalOrder = ArrayList<OcppEventRecord>()

    override fun append(
        stationId: String,
        direction: MessageDirection,
        action: String?,
        messageId: String,
        payload: String,
        occurredAt: Instant,
    ): OcppEventRecord = synchronized(lock) {
        val records = byStation.getOrPut(stationId) { ArrayList() }
        val record = OcppEventRecord(
            seq = records.size + 1L,
            stationId = stationId,
            direction = direction,
            action = action,
            messageId = messageId,
            payload = payload,
            occurredAt = occurredAt,
        )
        records += record
        arrivalOrder += record
        record
    }

    /** One station's records in `seq` order. Reconstruction always works on this unit. */
    fun of(stationId: String): List<OcppEventRecord> = synchronized(lock) {
        byStation[stationId].orEmpty().toList()
    }

    /** Every record in insertion order. There is no `seq` across stations, so that is all it is. */
    fun all(): List<OcppEventRecord> = synchronized(lock) { arrayListOf<OcppEventRecord>().apply { addAll(arrivalOrder) } }

    fun size(): Int = synchronized(lock) { arrivalOrder.size }
}
