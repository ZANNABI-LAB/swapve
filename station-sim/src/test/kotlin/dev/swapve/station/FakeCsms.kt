package dev.swapve.station

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.ocpp.json.OcppDateTime
import dev.swapve.ocpp.rpc.MessageType
import dev.swapve.ocpp.rpc.RpcErrorCode
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.schema.PayloadValidation
import dev.swapve.ocpp.session.InMemoryOcppEventLog
import dev.swapve.ocpp.session.InboundCallLedger
import dev.swapve.ocpp.session.InboundResponse
import dev.swapve.ocpp.session.MessageDirection
import dev.swapve.ocpp.session.OcppCall
import dev.swapve.ocpp.session.OcppEventRecord
import dev.swapve.ocpp.session.OcppResult
import dev.swapve.ocpp.session.OcppSession
import dev.swapve.ocpp.session.StationSerializer
import dev.swapve.ocpp.session.TransmitOutcome
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.ocpp.swap.VariableRef
import dev.swapve.swap.IdToken
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 컴파일된 스키마 181개를 시험 전체가 공유한다.
 *
 * 가짜 CSMS 와 시뮬레이터가 각자 만들면 시험 클래스마다 그만큼 다시 파싱한다.
 * 운영에서도 인스턴스 하나를 공유하는 것이 전제다 (`OcppSession` KDoc).
 */
val fakeCsmsValidator = OcppPayloadValidator()

/** 고정 시각. `BootNotificationResponse.currentTime` 이 결정적이어야 기록을 그대로 비교할 수 있다. */
val FAKE_CSMS_CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-22T09:00:00Z"), ZoneOffset.UTC)

/**
 * 시뮬레이터 맞은편에 세우는 **가짜 CSMS**.
 *
 * ### 프레임을 손으로 만들지 않는다
 *
 * CSMS 쪽도 `ocpp-core` 의 [OcppSession] 을 그대로 쓴다. 문자열을 조립해 답을 만들면 시험이
 * 확인하는 것은 "우리가 적은 답을 우리가 읽었다"뿐이고, 스테이션이 내보낸 페이로드가 공식
 * `<Action>Request` 스키마를 통과하는지도, 우리가 돌려준 답이 `<Action>Response` 스키마를
 * 통과하는지도 아무도 보지 않는다. 세션 둘을 맞물리면 그 검증이 양쪽에서 자동으로 돈다.
 *
 * ### 원장·직렬화기·이벤트 로그는 연결 밖에 있다
 *
 * 연결마다 CSMS 쪽 세션을 **새로 연다** — 그것이 재접속의 실제 모습이고, [InboundCallLedger]
 * 만 그 위를 가로질러 살아남는다. F6 재전송이 저장된 응답을 그대로 받는 것은 그 배치 덕이다
 * (`MinimalSetupExample` 이 같은 결론을 적어 두었다).
 *
 * ### ★ "답하지 않음"은 **전송 경계**에서 만든다
 *
 * [Mode.SILENT] 는 프레임을 받아 두되 CSMS 세션에 넘기지 않는다. CSMS 쪽 `onCall` 을 매달아
 * 흉내 내려 하면 타임아웃이 아니라 **교착**이 된다: `station.call → transmit →
 * csms.receive → onCall` 이 같은 코루틴의 동기 사슬이라, `onCall` 이 돌아오지 않으면
 * `transmit` 도 돌아오지 않고 스테이션 쪽 `withTimeoutOrNull` 은 시작조차 못 한다.
 * 답이 오지 않는다는 사실은 **선로에서** 만들어야 세션이 그것을 시간으로 관측한다.
 *
 * ### [Mode.BROKEN] 은 던진다
 *
 * `StationTransport.send` 는 `Unit` 을 돌려주므로 전송 실패를 값으로 말할 자리가 없다.
 * [TransmitOutcome.Gone] 은 시뮬레이터의 `transmitOrGone` 이 예외를 옮겨 담아야 생기고,
 * 그래서 이 모드가 **예외를 던지는** 방식으로만 그 경로를 열 수 있다.
 */
