package dev.swapve.station

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ★ **초록인 감사는 그 자체로 아무것도 증명하지 않는다.**
 *
 * 아무것도 세지 않는 감사도 초록으로 끝난다. 이 저장소가 그 함정에 두 번 걸렸다 —
 * 입력 선언이 없어 시험이 아예 돌지 않은 적이 있고(`wireConstantsSource`), 함정을 잡는다고
 * 적어 둔 단언이 동어반복이라 아무것도 안 잡은 적이 있다(`0d9faea`).
 *
 * 그래서 [DocumentedSurfaceAudit] 도 양쪽을 다 시험한다: 일부러 틀린 소스·문서를 먹여
 * **빨개지는지** 보고, 정당한 것에는 **조용한지** 보고, 그런 다음에야 저장소의 진짜 파일에
 * 돌린다. 마지막 시험은 **센 개수가 0 이 아님**도 함께 단언한다 — 경로가 빗나가면 그 자리에서
 * 드러나야 하기 때문이다.
 *
 * L1 단위 게이트: 소켓도 코루틴도 없다.
 */
class DocumentedSurfaceAuditTest {

    // ------------------------------------------------------------------ 검출

    @Test
    fun `함수 개수가 문서와 다르면 잡는다`(@TempDir dir: File) {
        val result = auditOf(
            dir,
            source = """
            class StationSimulator {
                fun connect() {}
                fun boot() {}
            }
            """.trimIndent(),
            doc = "All 1 public functions and 0 public properties of `StationSimulator`.\n`connect` `boot`",
        )

        assertFalse(result.passed, "개수가 어긋났는데 통과했다")
        assertTrue(
            result.violations.any { "함수 개수" in it && "문서는 1" in it && "소스는 2" in it },
            "무엇이 어긋났는지 보고에 없다: ${result.violations}",
        )
    }

    @Test
    fun `속성 개수가 문서와 다르면 잡는다`(@TempDir dir: File) {
        val result = auditOf(
            dir,
            source = """
            class StationSimulator {
                val isConnected: Boolean = false
                var lastTransmitFailure: String? = null
            }
            """.trimIndent(),
            doc = "All 0 public functions and 1 public properties of `StationSimulator`.\n" +
                "`isConnected` `lastTransmitFailure`",
        )

        assertFalse(result.passed, "속성 개수가 어긋났는데 통과했다")
        assertTrue(result.violations.any { "속성 개수" in it }, "보고: ${result.violations}")
    }

    /**
     * ★ **이 시험이 개수 대조만으로는 못 잡는 것을 잡는다.**
     *
     * 멤버 하나를 지우고 다른 하나를 더하면 합계는 그대로다. 개수만 세는 감사는 그것을
     * 영영 통과시킨다 — *"with nothing left out"* 이 주장하는 것은 합계가 아니라 목록이다.
     */
    @Test
    fun `개수는 맞는데 이름이 문서에 없으면 잡는다`(@TempDir dir: File) {
        val result = auditOf(
            dir,
            source = """
            class StationSimulator {
                fun connect() {}
                fun reconnect() {}
            }
            """.trimIndent(),
            doc = "All 2 public functions and 0 public properties of `StationSimulator`.\n" +
                "`connect` 는 붙는다. `disconnect` 는 끊는다.",
        )

        assertFalse(result.passed, "문서에 없는 멤버를 통과시켰다")
        assertTrue(
            result.violations.any { "문서에 없다: reconnect" in it },
            "빠진 이름을 지목하지 않았다: ${result.violations}",
        )
    }

    @Test
    fun `완전성 주장 문장이 사라지면 잡는다`(@TempDir dir: File) {
        val result = auditOf(
            dir,
            source = "class StationSimulator {\n    fun connect() {}\n}",
            doc = "이 문서는 이제 개수를 말하지 않는다. `connect`",
        )

        assertFalse(result.passed, "주장이 사라졌는데 조용했다")
        assertTrue(
            result.violations.any { "완전성 주장 문장" in it },
            "문장이 사라진 사실을 말하지 않았다: ${result.violations}",
        )
    }

    // ------------------------------------------------------------------ 조용해야 하는 자리

