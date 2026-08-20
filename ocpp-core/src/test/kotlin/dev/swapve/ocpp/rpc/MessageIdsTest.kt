package dev.swapve.ocpp.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageIdsTest {

    @Test
    fun `36자 한도 안에 들어간다`() {
        assertTrue(MessageIds.next().length <= OcppFrameCodec.MAX_MESSAGE_ID_LENGTH)
    }

    @Test
    fun `연속 발번이 중복되지 않는다`() {
        val ids = List(10_000) { MessageIds.next() }
        assertEquals(ids.size, ids.toSet().size, "messageId 가 중복됐다 (Part 4 §4.1.4 위반)")
    }

    @Test
    fun `사전순 정렬이 생성순과 같다`() {
        // 시간순 정렬 가능 = 이벤트 로그의 순서 재구성이 공짜가 된다
        val ids = List(1_000) { MessageIds.next() }
        assertEquals(ids, ids.sorted())
    }
}
