package dev.swapve.ocpp.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Serialises work per station.
 *
 * ### The key is `stationId`, not the session object
 *
 * Locking on the session would make the lock meaningful only inside this JVM, and a reconnect
 * would hand the same station a different lock. Keying on `stationId` makes it a local lock
 * today and **a partition key** if this ever spreads across nodes — without touching a single
 * call site.
 *
 * That choice mirrors the protocol: OCPP scopes its uniqueness rules to the station identifier
 * and explicitly across connections (Part 4 §4.1.4), so anything bound to a connection is the
 * wrong unit here.
 *
 * ### Ordering
 *
 * [Mutex] is fair — waiters wake in arrival order. So calling [withStation] in arrival order
 * gives processing in arrival order. **Calling in order remains the caller's job**: this class
 * cannot unscramble calls that already arrived out of order.
 *
 * Different stations hold different mutexes, so a slow one does not block the others.
 *
 * ### Lifetime
 *
 * One mutex per station is kept and never reclaimed. Stations are finite (tens of them in
 * operation), and reclaiming would mean atomically confirming "nobody holds this right now" —
 * contention that costs more than the object it would free.
 */
class StationSerializer {

    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /** Waits for [stationId]'s turn, then runs [block]. */
    suspend fun <T> withStation(stationId: String, block: suspend () -> T): T =
        mutexOf(stationId).withLock { block() }

    /** Whether that station's turn is currently held. For tests and diagnostics. */
    fun isBusy(stationId: String): Boolean = mutexes[stationId]?.isLocked == true

    private fun mutexOf(stationId: String): Mutex = mutexes.computeIfAbsent(stationId) { Mutex() }
}
