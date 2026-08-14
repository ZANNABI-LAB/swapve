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

        // 원문은 기록에 남는다 (PLAN §11.1).
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
        // PLAN §4.5 S02.FR.04 — 재고 판정은 스테이션이 한다. CSMS 는 그 답을 그대로 받는다.
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
}
