package dev.swapve.console

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 인자 해석 — `sim-console` 모듈의 **첫 자기 시험**이다.
 *
 * 이 모듈 1,321줄은 그동안 `csms` 의 `SimConsoleControlTest` 로만 간접적으로 덮였고,
 * 그 시험은 서버를 띄운 뒤의 제어 API 를 본다. **CLI 는 아무도 보지 않았다.**
 *
 * 그래서 이 시험을 쓰다가 결함이 하나 나왔다 — 아래 [모르는 옵션은 조용히 무시되지 않는다].
 */
class SimConsoleCliTest {

    @Test
    fun `기본값은 아무 인자 없이도 성립한다`() {
        val options = SimConsoleCli.options(emptyArray())

        assertEquals(8090, options.port)
        assertEquals("127.0.0.1", options.bindAddress)
        assertEquals("ws://localhost:8080/ocpp", options.csmsUrl)
        assertNull(options.credentials)
    }

    @Test
    fun `준 값이 기본값을 밀어낸다`() {
        val options = SimConsoleCli.options(
            arrayOf("--port", "9001", "--bind", "0.0.0.0", "--csms-url", "ws://elsewhere/ocpp",
                    "--user", "ops", "--password", "s3cret"),
        )

        assertEquals(9001, options.port)
        assertEquals("0.0.0.0", options.bindAddress)
        assertEquals("ws://elsewhere/ocpp", options.csmsUrl)
        assertEquals(SimConsoleServer.Credentials("ops", "s3cret"), options.credentials)
    }

    /**
     * ★ 결함이었다. `--prot 9000` 은 오타지만 `--` 로 시작하는 정상적인 짝이라 파서가
     * 받아들이고 조용히 버렸다 — 콘솔은 아무 말 없이 **기본 포트 8090** 으로 떴다.
     * 실제로 돌려서 확인했다. 준 사람은 9000 에 떴다고 믿는다.
     */
    @Test
    fun `모르는 옵션은 조용히 무시되지 않는다`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SimConsoleCli.options(arrayOf("--prot", "9000"))
        }

        assertTrue("unknown option: --prot" in failure.message.orEmpty(), failure.message.orEmpty())
        // 무엇을 줄 수 있었는지 같이 알려 준다. 오타는 목록을 보면 대개 스스로 보인다.
        assertTrue("--port" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    /**
     * USAGE 에 적힌 것과 파서가 받는 것이 갈리면, **문서대로 쓴 사람이 거절당한다.**
     * `station-sim` 쪽에서 같은 대조를 먼저 세웠고, 그쪽에서 실제로 값을 했다.
     */
    @Test
    fun `USAGE 에 적힌 옵션은 전부 받아들여진다`() {
        val documented = Regex("^ {2}--([a-z0-9-]+)", RegexOption.MULTILINE)
            .findAll(SimConsoleCli.USAGE)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(documented, SimConsoleCli.KNOWN, "USAGE 와 파서가 갈렸다")
    }

    @Test
    fun `짝이 맞지 않는 인자를 거절한다`() {
        assertTrue(
            "argument without a value" in assertFailsWith<IllegalArgumentException> {
                SimConsoleCli.options(arrayOf("--port"))
            }.message.orEmpty(),
        )

        assertTrue(
            "unknown argument" in assertFailsWith<IllegalArgumentException> {
                SimConsoleCli.options(arrayOf("8090"))
            }.message.orEmpty(),
        )
    }

    @Test
    fun `숫자가 아닌 포트를 거절한다`() {
        assertTrue(
            "not a number: --port eight" in assertFailsWith<IllegalArgumentException> {
                SimConsoleCli.options(arrayOf("--port", "eight"))
            }.message.orEmpty(),
        )
    }

    /** 한쪽만 주면 인증이 반쯤 켜진 채로 뜬다 — 그 상태를 허용하지 않는다. */
    @Test
    fun `사용자와 비밀번호는 함께여야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            SimConsoleCli.options(arrayOf("--user", "ops"))
        }
        assertFailsWith<IllegalArgumentException> {
            SimConsoleCli.options(arrayOf("--password", "s3cret"))
        }
    }
}
