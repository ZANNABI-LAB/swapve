package dev.swapve.ocpp.session

import java.util.concurrent.ConcurrentHashMap

/**
 * The only way to command a station.
 *
 * ### Why this one is an interface
 *
 * This project does not add interfaces that have a single implementation. This one is not an
 * abstraction kept for a hypothetical future — it is **a rule about today: session objects do
 * not leak upward.** Once the layer above starts calling `session.send(...)`, the assumption
 * that the session lives in this JVM spreads through the codebase, and that assumption is hard
 * to take back.
 *
 * The layer above always addresses a `stationId`. That is the local lock key now, and the
 * partition key later.
 */
interface StationCommandBus {

    /**
     * Sends a CALL to [stationId] and returns how it ended.
     *
     * **Never throws.** No connection is a result too ([OcppResult.NotConnected]).
     */
    suspend fun send(stationId: String, call: OcppCall): OcppResult
}

/**
 * The connected sessions.
 *
 * [sessionOf] being `internal` is the point — **session objects never leave this module**. What
 * the layer above can reach is [StationCommandBus] and [isConnected], nothing else.
 *
 * Thread-safe.
 */
class SessionRegistry {

    private val sessions = ConcurrentHashMap<String, OcppSession>()

    /**
     * A connection was established. Replaces any earlier session for that station and returns it.
     *
     * Closing the replaced session is the caller's job — this class knows nothing about transport.
     */
    fun register(session: OcppSession): OcppSession? = sessions.put(session.stationId, session)

    /**
     * A connection ended. Removes it **only if that session is still the registered one**.
     *
     * On a fast reconnect the new session is already registered when the old connection's
     * teardown arrives. Removing unconditionally would drop the live one.
     */
    fun unregister(session: OcppSession): Boolean = sessions.remove(session.stationId, session)

    fun isConnected(stationId: String): Boolean = sessions.containsKey(stationId)

    val connectedStationIds: Set<String> get() = sessions.keys.toSet()

    internal fun sessionOf(stationId: String): OcppSession? = sessions[stationId]
}

/**
 * Looks the station up in the local registry and sends. That is all it does.
 *
 * No Redis, no message bus, no cluster membership. **If distribution is ever needed, this class
 * is the only one that changes** — not a single call site.
 */
class LocalStationCommandBus(private val registry: SessionRegistry) : StationCommandBus {

    override suspend fun send(stationId: String, call: OcppCall): OcppResult =
        registry.sessionOf(stationId)?.call(call) ?: OcppResult.NotConnected(stationId)
}
