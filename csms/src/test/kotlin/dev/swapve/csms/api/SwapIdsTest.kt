package dev.swapve.csms.api

import dev.swapve.swap.StationId
import dev.swapve.swap.SwapKey
import dev.swapve.swap.SwapRequestId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 복합키의 URL 표현 (PLAN §5.3).
 *
 * 교환은 `(stationId, requestId)` 로만 유일하므로 `{id}` 하나에 둘이 들어간다. 콜론이
 * 구분자로 안전한 근거는 **Part 4 §3.1.1 이 스테이션 식별자에 콜론을 금지**한다는 것이고,
 * 그 규칙은 `StationIdentityTest` 가 이미 실행되는 검사로 확인하고 있다.
 */
class SwapIdsTest {

    private val key = SwapKey(StationId("CS001"), SwapRequestId(42))

    @Test
    fun `상관키와 URL 토큰이 서로를 되돌린다`() {
        assertEquals("CS001:42", SwapIds.of(key))
        assertEquals(key, SwapIds.parse(SwapIds.of(key)))
    }

    @Test
    fun `조회 경로가 곧 self 다`() {
        assertEquals("/api/swaps/CS001:42", SwapIds.pathOf(key))
    }

    /** 식별자에 하이픈·점·퍼센트 인코딩 흔적이 섞여도 첫 콜론 하나로 갈린다. */
    @Test
    fun `콜론이 아닌 문자는 식별자에 그대로 들어간다`() {
        val long = SwapKey(StationId("CS-STATION.01_A"), SwapRequestId(2_147_483_647))

        assertEquals("CS-STATION.01_A:2147483647", SwapIds.of(long))
        assertEquals(long, SwapIds.parse(SwapIds.of(long)))
    }

    @Test
    fun `모양이 아니면 null 이다`() {
        listOf(
            "",
            "CS001",
            ":42",
            "CS001:",
            "CS001:abc",
            "CS001:4.2",
            // Int 범위를 넘는 값. requestId 는 스키마상 integer 다 (PLAN §4.3).
            "CS001:99999999999",
        ).forEach { assertNull(SwapIds.parse(it), "'$it' 이 상관키로 읽혔다") }
    }

    /** 음수 requestId 는 발번하지 않지만, 읽을 수는 있어야 한다 — 없는 교환으로 404 가 된다. */
    @Test
    fun `음수도 문법으로는 성립한다`() {
        assertEquals(SwapKey(StationId("CS001"), SwapRequestId(-1)), SwapIds.parse("CS001:-1"))
    }
}