class FakeCsms(
    val stationId: String,
    private val clock: Clock = FAKE_CSMS_CLOCK,
) : StationTransportFactory {

    /** 이 CSMS 가 선로에서 하는 일. 시험 도중에 바뀔 수 있다. */
    enum class Mode {

        /** 받아서 세션에 넘기고 답한다. 정상 운영이다. */
        ANSWERING,

        /** 받아 두기만 하고 세션에 넘기지 않는다 — 스테이션 쪽에서 보면 **답이 오지 않는다.** */
        SILENT,

        /** 보내기가 실패한다. 시뮬레이터 쪽에서 [TransmitOutcome.Gone] 이 되는 유일한 길이다. */
        BROKEN,
    }

    var mode: Mode = Mode.ANSWERING

    /** CSMS 가 본 것 전부. `INBOUND` 는 스테이션 → CSMS 다. */
    val eventLog = InMemoryOcppEventLog()

    /** [Mode.SILENT] 로 받아 두기만 한 프레임 원문. */
    val withheld = mutableListOf<String>()

    /** 열린 순서대로의 연결 URL. 재접속이 실제로 새 연결이었는지 센다. */
    val opened = mutableListOf<String>()

    /**
     * 스테이션의 CALL 을 상위 계층이 실제로 처리한 횟수.
     *
     * 멱등 원장이 잡아낸 재전송은 `onCall` 을 지나지 않으므로 이 수가 늘지 않는다 — F6 이
     * 확인하는 것이 정확히 그 차이다.
     */
    var handledCalls: Int = 0
        private set

    /**
     * 스테이션이 보낸 CALL 에 무엇으로 답할지. 기본값은 [defaultResponse] 다.
     *
     * 시험이 갈아 끼우면 거부·오답을 만들 수 있다.
     */
    var onStationCall: suspend (OcppCall) -> InboundResponse = { defaultResponse(it) }

    private val ledger = InboundCallLedger()
    private val serializer = StationSerializer()

    /** 열려 있는 전송이 넘겨준 스테이션 쪽 수신구. 닫히면 `null` 이 된다. */
    private var stationInbound: (suspend (String) -> Unit)? = null

    private var current: Connection? = null

    // ------------------------------------------------------------------ 전송 팩토리

    override suspend fun open(
        url: String,
        authorization: String?,
        onText: suspend (String) -> Unit,
    ): StationTransport {
        opened += url
        stationInbound = onText

        val session = OcppSession(
            stationId = stationId,
            transmit = { text ->
                val deliver = stationInbound
                if (deliver == null) {
                    TransmitOutcome.Gone("스테이션 쪽 연결이 없다: $stationId")
                } else {
                    deliver(text)
                    TransmitOutcome.Delivered
                }
            },
            onCall = { _, call ->
                handledCalls++
                onStationCall(call)
            },
            eventSink = eventLog,
            ledger = ledger,
            serializer = serializer,
            clock = clock,
            validator = fakeCsmsValidator,
        )

        return Connection(session).also { current = it }
    }

    /**
     * 연결 하나.
     *
     * [send] 가 스테이션 → CSMS 방향이고, 그 반대 방향은 세션의 `transmit` 이 [stationInbound]
     * 로 직접 간다. 소켓이 없어도 왕복이 성립하는 것은 그 두 갈래가 서로를 부르기 때문이다.
     */
    inner class Connection(val session: OcppSession) : StationTransport {

        override var isOpen: Boolean = true
            private set

        /** 실제 협상값과 같은 것을 답한다 (Part 4 §3.1.2). */
        override val subprotocol: String get() = WebSocketTransport.SUBPROTOCOL

        override suspend fun send(text: String) {
            when (mode) {
                Mode.BROKEN -> throw IOException(BROKEN_REASON)
                Mode.SILENT -> withheld += text
                Mode.ANSWERING -> session.receive(text)
            }
        }

        override fun close() {
            isOpen = false
            session.close()
            if (current === this) {
                current = null
                stationInbound = null
            }
        }
    }

    // ------------------------------------------------------------------ CSMS → 스테이션

    /**
     * CSMS 가 스테이션에 CALL 하나를 보낸다.
     *
     * 보내기 전에 우리가 만든 페이로드를 공식 `<Action>Request` 스키마로 자기 검증한다 —
     * 시뮬레이터의 `call` 과 같은 태도다. 여기서 걸리면 원인이 시험계라는 사실이 그 자리에서
     * 드러난다.
     */
    suspend fun call(action: String, payload: ObjectNode): OcppResult {
        val connection = current ?: error("열린 연결이 없다: $stationId")

        val validation = fakeCsmsValidator.validateCall(action, payload)
        if (validation is PayloadValidation.Invalid) {
            error("가짜 CSMS 가 만든 ${action}Request 가 공식 스키마를 통과하지 못했다: ${validation.errorDescription}")
        }

        return connection.session.call(OcppCall(action, payload))
    }

    // ------------------------------------------------------------------ 관측

    /** 스테이션에서 온 그 action 의 CALL 기록. 원문이 그대로 남아 있다. */
    fun received(action: String): List<OcppEventRecord> = receivedCalls().filter { it.action == action }

    /** 스테이션에서 온 CALL 의 action 을 도착 순서대로. */
    fun receivedActions(): List<String?> = receivedCalls().map { it.action }

    /**
     * 스테이션 → CSMS 로 온 **CALL 프레임만**.
     *
     * 우리가 보낸 CALL 의 CALLRESULT 도 방향으로는 `INBOUND` 이고, 세션이 그 자리에 원래
     * CALL 의 action 을 채워 넣는다. 방향만으로 세면 `GetVariables` 를 한 번 보냈는데
     * "`GetVariables` 를 한 번 받았다"가 되므로, 프레임 종류로 가른다.
     */
    private fun receivedCalls(): List<OcppEventRecord> = eventLog.of(stationId)
        .filter {
            it.direction == MessageDirection.INBOUND &&
                frameMapper.readTree(it.payload).get(0).asInt() == MessageType.CALL.number
        }

    // ------------------------------------------------------------------ 기본 응답기

    /**
     * 스테이션이 보내는 CALL 마다 **스키마가 요구하는 것만** 답한다.
     *
     * `BootNotificationResponse` 는 `status`·`currentTime`·`interval` 셋이 필수고,
     * `AuthorizeResponse` 는 `idTokenInfo` 가 필수다. 나머지 다섯은 필수 필드가 없어 빈
     * 응답이 정답이다 (*"Empty response by CSMS to confirm receipt"*).
     *
     * 모르는 action 은 [RpcErrorCode.NotImplemented] 로 답한다. 아무것에나 빈 응답을 주면
     * 스테이션이 보내지 말아야 할 것을 보내도 시험이 초록으로 지나간다.
     */
    private fun defaultResponse(call: OcppCall): InboundResponse = when (call.action) {
        BatterySwapWire.BOOT_NOTIFICATION -> InboundResponse.Respond(
            node().apply {
                put("status", BatterySwapWire.REGISTRATION_ACCEPTED)
                put("currentTime", OcppDateTime.format(clock.instant()))
                put("interval", HEARTBEAT_INTERVAL_SECONDS)
            },
        )

        BatterySwapWire.AUTHORIZE -> InboundResponse.Respond(
            node().apply {
                putObject("idTokenInfo").put("status", BatterySwapWire.AUTHORIZATION_ACCEPTED)
            },
        )

        BatterySwapWire.NOTIFY_EVENT,
        BatterySwapWire.SECURITY_EVENT_NOTIFICATION,
        BatterySwapWire.TRANSACTION_EVENT,
        BatterySwapWire.BATTERY_SWAP,
        BatterySwapWire.NOTIFY_REPORT,
        -> InboundResponse.Respond.empty()

        else -> InboundResponse.Fail(
            RpcErrorCode.NotImplemented,
            "이 가짜 CSMS 가 답하지 않는 action: ${call.action}",
        )
    }

    private fun node(): ObjectNode = JsonNodeFactory.instance.objectNode()

    companion object {

        /** [Mode.BROKEN] 이 던지는 사유. 시뮬레이터가 `Gone` 에 옮겨 담는 문구다. */
        const val BROKEN_REASON = "가짜 CSMS 의 선로가 끊겼다"

        /** `BootNotificationResponse.interval` — 필수 필드라 값이 있어야 한다. */
        const val HEARTBEAT_INTERVAL_SECONDS = 300
    }
}