    @Test
    fun `private 과 internal 은 공개 표면이 아니다`(@TempDir dir: File) {
        val result = auditOf(
            dir,
            source = """
            class StationSimulator {
                fun connect() {}
                private fun bootSequence() {}
                internal fun peek() {}
                private val slots = emptyMap<Int, String>()
                private var closed = false
            }
            """.trimIndent(),
            doc = "All 1 public functions and 0 public properties of `StationSimulator`.\n`connect`",
        )

        assertTrue(result.passed, "비공개 멤버를 세었다: ${result.violations}")
        assertEquals(1, result.checked, "센 것은 공개 함수 하나뿐이어야 한다")
    }

    @Test
    fun `중첩된 선언은 클래스의 표면이 아니다`(@TempDir dir: File) {
        val result = auditOf(
            dir,
            source = """
            class StationSimulator {
                fun connect() {}

                class Inner {
                    fun hidden() {}
                    val alsoHidden: Int = 0
                }

                companion object {
                    const val NOT_A_MEMBER = "x"
                    fun factory() {}
                }
            }
            """.trimIndent(),
            doc = "All 1 public functions and 0 public properties of `StationSimulator`.\n`connect`",
        )

        assertTrue(result.passed, "여덟 칸 들여쓴 선언을 세었다: ${result.violations}")
        assertEquals(1, result.checked)
    }

    @Test
    fun `suspend 와 override 와 생성자 프로퍼티는 표면이다`(@TempDir dir: File) {
        val result = auditOf(
            dir,
            source = """
            class StationSimulator(
                val config: StationSimConfig,
                private val clock: Clock,
            ) : AutoCloseable {
                suspend fun connect() {}
                override fun close() {}
                val isConnected: Boolean get() = true
            }
            """.trimIndent(),
            doc = "All 2 public functions and 2 public properties of `StationSimulator`.\n" +
                "`config` `isConnected` `connect` `close`",
        )

        assertTrue(result.passed, "표면인 것을 빠뜨렸다: ${result.violations}")
        assertEquals(4, result.checked)
    }

    // ------------------------------------------------------------------ 진짜 파일

    /**
     * ★ 저장소의 실제 `StationSimulator` 와 `VIRTUAL-STATION.md` 를 맞대어 본다.
     *
     * 경로는 Gradle 이 입력으로 선언한 그대로 온다. 추정했다가 빗나가면 **0 개를 세고도
     * 통과**하므로, 센 개수가 0 이 아님을 함께 단언한다.
     */
    @Test
    fun `문서의 완전성 주장이 지금도 사실이다`() {
        val source = File(requireNotNull(System.getProperty(SOURCE_PROPERTY)) { "$SOURCE_PROPERTY 가 없다" })
        val doc = File(requireNotNull(System.getProperty(DOC_PROPERTY)) { "$DOC_PROPERTY 가 없다" })
        assertTrue(source.isFile, "소스를 찾지 못했다: $source")
        assertTrue(doc.isFile, "문서를 찾지 못했다: $doc")

        val result = DocumentedSurfaceAudit(source, doc).run()

        assertTrue(result.checked > 0, "아무것도 세지 않고 통과했다 — 경로나 정규식이 빗나갔다")
        assertTrue(
            result.passed,
            "문서가 코드와 어긋났다. 멤버를 늘리거나 줄였으면 " +
                "docs/VIRTUAL-STATION.md §2 의 개수와 배치표를 함께 고친다:\n" +
                result.violations.joinToString("\n") { "  - $it" },
        )
    }

    // ------------------------------------------------------------------ 지원

    private fun auditOf(dir: File, source: String, doc: String): DocumentedSurfaceAudit.Result {
        val sourceFile = File(dir, "StationSimulator.kt").apply { writeText(source) }
        val docFile = File(dir, "VIRTUAL-STATION.md").apply { writeText(doc) }
        return DocumentedSurfaceAudit(sourceFile, docFile).run()
    }

    private companion object {
        const val SOURCE_PROPERTY = "swapve.docs.stationSimulatorSource"
        const val DOC_PROPERTY = "swapve.docs.virtualStationDoc"
    }
}
