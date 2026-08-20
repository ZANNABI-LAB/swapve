package dev.swapve.ocpp.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwapRequestIdsTest {

    @Test
    fun `언제나 양수다`() {
        // 0 은 돌려주지 않는다 — "값이 없다"와 구분되지 않는 값을 발번하지 않는다.
        repeat(10_000) { assertTrue(SwapRequestIds.newId() > 0) }
    }

    @Test
    fun `스키마의 integer 범위 안이다`() {
        // requestId 는 스키마상 integer 다. Int 를 넘지 않는 것이 이 발번의 제약이다.
        repeat(10_000) { assertTrue(SwapRequestIds.newId() <= Int.MAX_VALUE) }
    }

    @Test
    fun `로컬 카운터가 아니다`() {
        // 카운터라면 1,2,3… 으로 나온다. 재시작하면 되돌아가고 인스턴스끼리 겹치는 성질이
        // 바로 그것이므로, 연속값이 아님을 고정해 둔다.
        val ids = List(100) { SwapRequestIds.newId() }
        assertTrue(ids.zipWithNext().none { (a, b) -> b == a + 1 }, "연속 카운터처럼 보인다: $ids")
    }

    @Test
    fun `전역 유일성을 약속하지 않는다는 것을 알고 쓴다`() {
        // 31비트 공간이라 대량 발번에서는 충돌이 보이는 것이 정상이다. 이 시험은 충돌이
        // 없음을 주장하지 않는다 — 상관키가 (stationId, requestId) 복합키라 안전할 뿐이다.
        val ids = List(1_000) { SwapRequestIds.newId() }
        assertEquals(ids.size, ids.toSet().size, "1000건 규모에서는 충돌이 사실상 없어야 한다")
    }
}
