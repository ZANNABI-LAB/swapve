package dev.swapve.csms.console

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.console.ControlledStation
import dev.swapve.console.ControlledStations
import dev.swapve.console.SimConsoleServer
import dev.swapve.console.StationSnapshot
import dev.swapve.console.StationSpec
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.swap.RemoteSwapStart
import dev.swapve.csms.swap.RemoteSwapStarter
import dev.swapve.csms.swap.SwapTransactionRegistry
import dev.swapve.ocpp.swap.BatteryRejectionReason
import dev.swapve.swap.IdToken
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapTransaction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ★ **제어 콘솔의 제어 경로가 실제로 돈다** (B24).
 *
 * ### 화면은 눈으로 보지만 제어 경로는 기계가 확인한다
 *
 * 콘솔이 하는 일은 "HTTP 요청 하나가 실제 WebSocket 위의 교환 1건이 되는 것"이다. 그건
 * 눈으로 보는 것이 아니라 시험할 수 있는 것이고, 시험하지 않으면 데모 당일에 처음 알게 된다.
 * 그래서 **실제 CSMS 를 상대로** 콘솔의 API 를 두드린다 — 붙이고, 교환을 걸고, 완주를
 * 확인하고, 장애를 주입하고, 잘못된 요청에 무엇으로 답하는지 본다.
 *
 * ### 여기 있는 이유
 *
 * 콘솔 자체는 CSMS 를 모른다 (의존 방향은 sim-console → station-sim 한 방향이다). 둘을 한
 * 자리에서 붙일 수 있는 곳은 **CSMS 의 시험 소스셋**뿐이고, 그건 `station-sim` 이 이미
 * 그렇게 쓰이고 있는 것과 같은 사정이다 (`csms/build.gradle.kts`).
 *
 * ### 태그가 없다
 *
 * 적합성도 감사도 아니다. `./gradlew build` 의 L1 게이트에서 돌고, `conformanceTest` 와
 * `auditTest` 의 질문에는 손대지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
class SimConsoleControlTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var swaps: SwapTransactionRegistry

    @Autowired
    private lateinit var remoteSwapStarter: RemoteSwapStarter

    private lateinit var console: SimConsoleServer

    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun startConsole() {
        // 포트 0 — 시험이 고정 포트를 잡으면 병렬 실행이나 사람의 로컬 데모와 부딪힌다.
        console = SimConsoleServer(ControlledStations("ws://localhost:$port/ocpp"), port = 0).start()
    }

    @AfterEach
    fun stopConsole() {
        // 붙어 있는 시뮬레이터까지 함께 닫힌다. 남겨 두면 다음 시험의 CSMS 에 죽은 연결이 남는다.
        console.close()
    }

    // ------------------------------------------------------------------ 정상 경로

    /**
     * ★ 제어 API 로 **스테이션을 띄우고 → 교환을 시작해 → 완주한다.**
     *
     * 완주 판정을 콘솔의 말만 듣고 하지 않는다. CSMS 의 장부에서 같은 교환이
     * [SwapTransaction.Completed] 인지 교차 확인한다 — 콘솔이 "끝났다"고 말하는 것과
     * 관제 서버의 장부가 맞는 것은 다른 일이다.
     *
     * 슬롯의 `chargingSuspended` 도 여기서 함께 본다. 콘솔 경로는 `MaxSoc` 기본값(100)을
     * 쓰므로 상한에 닿을 일이 없다 — 확인하는 것은 **필드가 실리고 `false` 라는 것**이다.
     * 상한 도달 자체를 만들려면 `chargeUntilMaxSoc` 를 여는 새 제어면이 필요하고, 그건 이
     * 콘솔이 하는 일이 아니다.
     */
    @Test
    fun `제어 API 로 붙인 스테이션이 교환 1건을 완주한다`() {
        val stationId = "CS-CONSOLE-SWAP"
        val attached = attach(stationId)

        // 붙자마자 보이는 것 — 투입 슬롯은 비어 있고 반출 슬롯에는 내줄 배터리가 있다.
        assertEquals(4, attached.path("slots").size())
        assertTrue(slot(attached, 1).path("battery").isNull, "투입 슬롯에 배터리가 있다")
        assertEquals("BAT-FULL-3", slot(attached, 3).path("battery").path("serialNumber").asText())

        val (status, _) = post("/api/stations/$stationId/swap", "{}")
        assertEquals(202, status, "교환 시작이 접수되지 않았다")

        val completed = awaitProgress(stationId, "COMPLETED")
        val requestId = completed.path("requestId").asInt()

        // ★ CSMS 의 장부에서 같은 교환이 완주로 남았다.
        val transaction = assertNotNull(swaps.find(StationId(stationId), requestId), "교환이 열리지 않았다")
        val ledger = assertIs<SwapTransaction.Completed>(transaction, "교환이 완료되지 않았다: $transaction")
        assertEquals(2, ledger.batteriesIn.size)
        assertEquals(ledger.batteriesIn.size, ledger.batteriesOut.size)

        // 슬롯이 뒤바뀐 것이 화면에도 그대로 보인다 — 헌 배터리가 들어오고 새 배터리가 나갔다.
        assertEquals("BAT-USED-1", slot(completed, 1).path("battery").path("serialNumber").asText())
        assertTrue(slot(completed, 3).path("battery").isNull, "내준 배터리가 슬롯에 남아 있다")
        assertTrue(completed.path("messageCount").asInt() > 0, "오간 메시지가 없다")

        assertTrue(slot(completed, 1).has("chargingSuspended"), "슬롯에 충전 정지 여부가 없다")
        assertTrue(
            completed.path("slots").none { it.path("chargingSuspended").asBoolean() },
            "상한에 닿지 않았는데 충전이 멈춘 슬롯이 있다",
        )
    }

    // ------------------------------------------------------------------ 연결과 이력

    /**
     * **붙지 않은 스테이션은 서브프로토콜을 아는 척하지 않는다.**
     *
     * 이 상태는 REST 로는 관측되지 않는다 — `ControlledStations.attach` 는 연결에 실패하면
     * 등록을 되돌리므로 `GET /api/state` 에 미연결 스테이션이 실릴 자리가 없다. 그래도 파생
     * 규칙 자체는 성립해야 하므로 (`subprotocol` 은 연결이 없으면 예외를 던진다) 여기서는
     * 콘솔의 스테이션을 직접 지어 스냅샷을 읽는다.
     */
    @Test
    fun `붙지 않은 스테이션은 서브프로토콜을 아는 척하지 않는다`() {
        ControlledStation(StationSpec(csmsUrl = "ws://localhost:$port/ocpp", stationId = "CS-NEVER-ATTACHED")).use {
            val snapshot = it.snapshot()

            assertEquals(false, snapshot.connected)
            assertNull(snapshot.subprotocol, "붙지도 않았는데 협상 결과를 알고 있다")
            assertTrue(snapshot.events.isEmpty(), "오간 것이 없는데 이력이 있다")
        }
    }

    /** 붙었다면 협상 결과가 남는다 — OCPP 2.1 이 아니면 그 위의 모든 것이 무의미하다 (Part 4 §3.1.2). */
    @Test
    fun `붙은 스테이션은 ocpp2_1 로 협상돼 있다`() {
        val attached = attach("CS-CONSOLE-PROTO")

        assertTrue(attached.path("connected").asBoolean(), "붙었다는데 연결이 없다")
        assertEquals("ocpp2.1", attached.path("subprotocol").asText())
    }

    /**
     * ★ **이벤트 꼬리가 최신 것부터 상한까지만 실린다.**
     *
     * 스냅샷은 로그 덤프가 아니다. 되싣는 것은 마지막 [StationSnapshot.EVENT_TAIL] 건이고,
     * **페이로드는 빠진다** — 프레임 한 줄이 수 KB 라 매초 폴링하는 화면이 그것을 감당하지
     * 못한다. 교환 1건이면 부팅만으로도 상한을 채우고 남으므로, 넘치지 않는다는 것이
     * 여기서 실제로 걸린다.
     */
    @Test
    fun `이벤트 꼬리가 최신 것부터 상한까지만 실린다`() {
        val stationId = "CS-CONSOLE-EVENTS"
        attach(stationId)
        post("/api/stations/$stationId/swap", "{}")

        val completed = awaitProgress(stationId, "COMPLETED")
        val events = completed.path("events")

        assertEquals(StationSnapshot.EVENT_TAIL, events.size(), "이벤트 꼬리가 상한과 다르다")
        assertTrue(
            completed.path("messageCount").asInt() >= events.size(),
            "전체 수보다 꼬리가 길다",
        )

        val sequences = events.map { it.path("seq").asLong() }
        assertEquals(sequences.sortedDescending(), sequences, "최신이 앞이 아니다")

        events.forEach { event ->
            assertTrue(event.path("messageId").asText().isNotBlank(), "messageId 가 비어 있다: $event")
            assertContains(listOf("INBOUND", "OUTBOUND"), event.path("direction").asText())
            assertTrue(event.has("occurredAt"), "발생 시각이 없다: $event")
            assertTrue(!event.has("payload"), "스냅샷에 프레임 본문이 실렸다: $event")
        }
    }

    // ------------------------------------------------------------------ 장애 주입

    /**
     * ★ **F1 — 배터리 부족.** 버튼 하나로 걸었을 때 **실제로 그 실패가 난다.**
     *
     * 개시 주체가 CSMS 인 시나리오라 (S02.FR.04) 콘솔은 붙여 놓고 기다린다. 여기서는
     * 사람이 칠 curl 대신 시험이 직접 개시를 건다 — 어느 쪽이든 CSMS 를 지나는 길은 같다.
     */
    @Test
    fun `F1 을 걸면 스테이션이 개시를 거부하고 그 사유가 콘솔에 남는다`() {
        val stationId = "CS-CONSOLE-F1"
        attach(stationId)

        val (status, _) = post("/api/stations/$stationId/swap", """{"fault":"F1"}""")
        assertEquals(202, status)

        // 붙고 부팅까지 끝나야 CSMS 가 이 스테이션을 안다. 그 시점이 이 상태다.
        val waiting = awaitProgress(stationId, "AWAITING_REMOTE_START")
        assertTrue(waiting.path("connected").asBoolean(), "기다린다면서 붙어 있지 않다")
        // 내줄 배터리가 없는 구성으로 다시 붙었다 — 반출 슬롯이 하나도 없다.
        assertTrue(
            waiting.path("slots").none { it.path("role").asText() == "DISPENSE" },
            "F1 인데 반출 슬롯이 있다",
        )
        // 상관 번호는 CSMS 가 발번한다 (S02.FR.02). 개시 전에는 모르는 것이 맞다.
        assertTrue(waiting.path("requestId").isNull, "개시 전인데 상관 번호를 알고 있다")

        val outcome = runBlocking {
            remoteSwapStarter.start(StationId(stationId), IdToken("RFID-0001", "ISO14443"))
        }

        val rejected = assertIs<RemoteSwapStart.Rejected>(outcome, "거부되지 않았다: $outcome")
        assertEquals(BatteryRejectionReason.NO_BATTERY_AVAILABLE, rejected.rejection.reason)

        val snapshot = awaitProgress(stationId, "REJECTED")
        assertContains(snapshot.path("note").asText(), BatteryRejectionReason.NO_BATTERY_AVAILABLE.wireValue)
        assertTrue(
            swaps.rejections().any { it.key.stationId.value == stationId },
            "거부가 CSMS 에 기록되지 않았다",
        )
    }

    /**
     * **F3 — 미등록 배터리.** 구성으로 재현되는 쪽도 도는지 본다.
     *
     * F1 과 갈리는 지점이 여기다: F3 은 교환이 **정상으로 완주하되** 응답에 customData 거부가
     * 붙는다 (§4.3 — `BatterySwapResponse` 는 거부할 수 없다).
     */
    @Test
    fun `F3 을 걸면 등록되지 않은 배터리가 customData 로 거부된다`() {
        val stationId = "CS-CONSOLE-F3"
        attach(stationId)

        post("/api/stations/$stationId/swap", """{"fault":"F3"}""")
        val completed = awaitProgress(stationId, "COMPLETED")

        assertContains(completed.path("note").asText(), BatteryRejectionReason.BATTERY_UNKNOWN.wireValue)
        // 그럼에도 교환 자체는 완주했다 — 거부는 회신에 실린 사실이지 흐름의 중단이 아니다.
        assertIs<SwapTransaction.Completed>(
            swaps.find(StationId(stationId), completed.path("requestId").asInt()),
        )
    }

    // ------------------------------------------------------------------ 수동 조작

    /**
     * ★ **조작은 붙어 있는 그 스테이션에 쌓인다** — 조작마다 시뮬레이터를 다시 짓지 않는다.
     *
     * 이것이 각본(`POST .../swap`)과 갈리는 지점이다. 각본은 매번 새로 짓지만 (구성으로
     * 재현되는 F1·F3 과 "완주한 스테이션은 투입 슬롯이 차 있다"는 두 사정 때문에), 사람이
     * 버튼을 누르는 것은 **누른 그 스테이션을 움직이는 일**이라 결과가 이어져야 한다.
     *
     * 그래서 "이어졌다"를 세 갈래로 확인한다. 다시 지었다면 셋 다 무너진다 —
     * `messageCount` 는 0 부터 다시 세고, `seq` 는 끊기고, 투입 슬롯은 다시 비어 있다.
     */
    @Test
    fun `조작 두 건이 같은 스테이션에 이어서 쌓인다`() {
        val stationId = "CS-CONSOLE-OP-CHAIN"
        val attached = attach(stationId)
        val afterBoot = attached.path("messageCount").asInt()
        assertTrue(slot(attached, 1).path("battery").isNull, "투입 슬롯에 이미 배터리가 있다")

        val (authorizeStatus, authorized) = op(stationId, """{"op":"authorize"}""")
        assertEquals(200, authorizeStatus, "인가가 통하지 않았다: ${authorized.path("error").asText()}")
        val afterAuthorize = authorized.path("messageCount").asInt()
        assertTrue(afterAuthorize > afterBoot, "Authorize 가 오갔는데 메시지가 늘지 않았다")

        val (insertStatus, inserted) = op(stationId, """{"op":"insertBatteries"}""")
        assertEquals(200, insertStatus, "투입이 통하지 않았다: ${inserted.path("error").asText()}")

        // (1) 앞선 조작 위에 이어 셌다.
        assertTrue(
            inserted.path("messageCount").asInt() > afterAuthorize,
            "투입 뒤에도 메시지 수가 인가 시점 그대로다 — 시뮬레이터가 다시 지어졌다",
        )

        // (2) 오간 프레임의 일련번호가 끊기지 않았다.
        val sequences = inserted.path("events").map { it.path("seq").asLong() }
        assertEquals(sequences.sortedDescending(), sequences, "최신이 앞이 아니다")
        assertEquals(
            (sequences.first() downTo sequences.last()).toList(),
            sequences,
            "이벤트 일련번호가 끊겼다 — 로그가 갈아 끼워졌다",
        )

        // (3) 투입한 배터리가 그 슬롯에 남아 있다. 다시 지었다면 빈 슬롯으로 돌아간다.
        assertEquals("BAT-USED-1", slot(inserted, 1).path("battery").path("serialNumber").asText())
        assertEquals("BAT-USED-2", slot(inserted, 2).path("battery").path("serialNumber").asText())

        // 각본이 도는 중이 아니므로 진행 상태는 붙었을 때 그대로다 — 조작은 각본이 아니다.
        assertEquals("ATTACHED", inserted.path("progress").asText())
    }

    /**
     * ★ **콘솔은 순서를 막지 않는다** — `authorize()` 없는 `insertBatteries()` 가 통한다.
     *
     * 그게 F5 다 (docs/VIRTUAL-STATION.md §3). 시뮬레이터의 모든 검사는 스테이션 안의
     * **물리적 사실**을 묻지 우리가 부른 순서를 묻지 않고, 콘솔이 그 위에 순서 게이트를
     * 새로 만들면 릴레이가 붙어버린 실제 스테이션이 내는 프레임을 CSMS 에 보여줄 길이
     * 없어진다. 막는 대신 보내고, 판정은 CSMS 가 한다.
     */
    @Test
    fun `인가 없이 투입해도 콘솔이 막지 않는다`() {
        val stationId = "CS-CONSOLE-OP-F5"
        attach(stationId)

        val (status, inserted) = op(stationId, """{"op":"insertBatteries"}""")

        assertEquals(200, status, "콘솔이 순서를 막았다: ${inserted.path("error").asText()}")
        assertEquals("BAT-USED-1", slot(inserted, 1).path("battery").path("serialNumber").asText())
    }

    /**
     * ★ **각본이 도는 중에는 조작을 받지 않는다.**
     *
     * F1 은 CSMS 의 개시를 기다리며 `AWAITING_REMOTE_START` 에 머문다. 그 위로 수동 조작이
     * 끼어들면 같은 시뮬레이터에 두 손이 얹히고, 그때 화면이 보여주는 상태는 어느 쪽의
     * 결과도 아니게 된다. 거절하는 것은 스테이션이 아니라 **콘솔**이므로 422 가 아니라 409 다.
     */
    @Test
    fun `각본이 도는 중에 조작하면 409 로 답한다`() {
        val stationId = "CS-CONSOLE-OP-BUSY"
        attach(stationId)

        post("/api/stations/$stationId/swap", """{"fault":"F1"}""")
        awaitProgress(stationId, "AWAITING_REMOTE_START")

        val (status, body) = op(stationId, """{"op":"authorize"}""")

        assertEquals(409, status, "각본 중인데 조작이 통했다")
        assertContains(body.path("error").asText(), "AWAITING_REMOTE_START")
    }

    /**
     * ★ **스테이션이 거절한 조작은 422 이고, 사유는 스테이션의 말 그대로다.**
     *
     * 투입 슬롯은 붙은 직후 비어 있다. 거기에 충전을 걸면 `advanceCharging()` 의
     * `checkNotNull` 이 걸리고, 그 메시지가 이미 무엇이 왜 안 되는지를 말하고 있다. 콘솔이
     * 그것을 자기 말로 바꾸면 사실 하나에 문장이 둘이 된다.
     *
     * 400 이 아닌 이유: 요청은 성립했다. 슬롯 1은 있는 슬롯이고 5% 는 온전한 값이다.
     * 성립하지 않는 것은 **지금 이 스테이션의 사정**이라 그 구분이 상태 코드에 남아야 한다.
     */
    @Test
    fun `빈 슬롯을 충전하려 하면 422 와 스테이션의 사유가 온다`() {
        val stationId = "CS-CONSOLE-OP-EMPTY"
        attach(stationId)

        val (status, body) = op(stationId, """{"op":"advanceCharging","slotId":1,"byPercent":5}""")

        assertEquals(422, status, "빈 슬롯 충전이 거절되지 않았다")
        assertContains(body.path("error").asText(), "an empty slot does not charge")
    }

    /** 인자가 빠진 조작은 아직 스테이션에 닿지도 않았다 — 400 이지 422 가 아니다. */
    @Test
    fun `인자가 빠진 조작은 400 으로 되묻는다`() {
        val stationId = "CS-CONSOLE-OP-ARGS"
        attach(stationId)

        val (status, body) = op(stationId, """{"op":"advanceCharging","slotId":1}""")

        assertEquals(400, status)
        assertContains(body.path("error").asText(), "byPercent")
    }

    @Test
    fun `모르는 조작과 없는 스테이션이 갈린다`() {
        val stationId = "CS-CONSOLE-OP-UNKNOWN"
        attach(stationId)

        val (unknown, unknownBody) = op(stationId, """{"op":"selfDestruct"}""")
        assertEquals(400, unknown)
        assertContains(unknownBody.path("error").asText(), "selfDestruct")
        // 무엇을 부를 수 있는지가 응답에 실린다 — 문서를 찾아가지 않아도 다음 수가 보인다.
        assertContains(unknownBody.path("error").asText(), "insertBatteries")

        val (missing, missingBody) = op("CS-NOT-THERE", """{"op":"authorize"}""")
        assertEquals(404, missing)
        assertContains(missingBody.path("error").asText(), "CS-NOT-THERE")
    }

    /**
     * ★ **`close` 는 조작 목록에 없다 — 되돌릴 수 없어서가 아니라 조작이 아니어서다.**
     *
     * `StationSimulator.close()` 는 `AutoCloseable.close()` 이고, 뜻은 "스테이션 전원을
     * 내린다"가 아니라 "이 객체를 그만 쓴다"이다. 한때 손잡이로 열려 있었고, 그때 드러난
     * 것이 **반쪽 죽은 스테이션**이었다: 세션은 닫혔는데 `reconnect()` 가 소켓을 다시 열어
     * 스냅샷에 `connected:true` 가 떴고, 그 스테이션은 인바운드 요청에 계속 답하면서 자기는
     * 아무것도 못 보냈다. 지금은 세 자리가 다 막혀 있다 — 세션이 인바운드도 거절하고,
     * 시뮬레이터가 use-after-close 를 막고, 콘솔이 이 조작을 내주지 않는다.
     *
     * 그래서 손잡이를 뺐다. 스테이션을 끝내는 입구는 `DELETE /api/stations/{id}` 이고,
     * 사람이 "전원을 내린다"로 원하는 것은 대개 `disconnect` 다 — 세션·슬롯이 남아 되돌아온다.
     * 이 시험은 **다시 열리는 것을 막는다.** 목록에 되돌릴 수 없는 것이 섞이면 여기가 터진다.
     */
    @Test
    fun `close 는 조작으로 열려 있지 않다`() {
        val stationId = "CS-CONSOLE-OP-CLOSE"
        attach(stationId)

        val (status, body) = op(stationId, """{"op":"close"}""")

        assertEquals(400, status, "수명 관리 호출이 조작으로 열려 있다")
        assertContains(body.path("error").asText(), "close")
        assertContains(body.path("error").asText(), "disconnect", message = "고를 수 있는 조작이 안내되지 않는다")
    }

    /**
     * ★ **나가지 못한 전송이 스냅샷에 남는다** — 그 한 칸이 아니면 어디에도 없다.
     *
     * ### 이벤트 로그로는 만들 수 없다
     *
     * `OcppSession.emitRaw` 는 **보내기 전에** 적는다 (그쪽 KDoc: 기록되고 안 나간 것이
     * 나가고 기록 안 된 것보다 낫다). 그래서 `TransmitOutcome.Gone` 이어도 OUTBOUND 기록은
     * 남고, 그 기록은 배달된 프레임과 **글자 하나 다르지 않다.** 아래 (1)(2) 가 그 사실을
     * 그대로 단언한다 — `lastTransmitFailure` 가 파생이 아니라 새 관측인 근거다.
     *
     * ### `connected` 가 답하는 것
     *
     * `transport?.isOpen` 하나다 — **지금 소켓이 있는가**이지 방금 보낸 것이 나갔는가가
     * 아니다. (4) 가 그 구분을 경합 없이 만든다: 다시 붙이면 `connected` 는 참으로 돌아오지만
     * 마지막 전송은 여전히 나가지 못한 그것이고, 화면이 보여줄 것은 그 창이다.
     */
    @Test
    fun `나가지 못한 전송이 스냅샷에 남는다`() {
        val stationId = "CS-CONSOLE-OP-GONE"
        val attached = attach(stationId)
        val beforeGone = attached.path("messageCount").asInt()
        assertTrue(attached.path("lastTransmitFailure").isNull, "붙자마자 전송이 실패해 있다")
        assertTrue(attached.path("lastCallTimeout").isNull, "붙자마자 답 없는 CALL 이 있다")

        // 소켓만 없앤다. 세션도 슬롯도 그대로라 다음 조작은 정상으로 시작해 전송에서만 죽는다.
        val (disconnectStatus, disconnected) = op(stationId, """{"op":"disconnect"}""")
        assertEquals(200, disconnectStatus, "끊기지 않았다: ${disconnected.path("error").asText()}")
        assertEquals(false, disconnected.path("connected").asBoolean())

        val (authorizeStatus, failure) = op(stationId, """{"op":"authorize"}""")
        assertEquals(422, authorizeStatus, "전송이 죽었는데 조작이 통했다")
        assertContains(failure.path("error").asText(), "no connection to send")

        val gone = station(stationId)

        // (1) 나가지 못했는데도 기록은 남았다.
        assertTrue(
            gone.path("messageCount").asInt() > beforeGone,
            "나가지 못한 프레임이 기록되지도 않았다 — emitRaw 의 순서가 바뀌었다",
        )
        val recorded = gone.path("events").first()
        assertEquals("OUTBOUND", recorded.path("direction").asText())
        assertEquals("Authorize", recorded.path("action").asText())

        // (2) 그 기록은 **배달된 프레임과 모양이 같다.** 실린 칸이 넷뿐이라 결과를 담을 자리가 없다.
        assertEquals(
            listOf("action", "direction", "messageId", "occurredAt", "seq"),
            recorded.fieldNames().asSequence().sorted().toList(),
            "이벤트에 전송 결과를 담을 칸이 생겼다",
        )

        // (3) ★ 그래서 스냅샷이 따로 말한다. 각본의 자리(note·error·progress)는 그대로 침묵한다 —
        //     조작은 각본이 아니고, 전송 실패를 각본의 실패로 적으면 둘이 뒤섞인다.
        assertContains(gone.path("lastTransmitFailure").asText(), "not connected")
        assertContains(gone.path("lastTransmitFailure").asText(), stationId)

        // (3-1) ★ 옆 칸은 **다른 사실**을 답한다. 나가지 못한 프레임은 답을 기다린 적이
        //       없으므로 `lastCallTimeout` 은 비어 있어야 한다 — 둘이 함께 차면 그 순간
        //       화면은 "재전송이 필요한가"를 더 이상 말하지 못한다.
        assertTrue(
            gone.path("lastCallTimeout").isNull,
            "나가지도 못한 프레임을 무응답으로 적었다: ${gone.path("lastCallTimeout")}",
        )
        assertTrue(gone.path("note").isNull, "각본이 아닌데 note 가 있다: ${gone.path("note")}")
        assertTrue(gone.path("error").isNull, "각본이 아닌데 error 가 있다: ${gone.path("error")}")
        assertEquals("ATTACHED", gone.path("progress").asText(), "조작은 각본의 진행을 건드리지 않는다")

        // (4) 다시 붙이면 connected 는 참으로 돌아온다. 소켓을 여는 것은 프레임을 보내는 것이
        //     아니므로 마지막 전송은 그대로 실패한 그것이다 — 붙어 있는데 전송은 실패해 있는
        //     그 창이 여기서 실제로 관측된다.
        val (connectStatus, reconnected) = op(stationId, """{"op":"connect"}""")
        assertEquals(200, connectStatus, "다시 붙지 못했다: ${reconnected.path("error").asText()}")

        val afterReconnect = station(stationId)
        assertTrue(afterReconnect.path("connected").asBoolean(), "다시 붙었는데 연결이 없다")
        assertEquals(
            recorded.path("seq").asLong(),
            afterReconnect.path("events").first().path("seq").asLong(),
            "다시 붙는 것만으로 프레임이 오갔다",
        )
        assertContains(
            afterReconnect.path("lastTransmitFailure").asText(),
            "not connected",
            message = "connected 가 참이 되자 전송 실패가 지워졌다 — 소켓과 전송을 같은 것으로 본다",
        )
    }

    /**
     * ★ **`lastTransmitFailure` 는 관측이지 게이트가 아니다.**
     *
     * 실려 있는 동안에도 조작은 그대로 통해야 한다. 이 값을 보고 거절하기 시작하면 그 순간
     * 콘솔이 순서 게이트를 하나 갖게 되고, 그건 `docs/VIRTUAL-STATION.md` §3 이 일부러 열어
     * 둔 문(F5)을 닫는 일이다. **그리고 마지막 하나만 남는다** — 성공한 전송이 지운다.
     * 지우지 않으면 한참 전에 끊겼다 되살아난 스테이션이 화면에서 영영 고장 난 채로 남는다.
     */
    @Test
    fun `전송 실패가 실려 있어도 조작을 막지 않고 성공하면 지워진다`() {
        val stationId = "CS-CONSOLE-OP-GONE-CLEARS"
        attach(stationId)

        op(stationId, """{"op":"disconnect"}""")
        op(stationId, """{"op":"authorize"}""")
        assertTrue(
            station(stationId).path("lastTransmitFailure").isTextual,
            "전송이 죽었는데 스냅샷이 말하지 않는다",
        )

        op(stationId, """{"op":"connect"}""")

        // 실패가 실린 채로 건 조작이다. 200 이 아니면 이 값이 게이트가 된 것이다.
        val (status, authorized) = op(stationId, """{"op":"authorize"}""")
        assertEquals(200, status, "전송 실패가 조작을 막았다: ${authorized.path("error").asText()}")
        assertTrue(
            authorized.path("lastTransmitFailure").isNull,
            "나간 뒤에도 실패가 남아 있다: ${authorized.path("lastTransmitFailure")}",
        )
        // 답이 온 CALL 이다. 옆 칸도 같은 규칙으로 비어 있어야 한다 — 지우는 조건은
        // "성공했는가"가 아니라 "답이 왔는가"이고, 여기서는 답이 왔다.
        assertTrue(
            authorized.path("lastCallTimeout").isNull,
            "답이 왔는데 무응답이 실려 있다: ${authorized.path("lastCallTimeout")}",
        )
    }

    // ------------------------------------------------------------------ 오류 처리

    @Test
    fun `없는 스테이션을 조종하려 하면 404 로 답한다`() {
        val (swapStatus, swapBody) = post("/api/stations/CS-NOT-THERE/swap", "{}")
        assertEquals(404, swapStatus)
        assertContains(swapBody.path("error").asText(), "CS-NOT-THERE")

        val detach = http.send(
            HttpRequest.newBuilder(uri("/api/stations/CS-NOT-THERE")).DELETE().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(404, detach.statusCode())
    }

    @Test
    fun `이미 붙은 stationId 를 또 띄우려 하면 409 로 답한다`() {
        val stationId = "CS-CONSOLE-DUPLICATE"
        attach(stationId)

        val (status, body) = post("/api/stations", attachBody(stationId))
        assertEquals(409, status)
        assertContains(body.path("error").asText(), stationId)

        // 앞서 붙은 것은 그대로 살아 있다 — 실패한 두 번째 요청이 첫 번째를 밀어내지 않는다.
        assertEquals(1, state().path("stations").size())

        // 막히는 것은 **같은 식별자**뿐이다. 스테이션 여러 대를 동시에 붙일 수 있어야 한다.
        attach("$stationId-2")
        assertEquals(2, state().path("stations").size())
    }

    /**
     * 화면은 클래스패스의 정적 파일 한 장이다.
     *
     * 눈으로 볼 것을 시험이 대신 보지는 못하지만, **경로가 살아 있는지**는 확인할 수 있다 —
     * 리소스 위치가 어긋나면 데모 당일에야 알게 된다.
     */
    @Test
    fun `화면이 정적 파일 한 장으로 나온다`() {
        val page = http.send(
            HttpRequest.newBuilder(uri("/")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, page.statusCode())
        assertContains(page.headers().firstValue("Content-Type").orElse(""), "text/html")
        // 화면이 React 로 옮겨간 뒤로는 **본문이 번들 안에 있다.** 그래서 눈에 보이는 문구
        // 대신 두 가지를 본다 — 문서의 제목(무엇이 서빙됐는지)과 React 가 붙을 자리.
        // 예전에는 파일 맨 위 주석의 한국어 문구를 봤는데, 그것은 화면이 아니라 **주석**에
        // 걸린 단언이었다. 주석이 사라지자 시험이 깨졌고, 그 자리가 취약했다는 뜻이다.
        assertContains(page.body(), "<title>SwapVe — simulator control console</title>")
        assertContains(page.body(), """id="root"""")
    }

    // ------------------------------------------------------------------ 공통

    private fun attach(stationId: String): JsonNode {
        val (status, body) = post("/api/stations", attachBody(stationId))
        assertEquals(201, status, "붙이지 못했다: ${body.path("error").asText()}")
        return body
    }

    private fun attachBody(stationId: String) =
        """{"stationId":"$stationId","slots":4,"setSize":2}"""

    /**
     * 교환은 비동기로 돈다 — 접수는 202 고 진행은 상태 조회로 본다. 화면이 하는 일과 같다.
     *
     * 폴링이라 실제 시간을 쓴다. 콘솔의 계약이 "요청을 붙들지 않는다"인 이상 이 시험도
     * 기다릴 수밖에 없다. 대신 **실패는 즉시** 드러낸다 — FAILED 를 보면 곧바로 터진다.
     */
    private fun awaitProgress(stationId: String, expected: String): JsonNode {
        repeat(POLL_ATTEMPTS) {
            val station = state().path("stations").firstOrNull { it.path("stationId").asText() == stationId }
            if (station != null) {
                val progress = station.path("progress").asText()
                if (progress == expected) return station
                if (progress == "FAILED") {
                    throw AssertionError("시나리오가 실패했다: ${station.path("error").asText()}")
                }
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("$stationId 가 $expected 에 이르지 못했다: ${state().toPrettyString()}")
    }

    /** 조작 한 건. 각본과 달리 응답이 곧 결과라 폴링할 것이 없다. */
    private fun op(stationId: String, body: String): Pair<Int, JsonNode> =
        post("/api/stations/$stationId/op", body)

    /** 스테이션 한 대의 지금 모습. 각본이 없는 조작 시험은 폴링할 것이 없어 한 번만 읽는다. */
    private fun station(stationId: String): JsonNode = assertNotNull(
        state().path("stations").firstOrNull { it.path("stationId").asText() == stationId },
        "$stationId 이 목록에 없다",
    )

    private fun state(): JsonNode = MAPPER.readTree(
        http.send(
            HttpRequest.newBuilder(uri("/api/state")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body(),
    )

    private fun post(path: String, body: String): Pair<Int, JsonNode> {
        val response = http.send(
            HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return response.statusCode() to MAPPER.readTree(response.body())
    }

    private fun slot(station: JsonNode, slotId: Int): JsonNode =
        assertNotNull(
            station.path("slots").firstOrNull { it.path("slotId").asInt() == slotId },
            "슬롯 $slotId 이 없다: ${station.toPrettyString()}",
        )

    private fun uri(path: String): URI = URI.create("http://localhost:${console.port}$path")

    private companion object {
        /** 20초. 교환 1건은 로컬에서 1초 안쪽이고, 20초를 넘겼다면 그건 느린 것이 아니라 멈춘 것이다. */
        const val POLL_ATTEMPTS = 200
        const val POLL_INTERVAL_MILLIS = 100L

        val MAPPER = ObjectMapper()
    }
}
