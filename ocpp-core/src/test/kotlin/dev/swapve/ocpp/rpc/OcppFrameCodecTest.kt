package dev.swapve.ocpp.rpc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OcppFrameCodecTest {

    private val mapper = ObjectMapper()
    private val codec = OcppFrameCodec(mapper)

    private fun obj(json: String) = mapper.readTree(json) as ObjectNode
    private fun empty() = mapper.createObjectNode()

    private fun decoded(text: String): OcppFrame {
        val outcome = codec.decode(text)
        assertIs<DecodeOutcome.Decoded>(outcome, "decode 실패: $outcome")
        return outcome.frame
    }

    private fun malformed(text: String): DecodeOutcome.Malformed {
        val outcome = codec.decode(text)
        assertIs<DecodeOutcome.Malformed>(outcome, "malformed 로 판정됐어야 한다: $outcome")
        return outcome
    }

    // ---------------------------------------------------------------- 스펙 예제

    @Test
    fun `Part 4 §4-2-1 CALL 예제를 디코딩한다`() {
        val frame = decoded(
            """[2,"19223201","BootNotification",{"reason":"PowerUp","chargingStation":{"model":"SingleSocketCharger","vendorName":"VendorX"}}]"""
        )

        assertEquals(
            OcppFrame.Call(
                messageId = "19223201",
                action = "BootNotification",
                payload = obj("""{"reason":"PowerUp","chargingStation":{"model":"SingleSocketCharger","vendorName":"VendorX"}}"""),
            ),
            frame,
        )
    }

    @Test
    fun `Part 4 §4-2-2 CALLRESULT 예제를 디코딩한다`() {
        val frame = decoded("""[3,"19223201",{"currentTime":"2013-02-01T20:53:32.486Z","interval":300,"status":"Accepted"}]""")

        assertEquals("19223201", frame.messageId)
        assertIs<OcppFrame.CallResult>(frame)
        assertEquals("Accepted", frame.payload.get("status").textValue())
    }

    @Test
    fun `Part 4 §4-2-3 CALLERROR 예제를 디코딩한다`() {
        val frame = decoded("""[4,"162376037","NotSupported","SetDisplayMessageRequest not supported",{}]""")

        assertEquals(
            OcppFrame.CallError("162376037", "NotSupported", "SetDisplayMessageRequest not supported", empty()),
            frame,
        )
        assertEquals(RpcErrorCode.NotSupported, (frame as OcppFrame.CallError).knownErrorCode)
    }

    @Test
    fun `Part 4 §4-2-4 SEND 예제를 디코딩한다`() {
        val frame = decoded("""[6,"19223201","NotifyPeriodicEventStream",{"id":123,"pending":0,"basetime":"2024-08-27T12:30:40Z","data":[{"t":0,"v":"230.4"}]}]""")

        assertIs<OcppFrame.Send>(frame)
        // SEND 의 action 은 접미사를 떼지 않는다 — 애초에 Request/Response 로 끝나지 않는다
        assertEquals("NotifyPeriodicEventStream", frame.action)
    }

    @Test
    fun `TC_S_103_CSMS 의 BatterySwap CALL 을 왕복한다`() {
        // TC_S_103_CSMS step 11 — 배터리 2개 세트가 공식 테스트다
        val text = """[2,"${'$'}wapVe0000001","BatterySwap",""" +
            """{"eventType":"BatteryIn","requestId":42,"idToken":{"idToken":"1234","type":"ISO14443"},""" +
            """"batteryData":[{"evseId":1,"serialNumber":"1234","soC":23,"soH":85},""" +
            """{"evseId":2,"serialNumber":"5678","soC":45,"soH":87}]}]"""

        val frame = decoded(text)
        assertIs<OcppFrame.Call>(frame)
        assertEquals("BatterySwap", frame.action)
        assertEquals(2, frame.payload.get("batteryData").size())
        assertEquals(text, codec.encode(frame))
    }

    // ---------------------------------------------------------------- 왕복

    @Test
    fun `5개 메시지 타입 전부 encode-decode 왕복에서 보존된다`() {
        val frames = listOf(
            OcppFrame.Call("id-call", "BatterySwap", obj("""{"eventType":"BatteryOut"}""")),
            OcppFrame.CallResult("id-result", empty()),
            OcppFrame.CallError("id-error", "FormatViolation", "bad payload", obj("""{"field":"soC"}""")),
            OcppFrame.CallResultError("id-rerror", "TypeConstraintViolation", "", empty()),
            OcppFrame.Send("id-send", "NotifyPeriodicEventStream", obj("""{"id":1}""")),
        )

        frames.forEach { frame ->
            assertEquals(frame, decoded(codec.encode(frame)), "${frame.type} 왕복이 깨졌다")
        }
    }

    @Test
    fun `빈 페이로드는 빈 객체로 인코딩된다`() {
        // Part 4 §4.1.5 — null 이 아니라 {} 를 쓴다
        assertEquals("""[3,"id",{}]""", codec.encode(OcppFrame.CallResult("id", empty())))
    }

    // ---------------------------------------------------------------- 미지의 타입

    @Test
    fun `표에 없는 메시지 타입은 CALLERROR 가 아니라 무시다`() {
        // errata 2026-06 §4.1·§4.3 — MessageTypeNotSupported 는 deprecated
        assertEquals(DecodeOutcome.Ignored(7), codec.decode("""[7,"id","Whatever",{}]"""))
        assertEquals(DecodeOutcome.Ignored(1), codec.decode("""[1,"id",{}]"""))
    }

    // ---------------------------------------------------------------- 손상된 프레임

    @Test
    fun `messageId 를 읽을 수 없으면 -1 로 회신하게 한다`() {
        // Part 4 §4.2.3 — "When also the MessageId cannot be read, the CALLERROR SHALL contain -1"
        listOf(
            """{"not":"an array"}""",
            """[2,"unclosed""",
            "",
            """[2]""",
            """["2","id","Action",{}]""",
            """[2,12345,"Action",{}]""",
            """[2,"","Action",{}]""",
            """[2,"${"x".repeat(37)}","Action",{}]""",
        ).forEach { text ->
            val outcome = malformed(text)
            assertEquals(OcppFrameCodec.UNREADABLE_MESSAGE_ID, outcome.messageId, "입력: $text")
            assertEquals(RpcErrorCode.RpcFrameworkError, outcome.errorCode, "입력: $text")
        }
    }

    @Test
    fun `messageId 는 읽히지만 구조가 틀리면 그 id 로 ProtocolError 를 낸다`() {
        listOf(
            """[2,"id","Action"]""",
            """[2,"id","Action",{},"extra"]""",
            """[2,"id","",{}]""",
            """[2,"id","Action",[]]""",
            """[3,"id",{},"extra"]""",
            """[4,"id","FormatViolation","desc"]""",
            """[4,"id","FormatViolation","desc",[]]""",
            """[5,"id",404,"desc",{}]""",
        ).forEach { text ->
            val outcome = malformed(text)
            assertEquals("id", outcome.messageId, "입력: $text")
            assertEquals(RpcErrorCode.ProtocolError, outcome.errorCode, "입력: $text")
        }
    }

    @Test
    fun `손상된 JSON 원문을 오류 설명에 되싣지 않는다`() {
        // Part 4 §4.2.3 — CALLERROR 는 문법적으로 잘못된 JSON 을 그대로 포함해서는 안 된다
        val outcome = malformed("""[2,"id",{"broken": """)
        assertTrue(outcome.errorDescription.none { it in "{}[]\"" }, "설명: ${outcome.errorDescription}")
    }

    @Test
    fun `표에 없는 errorCode 도 버리지 않고 보존한다`() {
        val frame = decoded("""[4,"id","VendorSpecificFailure","",{}]""")

        assertIs<OcppFrame.CallError>(frame)
        assertEquals("VendorSpecificFailure", frame.errorCode)
        assertEquals(null, frame.knownErrorCode)
    }

    // ---------------------------------------------------------------- 인코딩 제약

    @Test
    fun `우리가 규격 위반 프레임을 내보내는 것은 막는다`() {
        assertFailsWith<IllegalArgumentException> {
            codec.encode(OcppFrame.Call("x".repeat(37), "BatterySwap", empty()))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.encode(OcppFrame.Call("", "BatterySwap", empty()))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.encode(OcppFrame.Call("id", " ", empty()))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.encode(OcppFrame.CallError("id", "GenericError", "d".repeat(256), empty()))
        }
    }

    @Test
    fun `messageId 가 -1 인 CALLERROR 는 내보낼 수 있다`() {
        // 읽을 수 없는 메시지에 대한 회신 경로 자체가 막히면 안 된다
        val text = codec.encode(
            OcppFrame.CallError(OcppFrameCodec.UNREADABLE_MESSAGE_ID, RpcErrorCode.RpcFrameworkError.name, "", empty())
        )
        assertEquals("""[4,"-1","RpcFrameworkError","",{}]""", text)
    }
}
