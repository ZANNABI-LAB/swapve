package dev.swapve.ocpp.json

import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.schema.PayloadValidation
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class OcppDateTimeTest {

    private val mapper = ObjectMapper()
    private val validator = OcppPayloadValidator()

    private fun at(text: String) = Instant.parse(text)

    // ---------------------------------------------------------------- format

    /**
     * 이 시험이 있는 이유. `ISO_INSTANT` 는 밀리초가 0 이면 소수부를 **아예 내지 않는다.**
     * 그러면 같은 CSMS 가 `...:00Z` 와 `...:00.123Z` 를 섞어 보내게 되고, 상대 파서의
     * 소수부 처리가 다를 때 그것이 사고가 된다.
     */
    @Test
    fun `소수부는 밀리초가 0 이어도 세 자리로 나온다`() {
        assertEquals("2026-08-18T09:30:00.000Z", OcppDateTime.format(at("2026-08-18T09:30:00Z")))
        assertEquals("2026-08-18T09:30:00.100Z", OcppDateTime.format(at("2026-08-18T09:30:00.1Z")))
        assertEquals("2026-08-18T09:30:00.123Z", OcppDateTime.format(at("2026-08-18T09:30:00.123Z")))
    }

    @Test
    fun `나노초는 버린다 — 반올림하지 않는다`() {
        assertEquals("2026-08-18T09:30:00.123Z", OcppDateTime.format(at("2026-08-18T09:30:00.123999999Z")))
        assertEquals("2026-08-18T09:30:00.000Z", OcppDateTime.format(at("2026-08-18T09:30:00.000456Z")))
    }

    @Test
    fun `언제나 UTC 로 낸다`() {
        // 같은 순간을 오프셋으로 적어 넣어도 출력은 Z 하나뿐이다.
        assertEquals("2026-08-18T00:30:00.000Z", OcppDateTime.format(at("2026-08-18T09:30:00+09:00")))
    }

    /**
     * 형식이 스스로 맞다고 주장하지 않고 **공식 스키마에 물어본다.** `HeartbeatResponse`
     * 의 `currentTime` 이 `format: date-time` 필드다.
     */
    @Test
    fun `낸 문자열이 공식 스키마의 date-time 을 통과한다`() {
        val payload = mapper.createObjectNode()
            .put("currentTime", OcppDateTime.format(at("2026-08-18T09:30:00Z")))

        assertIs<PayloadValidation.Valid>(validator.validateCallResult("Heartbeat", payload))
    }

    // ---------------------------------------------------------------- parse

    @Test
    fun `낸 것을 다시 읽으면 같은 순간이다`() {
        val original = at("2026-08-18T09:30:00.123Z")
        assertEquals(original, OcppDateTime.parse(OcppDateTime.format(original)))
    }

    @Test
    fun `오프셋 표기도 받아 UTC 로 정규화한다`() {
        // 우리는 Z 로만 내지만, 상대가 오프셋으로 보내는 것은 RFC 3339 로 유효하다.
        assertEquals(at("2026-08-18T00:30:00Z"), OcppDateTime.parse("2026-08-18T09:30:00+09:00"))
    }

    @Test
    fun `읽지 못하면 예외가 아니라 null 이다`() {
        // 시각 하나 때문에 메시지 전체를 실패시키지 않는다. 원문은 이벤트 로그에 남는다.
        assertNull(OcppDateTime.parse("2026-08-18 09:30:00Z"))
        assertNull(OcppDateTime.parse("2026-08-18T09:30:00"))
        assertNull(OcppDateTime.parse(""))
    }
}
