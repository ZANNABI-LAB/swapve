package dev.swapve.ocpp.json

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The one place that renders the `"format": "date-time"` fields of the official schemas.
 *
 * **Never reads the current time** — it takes an [Instant] and renders it. Time always comes
 * from an injected `Clock`, which is what lets callers pin it and get deterministic tests.
 *
 * The fractional second is always three digits. Nanosecond precision carries no protocol
 * meaning, and a sender that emits `...:00Z` on one message and `...:00.123Z` on the next is
 * a common source of breakage in peer parsers — even though RFC 3339 permits both.
 */
object OcppDateTime {

    private val FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Renders [at] as a schema `date-time` string in UTC, with a three-digit fraction. */
    fun format(at: Instant): String = FORMAT.format(at)

    /**
     * Reads a received `date-time` string, or returns `null` when it cannot be read.
     *
     * Does not throw. A payload can satisfy `format: date-time` in the schema and still carry
     * an offset or fraction this parser disagrees with, and that is no reason to fail the whole
     * message — the raw text stays in the event log either way, so nothing is lost. Callers may
     * fall back to their own clock for the field.
     */
    fun parse(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
}
