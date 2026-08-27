package dev.swapve.ocpp.example

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.session.InMemoryOcppEventLog
import dev.swapve.ocpp.session.InboundCallLedger
import dev.swapve.ocpp.session.InboundResponse
import dev.swapve.ocpp.session.OcppCall
import dev.swapve.ocpp.session.OcppResult
import dev.swapve.ocpp.session.OcppSession
import dev.swapve.ocpp.session.TransmitOutcome
import dev.swapve.ocpp.session.StationSerializer
import dev.swapve.ocpp.swap.BatterySwapWire
import kotlinx.coroutines.test.runTest
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ★ **소비자 관점의 최소 예제.** 시험으로 두어 실제로 도는 것을 보장한다.
 *
 * 여기서는 `TestConnection` 같은 내부 헬퍼를 쓰지 않는다 — 좌표로 받은 사람에게는 그것이
 * 없기 때문이다. **공개 API 만으로 무엇을 준비해야 하는지**가 그대로 드러나야 한다.
 *
 * 그리고 소켓이 없다. 두 세션의 `transmit` 을 서로에게 연결하면 전송 계층 없이 왕복이
 * 성립한다 — 세션이 I/O 를 모르기 때문에 가능한 일이고, 소비자가 자기 전송을 붙이기 전에
 * 프로토콜 흐름만 따로 시험할 수 있다는 뜻이기도 하다.
 *
 * ### 이 예제가 측정한 것
 *
 * 세션 하나를 열기까지 소비자가 **직접 만들어 수명을 관리해야 하는 것이 다섯**이다 —
 * `ledger` · `serializer` · `eventSink` · `validator` · `clock`. 그런데 그중 넷은 정답이
 * 사실상 하나뿐이다: 원장과 직렬화기는 스테이션 단위로, 검증기와 코덱은 전역으로 공유한다.
 *
 * [dev.swapve.ocpp.session.OcppSessions] 가 그 넷을 들고 있으므로, 같은 일이 이렇게 줄어든다:
 *
 * ```
 * val sessions = OcppSessions(clock, eventSink = myEventLog)
 * val session = sessions.open(stationId, transmit = { ws.send(it); TransmitOutcome.Delivered }, onCall = ::handle)
 * ```
 *
 * **이 예제는 일부러 손으로 조립한 쪽을 남겨 둔다.** 팩토리가 감추는 것이 무엇인지 보여
 * 주기 위해서이고, 세밀한 제어가 필요한 소비자에게 그 길이 여전히 열려 있음을 확인하기
 * 위해서다. 팩토리 쪽 성질은 `OcppSessionsTest` 가 따로 검증한다.
 */
class MinimalSetupExample {

    private val mapper = ObjectMapper()

    private fun obj(json: String): ObjectNode = mapper.readTree(json) as ObjectNode

    @Test
    fun `소켓 없이 CSMS 와 스테이션이 교환 한 건을 주고받는다`() = runTest {
        // ── 공유물. 세션보다 오래 살아야 하는 것들이다 ─────────────────────────────
        // ledger 와 serializer 는 stationId 로 잡히므로 재접속으로 세션이 바뀌어도 이어진다.
        // validator 는 181 개 스키마를 캐시하므로 세션마다 새로 만들면 그만큼 다시 파싱한다.
        val clock = Clock.systemUTC()
        val validator = OcppPayloadValidator()
        val csmsLedger = InboundCallLedger()
        val csmsSerializer = StationSerializer()
        val csmsLog = InMemoryOcppEventLog()

        val stationLedger = InboundCallLedger()
        val stationSerializer = StationSerializer()
        val stationLog = InMemoryOcppEventLog()

        // ── 두 세션을 서로에게 연결한다 ──────────────────────────────────────────
        lateinit var station: OcppSession

        val csms = OcppSession(
            stationId = STATION_ID,
            transmit = { line -> station.receive(line); TransmitOutcome.Delivered },
            onCall = { _, call ->
                // CSMS 가 받는 CALL. BatterySwap 은 빈 응답이 정답이다
                // ("Empty response by CSMS to confirm receipt").
                assertEquals(BatterySwapWire.BATTERY_SWAP, call.action)
                InboundResponse.Respond.empty()
            },
            eventSink = csmsLog,
            ledger = csmsLedger,
            serializer = csmsSerializer,
            clock = clock,
            validator = validator,
        )

        station = OcppSession(
            stationId = STATION_ID,
            transmit = { line -> csms.receive(line); TransmitOutcome.Delivered },
            onCall = { _, call ->
                // 스테이션이 받는 CALL. S02 원격 개시를 받아들인다.
                assertEquals(BatterySwapWire.REQUEST_BATTERY_SWAP, call.action)
                InboundResponse.Respond(obj("""{"status": "${BatterySwapWire.GENERIC_ACCEPTED}"}"""))
            },
            eventSink = stationLog,
            ledger = stationLedger,
            serializer = stationSerializer,
            clock = clock,
            validator = validator,
        )

        // ── ① CSMS → 스테이션. 원격 개시 (S02) ───────────────────────────────────
        val started = csms.call(
            OcppCall(
                BatterySwapWire.REQUEST_BATTERY_SWAP,
                obj("""{"requestId": $REQUEST_ID, "idToken": {"idToken": "RFID-0001", "type": "ISO14443"}}"""),
            ),
        )

        val accepted = assertIs<OcppResult.Accepted>(started, "스테이션이 받아들이지 않았다: $started")
        assertEquals(BatterySwapWire.GENERIC_ACCEPTED, accepted.payload.path("status").asText())

        // ── ② 스테이션 → CSMS. 헌 배터리가 들어왔다 (S03) ────────────────────────
        val reported = station.call(
            OcppCall(
                BatterySwapWire.BATTERY_SWAP,
                obj(
                    """
                    {
                      "eventType": "${BatterySwapWire.BATTERY_IN}",
                      "requestId": $REQUEST_ID,
                      "idToken": {"idToken": "RFID-0001", "type": "ISO14443"},
                      "batteryData": [{"evseId": 1, "serialNumber": "BAT-1", "soC": 12, "soH": 91}]
                    }
                    """.trimIndent(),
                ),
            ),
        )

        assertIs<OcppResult.Accepted>(reported, "CSMS 가 받아들이지 않았다: $reported")

        // ── ③ 오간 것은 전부 이벤트 로그에 원문으로 남는다 ────────────────────────
        val csmsRecords = csmsLog.of(STATION_ID)
        assertEquals(4, csmsRecords.size, "CSMS 쪽 기록: 발신 2 + 수신 2")
        assertTrue(
            csmsRecords.any { it.action == BatterySwapWire.BATTERY_SWAP && it.payload.contains("BAT-1") },
            "배터리 일련번호가 원문 그대로 남아야 한다",
        )
    }