// ------------------------------------------------------------------ 시험 공용

private val frameMapper = ObjectMapper()

/** CALL 프레임 `[2,"id","Action",{payload}]` 에서 페이로드를 꺼낸다. */
fun callPayloadOf(record: OcppEventRecord): ObjectNode =
    frameMapper.readTree(record.payload).get(3) as ObjectNode

// ------------------------------------------------------------------ CSMS → 스테이션 페이로드

/**
 * CSMS 가 보내는 요청들.
 *
 * 식별자 인코딩은 [VariableRef.writeTo] 한 곳을 지난다 — 조회하는 쪽과 답하는 쪽이 같은
 * 코드를 써야 인스턴스를 빠뜨린 쪽이 조용히 다른 변수를 가리키는 일이 없다.
 */
object CsmsPayloads {

    /** `RequestBatterySwapRequest` — `requestId` 와 `idToken` 이 둘 다 필수다 (공식 스키마). */
    fun requestBatterySwap(requestId: Int, idToken: IdToken): ObjectNode = node().apply {
        put("requestId", requestId)
        putObject("idToken").apply {
            put("idToken", idToken.idToken)
            put("type", idToken.type)
        }
    }

    fun getVariables(refs: List<VariableRef>): ObjectNode = node().apply {
        val array = putArray("getVariableData")
        refs.forEach { ref -> ref.writeTo(array.addObject()) }
    }

