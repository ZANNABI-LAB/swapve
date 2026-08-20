package dev.swapve.ocpp.schema

/**
 * Classpath access to the official OCA OCPP 2.1 JSON Schema documents.
 *
 * The schemas are never transcribed into code — they are read verbatim from the classpath.
 * Transcribing them would start drifting from the standard, and CC BY-ND 4.0 forbids
 * derivatives regardless. Moving to a new revision of the standard means replacing the `.json`
 * files and nothing else.
 *
 * Names are the OCPP action plus `Request`/`Response`, e.g. `BatterySwapRequest`.
 */
object OcppSchemas {

    private const val ROOT = "dev/swapve/ocpp/schemas"
    private const val INDEX = "$ROOT/_index.txt"
    private const val VERSION = "$ROOT/_version.txt"

    /** Every schema name on the classpath, sorted. */
    val names: List<String> by lazy {
        val index = resource(INDEX)
            ?: error("schema index not found: $INDEX — check the syncOcppSchemas task of ocpp-core")
        index.use { it.reader().readLines().filter(String::isNotBlank) }
    }

    // [contains] is on the hot path — every inbound message asks it, including the unknown
    // actions a peer may send repeatedly. A linear scan of [names] there would push callers
    // into caching the misses, and a cache of arbitrary peer-supplied strings has no bound.
    private val nameSet: Set<String> by lazy { names.toSet() }

    /**
     * The revision these schemas state they are, verbatim from their own `comment` field —
     * `"OCPP 2.1 Edition 1 (c) OCA, Creative Commons Attribution-NoDerivatives 4.0 …"`.
     *
     * Read from the documents rather than written by hand, so replacing `schemas/` carries the
     * revision with it. The build refuses to package a mixed set.
     */
    val version: String by lazy {
        val text = resource(VERSION)
            ?: error("schema version not found: $VERSION — check the syncOcppSchemas task of ocpp-core")
        text.use { it.reader().readText().trim() }
    }

    /** The schema document. Throws [IllegalArgumentException] if there is no such name. */
    fun read(name: String): String {
        val stream = resource("$ROOT/$name.json")
            ?: throw IllegalArgumentException("unknown OCPP schema: $name")
        return stream.use { it.readBytes().decodeToString() }
    }

    fun contains(name: String): Boolean = name in nameSet

    private fun resource(path: String) = OcppSchemas::class.java.classLoader.getResourceAsStream(path)
}
