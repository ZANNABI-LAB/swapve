package dev.swapve.csms.audit

import java.io.File

/**
 * ★★ **Strings that leave on the wire are English** (audit item, gate L1).
 *
 * ### What this audits and why it is narrow
 *
 * A CALLERROR `errorDescription` and a `statusInfo.additionalInfo` are **read by the other
 * side** — a third-party CSMS or charging station that has no reason to read Korean. Those two
 * are the only free-text fields OCPP 2.1 gives us on the wire, and they are exactly the fields
 * an integrator quotes back when something goes wrong. `BatteryRegistry.additionalInfo`
 * (`"Not a battery of this CPO: …"`) already set that precedent; this audit makes the precedent
 * a rule instead of a habit.
 *
 * Everything else in this repository stays Korean on purpose. KDoc is the project's own prose,
 * `//` comments explain decisions to whoever reads the source, and `log.*` / `error()` /
 * `require()` messages are read by the operator running *this* server — none of them cross the
 * wire. So this audit does **not** forbid Hangul in a file. It forbids Hangul in the argument
 * region of a **wire sink**: the small set of call sites and property assignments whose value is
 * serialised into an outbound frame.
 *
 * ### How the region is cut
 *
 * Comments are removed first (a Korean KDoc sitting above `additionalInfo =` must not be
 * mistaken for its argument), then each anchor in [ANCHORS] is located and the text is consumed
 * until the brackets balance or an argument separator is reached at depth 0. Only string
 * literals starting inside that region are inspected.
 *
 * ### What it cannot see
 *
 * - **Indirect strings.** A value produced by a helper (`SimDeviceModel.unknownVariableHint`) or
 *   held in a constant is assembled outside the region, so the audit reads the call, not the
 *   text. Those were translated by hand and stay correct only by review.
 * - **Strings assembled by the caller.** `additionalInfo = reason` says nothing about `reason`.
 * - **New sinks.** A sink that is not in [ANCHORS] is invisible. `checked` is reported so that a
 *   rename which silently drops every anchor fails instead of passing — see [AuditItem].
 * - **Hangul is the only alphabet checked.** This is a rule about *this* repository's habit of
 *   writing Korean, not a general "is it English" test.
 */
class WireLanguageAudit(private val sourceRoots: List<File>) {

    fun run(): AuditItem {
        val files = sourceRoots
            .filter { it.exists() }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .sortedBy { it.path }

        var checked = 0
        val violations = mutableListOf<String>()

        files.forEach { file ->
            val source = Source.of(file.readText())
            source.wireRegions().forEach { region ->
                checked++
                source.literalsIn(region)
                    .filter { it.text.any(::isHangul) }
                    .forEach { literal ->
                        violations += "${file.name}:${literal.line} — ${literal.text.trim()}"
                    }
            }
        }

        return AuditItem(
            name = "와이어 문자열은 영문이다",
            basis = "Part 4 §4.2.3",
            checked = checked,
            unit = "곳",
            violations = violations.distinct(),
        )
    }