    /** `SetVariableDataType` 는 `attributeValue` 가 필수다. 값은 언제나 문자열이다. */
    fun setVariables(assignments: List<Pair<VariableRef, String>>): ObjectNode = node().apply {
        val array = putArray("setVariableData")
        assignments.forEach { (ref, value) ->
            array.addObject().apply {
                put("attributeValue", value)
                ref.writeTo(this)
            }
        }
    }

    fun getBaseReport(requestId: Int, reportBase: String): ObjectNode = node().apply {
        put("requestId", requestId)
        put("reportBase", reportBase)
    }

    /** `ResetRequest` — 스키마가 있는 실제 action 이지만 시뮬레이터가 구현하지 않은 것이다. */
    fun reset(type: String): ObjectNode = node().apply { put("type", type) }

    private fun node(): ObjectNode = JsonNodeFactory.instance.objectNode()
}

/**
 * 시험이 쓰는 스테이션 구성.
 *
 * 슬롯 1 은 비어 있는 투입 자리, 슬롯 2 는 내줄 배터리가 든 반출 자리다 — 교환 1건의
 * 최소 형태이고 `StationSimConfig` 의 장부 불변식(투입 수 = 반출 수)을 만족한다.
 */
fun testConfig(
    stationId: String = "CS-FAKE",
    swapOrder: SwapOrder = SwapOrder.IN_OUT,
    targetSoC: Int = 80,
    maxSoc: Int = 100,
    dispensedSoC: Double = 60.0,
    chargingIdToken: String? = null,
): StationSimConfig = StationSimConfig(
    csmsUrl = "ws://localhost:8080/ocpp",
    stationId = stationId,
    password = "s3cret",
    slots = listOf(
        SlotConfig(1),
        SlotConfig(2, SimBattery("BAT-OUT", soC = dispensedSoC, soH = 98.0)),
    ),
    idToken = IdToken("RFID-FAKE", "ISO14443"),
    requestId = 42,
    insertSlots = listOf(1),
    dispenseSlots = listOf(2),
    incomingBatteries = listOf(SimBattery("BAT-IN", soC = 12.0, soH = 90.0)),
    swapOrder = swapOrder,
    chargingIdToken = chargingIdToken,
    targetSoC = targetSoC,
    maxSoc = maxSoc,
)

/** [csms] 를 전송으로 삼는 시뮬레이터. 시각과 검증기를 공유해 결정적으로 돈다. */
fun stationOn(
    csms: FakeCsms,
    config: StationSimConfig = testConfig(csms.stationId),
): StationSimulator = StationSimulator(
    config = config,
    clock = FAKE_CSMS_CLOCK,
    validator = fakeCsmsValidator,
    openTransport = csms,
)
