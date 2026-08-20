package dev.swapve.ocpp.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageIdsTest {

    @Test
    fun `36자 한도를 넘지 않는다`() {
        // UUID 는 정확히 36자다. 한도와 같지 넘지 않는다 (Part 4 §4.1.4).
        assertEquals(36, MessageIds.newId().length)
        assertTrue(MessageIds.newId().length <= OcppFrameCodec.MAX_MESSAGE_ID_LENGTH)
    }

    @Test
    fun `연속 발번이 중복되지 않는다`() {
        val ids = List(10_000) { MessageIds.newId() }
        assertEquals(ids.size, ids.toSet().size, "messageId 가 중복됐다 (Part 4 §4.1.4 위반)")
    }

    @Test
    fun `코덱이 받아들이는 형식이다`() {
        // 발번한 값을 실제로 프레임에 실어 왕복시킨다 — 한도·문자 제약을 코덱에게 물어본다.
        val codec = OcppFrameCodec()
        val id = MessageIds.newId()
        val text = codec.encode(OcppFrame.Call(id, "Heartbeat", com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()))

        val outcome = codec.decode(text)
        assertTrue(outcome is DecodeOutcome.Decoded, "코덱이 거부했다: $outcome")
        assertEquals(id, outcome.frame.messageId)
    }
}
