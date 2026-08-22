package dev.swapve.ocpp.session

import dev.swapve.ocpp.rpc.OcppFrameCodec
import dev.swapve.ocpp.rpc.RpcErrorCode
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 수신 경로 — M1 디코딩 → M2 스키마 검증 → 상위 계층.
 *
 * 각 단계에서 막혔을 때 무엇으로 회신하는지가 세션의 정책이다. M1·M2 는 판정만 하고 회신하지
 * 않는다 (`DecodeOutcome` · `PayloadValidation` KDoc).
 */
class OcppSessionInboundTest {

    @Test
    fun `손상 프레임을 받으면 CALLERROR 로 회신하고 세션은 살아 있다`() = runTest {
        val connection = TestConnection()

        connection.session.receive("이건 JSON 이 아니다")
        advanceUntilIdle()

        val reply = connection.lastSent()
        assertEquals(4, typeNumberOf(reply))
        // messageId 조차 읽을 수 없으면 "-1" 이다 (Part 4 §4.2.3).
        assertEquals(OcppFrameCodec.UNREADABLE_MESSAGE_ID, messageIdOf(reply))
        assertEquals(RpcErrorCode.RpcFrameworkError.name, errorCodeOf(reply))

        // 세션은 멀쩡하다.
        connection.session.receive(callText("m1", "BatterySwap", batterySwapPayload(1, "BatteryIn")))
        advanceUntilIdle()
        assertEquals(3, typeNumberOf(connection.lastSent()))
    }

    @Test
    fun `messageId 는 읽히지만 구조가 틀리면 그 id 로 CALLERROR 를 회신한다`() = runTest {
        val connection = TestConnection()

        connection.session.receive("""[2,"m9","BatterySwap"]""")
        advanceUntilIdle()

        val reply = connection.lastSent()
        assertEquals("m9", messageIdOf(reply))
        assertEquals(RpcErrorCode.ProtocolError.name, errorCodeOf(reply))
    }

    @Test
    fun `스키마 위반 페이로드는 M2 가 정한 errorCode 로 CALLERROR 가 된다`() = runTest {
        var handlerCalls = 0
        val connection = TestConnection(onCall = { _, _ -> handlerCalls++; InboundResponse.Respond.empty() })

        // requestId 누락 → 필수 필드 누락 → OccurrenceConstraintViolation (M2 정책)
        val broken = batterySwapPayload(1, "BatteryIn").apply { remove("requestId") }
        connection.session.receive(callText("m1", "BatterySwap", broken))
        advanceUntilIdle()

        val reply = connection.lastSent()
        assertEquals(4, typeNumberOf(reply))
        assertEquals("m1", messageIdOf(reply))
        assertEquals(RpcErrorCode.OccurrenceConstraintViolation.name, errorCodeOf(reply))
        assertEquals(0, handlerCalls, "스키마를 통과하지 못한 메시지가 상위 계층까지 갔다")
    }

    @Test
    fun `enum 밖의 값은 PropertyConstraintViolation 이다`() = runTest {
        val connection = TestConnection()

        val broken = batterySwapPayload(1, "BatteryIn").put("eventType", "BatterySideways")
        connection.session.receive(callText("m1", "BatterySwap", broken))
        advanceUntilIdle()

        assertEquals(RpcErrorCode.PropertyConstraintViolation.name, errorCodeOf(connection.lastSent()))
    }

    @Test
    fun `모르는 action 은 NotImplemented 다`() = runTest {
        val connection = TestConnection()

        connection.session.receive(callText("m1", "MakeCoffee", emptyObj()))
        advanceUntilIdle()

        assertEquals(RpcErrorCode.NotImplemented.name, errorCodeOf(connection.lastSent()))
    }

    @Test
    fun `알 수 없는 메시지 타입은 회신 없이 무시되고 세션은 정상이다`() = runTest {
        val connection = TestConnection()

        // Part 4 §4.1.3 + errata 2026-06 §4.1 — 메시지 전체를 무시한다. CALLERROR 도 보내지 않는다.
        connection.session.receive("""[99,"m1","BatterySwap",{}]""")
        advanceUntilIdle()
        assertTrue(connection.sent.isEmpty(), "미지의 메시지 타입에 회신하면 안 된다")

        // 원문은 기록에 남는다.
        val logged = connection.log.of(connection.stationId).single()
        assertEquals(MessageDirection.INBOUND, logged.direction)
        assertEquals(OcppFrameCodec.UNREADABLE_MESSAGE_ID, logged.messageId)
        assertTrue(logged.payload.contains("99"))

        connection.session.receive(callText("m2", "BatterySwap", batterySwapPayload(1, "BatteryIn")))
        advanceUntilIdle()
        assertEquals(3, typeNumberOf(connection.lastSent()))
    }

