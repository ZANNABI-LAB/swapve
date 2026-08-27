package dev.swapve.csms.audit

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ★ **The wire-language audit is tested the way [InvariantAuditTest] tests its audit.**
 *
 * A green audit proves nothing on its own — an anchor list that matches nothing also ends green.
 * So this test does both halves: it feeds [WireLanguageAudit] source that is deliberately wrong
 * and checks that it goes red, feeds it source that is *legitimately* Korean (KDoc, `//`
 * comments, `log.warn`, `error()`) and checks that it stays quiet, and only then runs it over
 * the repository's real sources.
 *
 * ### The limitation this test cannot cover
 *
 * The audit reads the argument region of a wire sink, so a string that arrives through a helper
 * — `SimDeviceModel.unknownVariableHint(ref)` is the live example — is invisible to it. The
 * `Get/SetVariablesResponse` text it produces was translated by hand and is held to English by
 * review, not by this gate. Same for anything the caller assembles before passing it in.
 *
 * L1 unit gate: no socket, no Spring context, no tag.
 */
class WireLanguageAuditTest {

    // ------------------------------------------------------------------ 검출

    @Test
    fun `한글 CALLERROR 설명을 잡는다`(@TempDir dir: File) {
        val item = auditOf(
            dir,
            """
            package sample

            fun handle(): InboundResponse = InboundResponse.Fail(
                RpcErrorCode.NotImplemented,
                "구현하지 않은 action 이다",
            )
            """.trimIndent(),
        )

        assertFalse(item.passed, "한글 errorDescription 을 통과시켰다:\n" + item.violations)
        assertTrue(
            item.violations.any { "구현하지 않은" in it },
            "위반한 문자열이 보고에 없다: ${item.violations}",
        )
    }

    @Test
    fun `한글 additionalInfo 를 잡는다`(@TempDir dir: File) {
        val item = auditOf(
            dir,
            """
            package sample

            val reading = VariableReading(
                ref,
                additionalInfo = "슬롯에 배터리가 없다",
            )
            """.trimIndent(),
        )

        assertFalse(item.passed, "한글 additionalInfo 를 통과시켰다")
        assertTrue(item.violations.any { "배터리가 없다" in it }, "${item.violations}")
    }

    @Test
    fun `직렬화 자리의 한글도 잡는다`(@TempDir dir: File) {
        val item = auditOf(
            dir,
            """
            package sample

            fun payload() = objectNode().apply {
                put("additionalInfo", "등록되지 않은 배터리다")
            }
            """.trimIndent(),
        )

        assertFalse(item.passed, "put(\"additionalInfo\", …) 의 한글을 통과시켰다")
    }

    // ------------------------------------------------------------------ 오탐 방지

    @Test
    fun `한글 KDoc 과 주석과 로그와 예외는 위반이 아니다`(@TempDir dir: File) {
        val item = auditOf(
            dir,
            """
            package sample

            /**
             * 이 함수는 교환을 거부한다. 이 문장은 KDoc 이라 와이어로 나가지 않는다.
             *
             * `additionalInfo = "여기는 예시지 코드가 아니다"` 처럼 적어도 마찬가지다.
             */
            fun reject(): InboundResponse {
                // 여기 적힌 한글도 와이어가 아니다.
                log.warn("교환을 거부한다: station={}", stationId)
                if (broken) error("장부가 맞지 않는다")
                require(slots > 0) { "슬롯이 없다" }
                /* 블록 주석 안의 additionalInfo = "한글" 도 마찬가지다 */
                return InboundResponse.Fail(
                    RpcErrorCode.PropertyConstraintViolation,
                    "idToken.idToken and idToken.type must not be empty",
                )
            }
            """.trimIndent(),
        )

        assertTrue(item.violations.isEmpty(), "와이어가 아닌 한글을 잡았다: ${item.violations}")
        assertTrue(item.checked > 0, "앵커를 하나도 찾지 못했다 — 통과가 아니라 미검사다")
    }

    @Test
    fun `앵커가 없는 소스는 통과가 아니라 미검사다`(@TempDir dir: File) {
        val item = auditOf(dir, "package sample\n\nval greeting = \"안녕\"\n")

        assertEquals(0, item.checked, "와이어 싱크가 없는 파일에서 앵커를 찾았다")
        assertFalse(item.passed, "0 건을 검사하고 통과로 끝났다")
    }

    // ------------------------------------------------------------------ 실제 소스 전량

    @Test
    fun `저장소의 와이어 문자열은 전부 영문이다`() {
        val roots = WireLanguageAudit.sourceRootsFromSystemProperty()

        assertTrue(
            roots.isNotEmpty() && roots.all { it.isDirectory },
            "스캔할 소스 루트를 받지 못했다 — csms/build.gradle.kts 의 " +
                "`${WireLanguageAudit.SOURCE_ROOTS_PROPERTY}` 선언을 보라. 받은 값: $roots",
        )

        val item = WireLanguageAudit(roots).run()

        assertTrue(
            item.violations.isEmpty(),
            "와이어로 나가는 문자열에 한글이 있다 — 상대가 읽는 자리다:\n" +
                item.violations.joinToString("\n") { "  - $it" },
        )
        assertTrue(item.checked > 0, "앵커를 하나도 찾지 못했다 — 통과가 아니라 미검사다")
    }

    // ------------------------------------------------------------------ 도구

    private fun auditOf(dir: File, source: String): AuditItem {
        dir.resolve("Sample.kt").writeText(source)
        return WireLanguageAudit(listOf(dir)).run()
    }
}
