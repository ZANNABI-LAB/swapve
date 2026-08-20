package dev.swapve.ocpp.session

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.ocpp.rpc.OcppFrame
import dev.swapve.ocpp.rpc.OcppFrameCodec
import dev.swapve.ocpp.schema.OcppPayloadValidator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import kotlin.time.Duration

/**
 * M4 세션 계층 테스트 공용 도구.
 *
 * **여기에는 실제 시간이 없다.** 시각은 고정 [Clock] 이고 타임아웃은 코루틴 가상 시간이다.
 * 세션이 시각을 직접 조회하지 않기 때문에 가능한 일이고, 그래서 테스트가 결정적이다.
 */

private val mapper = ObjectMapper()

/**
 * 스키마 컴파일 결과를 테스트 전체가 공유한다.
 *
 * 세션마다 새로 만들면 181 개 스키마를 세션 수만큼 다시 파싱한다. 운영에서도 인스턴스
 * 하나를 공유하는 것이 전제다 ([OcppSession] KDoc).
 */
val sharedValidator = OcppPayloadValidator()

val testCodec = OcppFrameCodec()

/** 고정 시각. 이벤트 로그의 `occurredAt` 이 결정적이어야 기록을 그대로 비교할 수 있다. */
val TEST_CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-14T09:00:00Z"), ZoneOffset.UTC)

fun obj(json: String): ObjectNode = mapper.readTree(json) as ObjectNode

fun emptyObj(): ObjectNode = mapper.createObjectNode()

// ------------------------------------------------------------------ 프레임 만들기

fun callText(messageId: String, action: String, payload: ObjectNode): String =
    testCodec.encode(OcppFrame.Call(messageId, action, payload))

fun sendText(messageId: String, action: String, payload: ObjectNode): String =
    testCodec.encode(OcppFrame.Send(messageId, action, payload))

fun callResultText(messageId: String, payload: ObjectNode): String =
    testCodec.encode(OcppFrame.CallResult(messageId, payload))

fun callErrorText(messageId: String, errorCode: String, description: String = "nope"): String =
    testCodec.encode(OcppFrame.CallError(messageId, errorCode, description, emptyObj()))

// ------------------------------------------------------------------ 프레임 읽기

fun typeNumberOf(text: String): Int = mapper.readTree(text).get(0).intValue()

fun messageIdOf(text: String): String = mapper.readTree(text).get(1).textValue()

fun actionOf(text: String): String = mapper.readTree(text).get(2).textValue()

/** CALLERROR/CALLRESULTERROR 의 errorCode */
fun errorCodeOf(text: String): String = mapper.readTree(text).get(2).textValue()

fun payloadOf(text: String): ObjectNode = mapper.readTree(text).let {
    (if (it.get(0).intValue() == 3) it.get(2) else it.get(3)) as ObjectNode
}

// ------------------------------------------------------------------ 페이로드 (TC_S_103_CSMS)

/** 배터리 2 개 세트가 공식 시험이다 (결정 #6). */
fun batterySwapPayload(
    requestId: Int,
    eventType: String,
    firstSlot: Int = 1,
    secondSlot: Int = 2,
    firstSerial: String = "1234",
    secondSerial: String = "5678",
    firstSoC: Int = 23,
    secondSoC: Int = 45,
): ObjectNode = obj(
    """
    {
      "eventType": "$eventType",
      "requestId": $requestId,
      "idToken": {"idToken": "1234567890", "type": "ISO14443"},
      "batteryData": [
        {"evseId": $firstSlot, "serialNumber": "$firstSerial", "soC": $firstSoC, "soH": 85},
        {"evseId": $secondSlot, "serialNumber": "$secondSerial", "soC": $secondSoC, "soH": 87}
      ]
    }
    """.trimIndent(),
)

fun requestBatterySwapPayload(requestId: Int = 1234): ObjectNode = obj(
    """
    {
      "requestId": $requestId,
      "idToken": {"idToken": "1234567890", "type": "ISO14443"}
    }
    """.trimIndent(),
)

fun requestBatterySwapAccepted(): ObjectNode = obj("""{"status": "Accepted"}""")

/** S02.FR.04 — 재고 판정은 스테이션이 한다. */
fun requestBatterySwapRejected(): ObjectNode = obj(
    """{"status": "Rejected", "statusInfo": {"reasonCode": "NoBatteryAvailable"}}""",
)

// ------------------------------------------------------------------ 연결 하나

/**
 * 연결 하나를 흉내 낸다.
 *
 * [ledger] · [serializer] · [log] 를 밖에서 받는 이유는 **재접속을 표현하기 위해서**다.
 * 세션은 새로 만들되 이 셋은 그대로 넘기면 그게 F6 상황이다.
 */
class TestConnection(
    val stationId: String = "ST-1",
    val ledger: InboundCallLedger = InboundCallLedger(),
    val serializer: StationSerializer = StationSerializer(),
    val log: InMemoryOcppEventLog = InMemoryOcppEventLog(),
    clock: Clock = TEST_CLOCK,
    callTimeout: Duration = OcppSession.DEFAULT_CALL_TIMEOUT,
    onCall: OcppCallHandler = { _, _ -> InboundResponse.Respond.empty() },
    onSend: OcppSendHandler = { _, _ -> },
    private val onTransmit: suspend (String) -> Unit = {},
) {
    /** 이 연결로 나간 프레임 원문. */
    val sent: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    val session = OcppSession(
        stationId = stationId,
        transmit = { text ->
            sent += text
            onTransmit(text)
        },
        onCall = onCall,
        eventSink = log,
        ledger = ledger,
        serializer = serializer,
        clock = clock,
        onSend = onSend,
        codec = testCodec,
        validator = sharedValidator,
        callTimeout = callTimeout,
    )

    /** 마지막으로 나간 프레임. 없으면 실패한다. */
    fun lastSent(): String = sent.lastOrNull() ?: error("나간 프레임이 없다")
}
