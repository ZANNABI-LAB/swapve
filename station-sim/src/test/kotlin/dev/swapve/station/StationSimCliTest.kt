package dev.swapve.station

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 인자 해석 — 시뮬레이터의 다른 시험들이 [StationSimConfig] 를 직접 만들어 쓰는 바람에
 * **CLI 표면만 아무도 보지 않았다.**
 *
 * 같은 구멍이 `sim-console` 에도 있었고 (`SimConsoleCliTest`), 같은 결함이 두 곳에서
 * 나왔다 — 아래 [모르는 옵션은 조용히 무시되지 않는다].
 */
class StationSimCliTest {

    @Test
    fun `짝지어 준 값을 그대로 읽는다`() {
        val options = StationSimCli.parse(arrayOf("--station-id", "CS042", "--slots", "6"))

        assertEquals(mapOf("station-id" to "CS042", "slots" to "6"), options)
    }

    /**
     * ★ 결함이었다. `--slot 6` 은 오타지만 `--` 로 시작하는 정상적인 짝이라 파서가 받아
     * 조용히 버렸고, 시뮬레이터는 아무 말 없이 **기본 4 슬롯**으로 떴다.
     */
    @Test
    fun `모르는 옵션은 조용히 무시되지 않는다`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            StationSimCli.parse(arrayOf("--slot", "6"))
        }

        assertTrue("unknown option: --slot" in failure.message.orEmpty(), failure.message.orEmpty())
        assertTrue("--slots" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    /** USAGE 에 적힌 것과 파서가 받는 것이 갈리면, 문서대로 쓴 사람이 거절당한다. */
    @Test
    fun `USAGE 에 적힌 옵션은 전부 받아들여진다`() {
        val documented = Regex("^ {2}--([a-z0-9-]+)", RegexOption.MULTILINE)
            .findAll(StationSimCli.USAGE)
            .map { it.groupValues[1] }
            .toSet()

        val flags = setOf("remote-start", "fault-f6")
        assertEquals(documented - flags, StationSimCli.KNOWN, "USAGE 와 파서가 갈렸다")
    }

    @Test
    fun `값 없는 플래그는 파서에 오기 전에 걸러진다`() {
        // main 이 filterNot 으로 떼고 넘긴다. 떼지 않은 채로 오면 값을 요구하며 거절한다 —
        // 그 사실을 고정해 둔다. 플래그를 늘릴 때 이 시험이 먼저 알려 준다.
        assertTrue(
            "unknown option: --remote-start" in assertFailsWith<IllegalArgumentException> {
                StationSimCli.parse(arrayOf("--remote-start", "--slots", "4"))
            }.message.orEmpty(),
        )
    }

    @Test
    fun `짝이 맞지 않는 인자를 거절한다`() {
        assertTrue(
            "argument without a value" in assertFailsWith<IllegalArgumentException> {
                StationSimCli.parse(arrayOf("--slots"))
            }.message.orEmpty(),
        )
        assertTrue(
            "unknown argument" in assertFailsWith<IllegalArgumentException> {
                StationSimCli.parse(arrayOf("4"))
            }.message.orEmpty(),
        )
    }
}