    @Test
    fun `SEND 는 처리하되 회신하지 않는다`() = runTest {
        val received = mutableListOf<String>()
        val connection = TestConnection(onSend = { _, send -> received += send.action })

        connection.session.receive(sendText("m1", "NotifyPeriodicEventStream", periodicEventStreamPayload()))
        advanceUntilIdle()

        assertEquals(listOf("NotifyPeriodicEventStream"), received)
        // Part 4 §4.2.4 — SEND 에 CALLRESULT/CALLERROR 로 응답해서는 안 된다(SHALL NOT).
        assertTrue(connection.sent.isEmpty())
    }

    @Test
    fun `스키마를 통과하지 못한 SEND 도 회신하지 않는다`() = runTest {
        val received = mutableListOf<String>()
        val connection = TestConnection(onSend = { _, send -> received += send.action })

        connection.session.receive(sendText("m1", "NotifyPeriodicEventStream", obj("""{"id": "숫자가 아니다"}""")))
        advanceUntilIdle()

        assertTrue(received.isEmpty(), "스키마를 통과하지 못한 SEND 가 상위 계층까지 갔다")
        assertTrue(connection.sent.isEmpty(), "SEND 에는 어떤 경우에도 회신하지 않는다")
    }

    @Test
    fun `CALLRESULT 는 원래 CALL 의 action 스키마로 검증된다`() = runTest {
        val connection = TestConnection()

        val result = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload())) }
        runCurrent()
        val messageId = messageIdOf(connection.sent[0])

        // RequestBatterySwapResponse 는 status 가 필수다. CALLRESULT 프레임에는 action 이
        // 없으므로, pending 표에서 찾은 "RequestBatterySwap" 이 없으면 이 검증이 불가능하다.
        connection.session.receive(callResultText(messageId, obj("""{"statusInfo": {"reasonCode": "Ok"}}""")))
        advanceUntilIdle()

        val invalid = assertIs<OcppResult.InvalidResponse>(result.await())
        assertEquals(RpcErrorCode.OccurrenceConstraintViolation, invalid.errorCode)
        assertTrue(invalid.errorDescription.contains("RequestBatterySwapResponse"))

        // Part 4 §4.2.5 — 응답 처리 실패는 CALLRESULTERROR(5) 로 알린다.
        val reply = connection.lastSent()
        assertEquals(5, typeNumberOf(reply))
        assertEquals(messageId, messageIdOf(reply))
    }

    @Test
    fun `스키마를 통과한 CALLRESULT 는 그대로 전달된다`() = runTest {
        val connection = TestConnection()

        val result = async { connection.session.call(OcppCall("RequestBatterySwap", requestBatterySwapPayload())) }
        runCurrent()
        connection.session.receive(callResultText(messageIdOf(connection.sent[0]), requestBatterySwapRejected()))
        advanceUntilIdle()

        val accepted = assertIs<OcppResult.Accepted>(result.await())
        // S02.FR.04 — 재고 판정은 스테이션이 한다. CSMS 는 그 답을 그대로 받는다.
        assertEquals("Rejected", accepted.payload.get("status").textValue())
        assertEquals("NoBatteryAvailable", accepted.payload.get("statusInfo").get("reasonCode").textValue())
    }

    @Test
    fun `상위 계층이 CALLERROR 를 고르면 그대로 회신한다`() = runTest {
        val connection = TestConnection(
            onCall = { _, _ -> InboundResponse.Fail(RpcErrorCode.SecurityError, "unknown station credentials") },
        )

        connection.session.receive(callText("m1", "BatterySwap", batterySwapPayload(1, "BatteryIn")))
        advanceUntilIdle()

        val reply = connection.lastSent()
        assertEquals(4, typeNumberOf(reply))
        assertEquals(RpcErrorCode.SecurityError.name, errorCodeOf(reply))
    }

    @Test
    fun `상위 계층이 예외로 끝나면 InternalError 로 회신한다`() = runTest {
        val connection = TestConnection(onCall = { _, _ -> error("장부가 깨졌다") })

        connection.session.receive(callText("m1", "BatterySwap", batterySwapPayload(1, "BatteryIn")))
        advanceUntilIdle()

        assertEquals(RpcErrorCode.InternalError.name, errorCodeOf(connection.lastSent()))
    }

    private fun periodicEventStreamPayload() = obj(
        """{"id": 1, "pending": 0, "basetime": "2026-08-14T09:00:00Z", "data": [{"t": 0, "v": "23"}]}""",
    )
    /**
     * ★ **닫힌 세션은 반쪽만 죽지 않는다.**
     *
     * `closed` 를 발신 쪽만 보던 때가 있었다. 그러면 세션을 닫은 뒤 전송만 다시 열었을 때
     * **상대의 요청에는 계속 답하면서 자기는 아무것도 못 보내는** 상대가 만들어진다. 실물
     * 스테이션에도 실물 CSMS 에도 없는 상태이고, 그런 물건을 만들 수 있는 시험 도구는
     * 언젠가 그 상태를 정상으로 착각하게 만든다.
     *
     * 세션 하나는 연결 하나의 것이다. 상대가 돌아오면 새로 열어야지, 이 객체 밑에 새 전송을
     * 끼우는 것이 아니다.
     */
    @Test
    fun `닫힌 세션은 인바운드 CALL 에 답하지 않는다`() = runTest {
        val connection = TestConnection()

        connection.session.receive(callText("m1", "BatterySwap", batterySwapPayload(1, "BatteryIn")))
        advanceUntilIdle()
        assertEquals(1, connection.sent.size, "열린 세션이 답하지 않았다")

        connection.session.close()
        connection.session.receive(callText("m2", "BatterySwap", batterySwapPayload(2, "BatteryIn")))
        advanceUntilIdle()

        assertEquals(1, connection.sent.size, "닫힌 세션이 답했다")
        assertTrue(
            connection.log.of(connection.stationId).none { it.messageId == "m2" },
            "닫힌 세션이 받은 프레임을 장부에 적었다",
        )
    }

    /** 닫힌 뒤의 발신은 두 갈래가 한 방향이다 — 둘 다 **값으로** 거절한다. */
    @Test
    fun `닫힌 세션은 발신도 거절한다`() = runTest {
        val connection = TestConnection()
        connection.session.close()

        assertIs<OcppResult.NotConnected>(connection.session.call(OcppCall("Heartbeat", emptyObj())))
        assertIs<TransmitOutcome.Gone>(connection.session.send("NotifyPeriodicEventStream", emptyObj()))
        assertTrue(connection.sent.isEmpty(), "닫힌 세션에서 프레임이 나갔다")
    }

    /**
     * ★ **죽은 전송은 예외가 아니라 [OcppResult.NotConnected] 다.**
     *
     * 이 시험이 성립한다는 것 자체가 요점이다 — 소켓 없이 "보낼 수 없는 상태"를 만들 수 있는
     * 것은 [OcppTransmit] 이 결과를 돌려주기 때문이다. 예전에는 전송이 예외를 던졌고,
     * `call()` 은 그것을 그대로 올려보내면서 *"Never throws"* 라고 적어 두고 있었다.
     */
    @Test
    fun `전송이 Gone 이면 call 은 NotConnected 를 돌려준다`() = runTest {
        val connection = TestConnection(onTransmit = { TransmitOutcome.Gone("소켓이 죽었다") })

        val result = connection.session.call(OcppCall("Heartbeat", emptyObj()))

        assertIs<OcppResult.NotConnected>(result)
        // 나간 것으로 **기록**은 남는다. 기록됐는데 안 나간 쪽이 그 반대보다 낫다 (emitRaw KDoc).
        assertEquals(1, connection.log.of(connection.stationId).count { it.direction == MessageDirection.OUTBOUND })
    }

    /** 못 보낸 응답은 조용히 버린다 — 물어본 그 연결만이 답을 나를 수 있고, 원장이 답을 들고 있다. */
    @Test
    fun `응답을 못 보내도 예외가 아니고 원장에는 남는다`() = runTest {
        val connection = TestConnection(onTransmit = { TransmitOutcome.Gone("소켓이 죽었다") })

        connection.session.receive(callText("m1", "BatterySwap", batterySwapPayload(1, "BatteryIn")))
        advanceUntilIdle()

        val claim = connection.ledger.claim(InboundCallKey(connection.stationId, "m1"))
        assertIs<CallClaim.AlreadyAnswered>(claim, "재전송이 가져갈 답이 남아 있지 않다")
    }

}