    /**
     * ★ **재접속을 넘어 멱등이 지켜진다** — 소비자가 무엇을 해야 하는지 보여 준다.
     *
     * 스테이션은 응답을 못 받으면 재전송한다. 그 사이 WebSocket 이 끊겼다 다시 붙으면
     * **세션 객체는 새것이지만 스테이션은 같다.** 이때 `ledger` 를 새로 만들면 재전송이
     * 새 메시지로 보이고, 배터리 장부가 두 번 계상된다.
     *
     * 그래서 `ledger` 와 `serializer` 는 세션 밖에서 만들어 **재접속을 가로질러 넘긴다.**
     * 아래가 그것을 하는 코드 전부다.
     */
    @Test
    fun `재접속 뒤 재전송이 와도 상위 계층은 한 번만 불린다`() = runTest {
        // 이 둘이 세션보다 오래 산다. 키가 stationId 라 재접속을 넘어 이어진다.
        val ledger = InboundCallLedger()
        val serializer = StationSerializer()

        val validator = OcppPayloadValidator()
        val clock = Clock.systemUTC()
        var handledCount = 0

        fun openSession(sent: MutableList<String>) = OcppSession(
            stationId = STATION_ID,
            transmit = { line -> sent += line; TransmitOutcome.Delivered },
            onCall = { _, _ ->
                handledCount++
                InboundResponse.Respond.empty()
            },
            eventSink = InMemoryOcppEventLog(),
            ledger = ledger,
            serializer = serializer,
            clock = clock,
            validator = validator,
        )

        val batterySwapCall = mapper.createArrayNode().apply {
            add(2)
            add(MESSAGE_ID)
            add(BatterySwapWire.BATTERY_SWAP)
            add(
                obj(
                    """
                    {
                      "eventType": "${BatterySwapWire.BATTERY_IN}",
                      "requestId": $REQUEST_ID,
                      "idToken": {"idToken": "RFID-0001", "type": "ISO14443"},
                      "batteryData": [{"evseId": 1, "serialNumber": "BAT-1", "soC": 12, "soH": 91}]
                    }
                    """.trimIndent(),
                ),
            )
        }.toString()

        // ── 첫 연결 ──────────────────────────────────────────────────────────────
        val firstSent = mutableListOf<String>()
        val first = openSession(firstSent)
        first.receive(batterySwapCall)

        assertEquals(1, handledCount, "처음 온 메시지는 상위 계층이 처리한다")
        assertEquals(1, firstSent.size)

        // ── 연결이 끊긴다. 멱등 원장은 건드리지 않는다 ────────────────────────────
        first.close()

        // ── 재접속. 세션은 새것이고 ledger·serializer 는 그대로다 ─────────────────
        val secondSent = mutableListOf<String>()
        val second = openSession(secondSent)
        second.receive(batterySwapCall)

        assertEquals(1, handledCount, "재전송으로 상위 계층이 다시 불렸다 — 장부가 두 번 계상된다")
        assertEquals(1, secondSent.size, "재전송에도 응답은 나가야 한다")
        assertEquals(firstSent[0], secondSent[0], "저장된 응답을 원문 그대로 다시 보낸다")
    }

    private companion object {
        const val STATION_ID = "ST-EXAMPLE"
        const val REQUEST_ID = 1234
        const val MESSAGE_ID = "b8f1e3c2-0000-4000-8000-000000000001"
    }
}