    /**
     * A parsed Kotlin source: comments blanked out, string literals located.
     *
     * Two views are kept at **identical indices**. [stripped] keeps literal text so an anchor
     * such as `put("additionalInfo"` can be found by plain search; [skeleton] replaces the
     * inside of every literal with a filler character so that a bracket or comma written inside
     * a string cannot throw off the region scan.
     */
    private class Source(
        private val stripped: String,
        private val skeleton: String,
        private val literals: List<Literal>,
    ) {

        fun literalsIn(region: IntRange): List<Literal> =
            literals.filter { it.start in region }

        /** The argument region of every wire sink in this file, in source order. */
        fun wireRegions(): List<IntRange> = buildList {
            ANCHORS.forEach { anchor ->
                var from = stripped.indexOf(anchor)
                while (from >= 0) {
                    regionAt(from, from + anchor.length)?.let { add(it) }
                    from = stripped.indexOf(anchor, from + anchor.length)
                }
            }
        }

        /**
         * Consume from the end of the anchor until the argument list is over.
         *
         * An anchor that opens a bracket (`InboundResponse.Fail(`) ends when that bracket
         * closes. An anchor that does not (`additionalInfo =`) ends at the first `,` or closing
         * bracket at depth 0 — not at the newline, because a value may be written on the line
         * below the `=` or continued with `+`.
         */
        private fun regionAt(start: Int, afterAnchor: Int): IntRange? {
            var depth = skeleton.substring(start, afterAnchor).fold(0) { acc, c -> acc + depthDelta(c) }
            val opened = depth > 0

            var i = afterAnchor
            while (i < skeleton.length) {
                val c = skeleton[i]
                when {
                    c in OPENERS -> depth++
                    c in CLOSERS -> {
                        if (depth == 0) return start..i
                        depth--
                        if (opened && depth == 0) return start..i
                    }

                    c == ',' && depth == 0 -> return start..i
                }
                i++
            }
            // Unbalanced source — the compiler will say so. Nothing to audit here.
            return null
        }

        companion object {

            /**
             * Anchors are matched as plain text, so a definition (`private fun callError(`)
             * matches too. That is harmless: a parameter list holds no string literals.
             */
            private val ANCHORS = listOf(
                "InboundResponse.Fail(",
                "OcppFrame.CallError(",
                "callError(",
                "DecodeOutcome.Malformed(",
                "errorDescription =",
                "additionalInfo =",
                "put(\"additionalInfo\"",
            )

            private const val OPENERS = "([{"
            private const val CLOSERS = ")]}"

            private fun depthDelta(c: Char): Int = when (c) {
                in OPENERS -> 1
                in CLOSERS -> -1
                else -> 0
            }

            /**
             * One pass: line and block comments become spaces, literals are recorded.
             *
             * Newlines are preserved everywhere so that a literal's line number is still the
             * line it occupies in the file on disk.
             */
            fun of(source: String): Source {
                val stripped = CharArray(source.length)
                val skeleton = CharArray(source.length)
                val literals = mutableListOf<Literal>()
                var line = 1
                var i = 0

                fun blank(count: Int) {
                    repeat(count) {
                        val c = source[i]
                        if (c == '\n') line++
                        stripped[i] = if (c == '\n') '\n' else ' '
                        skeleton[i] = stripped[i]
                        i++
                    }
                }

                fun keep(count: Int, fillerFrom: Int = Int.MAX_VALUE, fillerTo: Int = -1) {
                    repeat(count) {
                        val c = source[i]
                        if (c == '\n') line++
                        stripped[i] = c
                        skeleton[i] = if (i in fillerFrom..fillerTo && c != '\n') 'x' else c
                        i++
                    }
                }

                while (i < source.length) {
                    when {
                        source.startsWith("//", i) -> {
                            val end = source.indexOf('\n', i).takeIf { it >= 0 } ?: source.length
                            blank(end - i)
                        }

                        source.startsWith("/*", i) -> blank(blockCommentLength(source, i))

                        source.startsWith("\"\"\"", i) -> {
                            val length = rawStringLength(source, i)
                            literals += Literal(i, line, source.substring(i + 3, i + length - 3))
                            keep(length, i + 3, i + length - 4)
                        }

                        source[i] == '"' -> {
                            val length = stringLength(source, i)
                            literals += Literal(i, line, source.substring(i + 1, i + length - 1))
                            keep(length, i + 1, i + length - 2)
                        }

                        source[i] == '\'' -> keep(charLiteralLength(source, i))

                        else -> keep(1)
                    }
                }

                return Source(String(stripped), String(skeleton), literals)
            }

            /** Block comments nest in Kotlin, so depth is counted rather than searched for. */
            private fun blockCommentLength(source: String, start: Int): Int {
                var depth = 0
                var i = start
                while (i < source.length) {
                    when {
                        source.startsWith("/*", i) -> { depth++; i += 2 }
                        source.startsWith("*/", i) -> { depth--; i += 2; if (depth == 0) return i - start }
                        else -> i++
                    }
                }
                return source.length - start
            }

            private fun rawStringLength(source: String, start: Int): Int {
                val end = source.indexOf("\"\"\"", start + 3)
                return if (end < 0) source.length - start else end + 3 - start
            }

            private fun stringLength(source: String, start: Int): Int {
                var i = start + 1
                while (i < source.length) {
                    when (source[i]) {
                        '\\' -> i += 2
                        '"' -> return i + 1 - start
                        '\n' -> return i - start
                        else -> i++
                    }
                }
                return source.length - start
            }

            private fun charLiteralLength(source: String, start: Int): Int {
                var i = start + 1
                while (i < source.length) {
                    when (source[i]) {
                        '\\' -> i += 2
                        '\'' -> return i + 1 - start
                        '\n' -> return i - start
                        else -> i++
                    }
                }
                return source.length - start
            }
        }
    }

    /** A string literal: where it starts, which line it is on, and what is between the quotes. */
    data class Literal(val start: Int, val line: Int, val text: String)

    companion object {

        /** Hangul syllables, conjoining jamo and the compatibility jamo block. */
        private fun isHangul(c: Char): Boolean =
            c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'

        /**
         * The source roots to scan, handed over by Gradle.
         *
         * The paths are **not** guessed from the working directory: a test task may run from
         * anywhere, and a wrong guess would scan nothing and still report a pass. Gradle also
         * declares the same files as task inputs — see `csms/build.gradle.kts`.
         */
        const val SOURCE_ROOTS_PROPERTY = "swapve.wireLanguage.sourceRoots"

        fun sourceRootsFromSystemProperty(): List<File> =
            System.getProperty(SOURCE_ROOTS_PROPERTY)
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map(::File)
    }
}
