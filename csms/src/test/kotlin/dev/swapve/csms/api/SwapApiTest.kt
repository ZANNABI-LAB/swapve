package dev.swapve.csms.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.csms.conformance.ConformanceScenario
import dev.swapve.csms.conformance.ConformanceScenario.callPayload
import dev.swapve.csms.conformance.ConformanceScenario.stationReceived
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.ocpp.swap.BatteryRejectionReason
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.station.StationSimConfig
import dev.swapve.station.StationSimulator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ★ **교환 REST API — 표준 S02 의 클라이언트 계약** (PLAN §3, §2 S5, M8).
 *
 * ### 목으로 때우지 않는다
 *
 * 이 시험의 모든 케이스에는 **실제 WebSocket 으로 붙은 스테이션 시뮬레이터**가 있다.
 * `POST /api/swaps` 가 정말로 `RequestBatterySwapRequest` 를 전선에 내보냈는지는 CSMS 쪽
 * 반환값이 아니라 **시뮬레이터가 받은 프레임**으로 확인한다 — 목을 세우면 "우리가 우리를
 * 불렀다"만 확인하게 된다.
 *
 * ### 응답을 DTO 로 역직렬화하지 않는다
 *
 * 본문을 [JsonNode] 로 읽어 **필드 이름을 직접** 확인한다. 우리 DTO 로 되읽으면 필드 이름이
 * 바뀌어도 시험은 통과하고, 그때 깨지는 것은 앱이다. 계약 시험은 전선 위의 이름을 봐야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
class SwapApiTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var rest: TestRestTemplate

    private val mapper = ObjectMapper()

    // ================================================================== 발신

    /**
     * ★ **POST /api/swaps 가 실제로 `RequestBatterySwapRequest` 를 스테이션으로 내보낸다.**
     *
     * 발신 로직은 M7 의 `RemoteSwapStarter` 에 이미 있고 M8 이 새로 만들지 않았다
     * (PLAN §0 v3.1). 그래도 이 시험이 필요한 이유는, **REST 진입점이 그것을 실제로 부르는지**
     * 는 별개의 사실이기 때문이다.
     */
    @Test
    fun `POST 는 RequestBatterySwapRequest 를 스테이션으로 내보낸다`() {
        val stationId = "CS-API-START"
        withStation(stationId) { simulator ->
            val response = startSwap(stationId, ConformanceScenario.VALID_ID_TOKEN.idToken)
            assertEquals(HttpStatus.CREATED, response.statusCode)

            val body = json(response)
            val requestId = body.path("requestId").asInt()

            // 검사 대상은 **실제로 소켓을 지난 바이트**다. 시뮬레이터가 받은 것 = CSMS 가 보낸 것.
            val sent = assertNotNull(
                simulator.eventLog.of(stationId).stationReceived()
                    .lastOrNull { it.action == BatterySwapWire.REQUEST_BATTERY_SWAP }
                    ?.callPayload(),
                "RequestBatterySwapRequest 가 나가지 않았다",
            )

            assertEquals(requestId, sent.path("requestId").asInt(), "응답의 requestId 와 전선의 값이 다르다")
            assertEquals(
                ConformanceScenario.VALID_ID_TOKEN.idToken,
                sent.path("idToken").path("idToken").asText(),
            )
            assertEquals(
                ConformanceScenario.VALID_ID_TOKEN.type,
                sent.path("idToken").path("type").asText(),
            )
        }
    }

    /**
     * 스테이션이 `Accepted` 로 답하면 교환이 열리고, **조회로 그 상태가 보인다.**
     *
     * 응답이 조회 URL 을 알려 준다는 것까지가 계약이다 (PLAN §3) — `Location` 헤더와 본문의
     * `swap.self` 가 같은 값을 가리킨다.
     */
    @Test
    fun `Accepted 면 교환이 열리고 조회로 그 상태가 보인다`() {
        val stationId = "CS-API-ACCEPTED"
        withStation(stationId) {
            val created = startSwap(stationId, ConformanceScenario.VALID_ID_TOKEN.idToken)
            assertEquals(HttpStatus.CREATED, created.statusCode)

            val body = json(created)
            assertEquals("ACCEPTED", body.path("outcome").asText())

            val self = body.path("swap").path("self").asText()
            assertEquals(self, created.headers.location?.toString(), "Location 과 self 가 다르다")
            assertEquals("$stationId:${body.path("requestId").asInt()}", body.path("swap").path("id").asText())

            val found = json(rest.getForEntity(self, String::class.java))
            assertEquals("AUTHORIZED", found.path("status").asText())
            assertEquals(stationId, found.path("stationId").asText())
            assertEquals(
                ConformanceScenario.VALID_ID_TOKEN.idToken,
                found.path("idToken").path("idToken").asText(),
            )
            // 시각은 ISO-8601 문자열이다 — 앱이 파싱할 형식이므로 계약에 속한다
            // (`docs/API.md` 의 예시가 이 모양이다). 에폭 밀리초로 나가면 여기서 깨진다.
            assertEquals(FixedClockConfig.FIXED_NOW_TEXT, found.path("authorizedAt").asText())

            // 아직 배터리가 오가지 않았다. 0 으로 채우지 않고 없는 값은 없다고 답한다.
            assertTrue(found.path("startedAt").isNull, "시작하지도 않은 교환에 시작 시각이 있다")
            assertTrue(found.path("durationMillis").isNull)
        }
    }

    /**
     * ★ **배터리가 없는 것은 시스템 장애가 아니다** (PLAN §5.4 F1 = `TC_S_102_CSMS`).
     *
     * 재고 판정은 스테이션이 한다 (PLAN §4.5, S02.FR.04). CSMS 는 그 사유를 받아 **정상
     * 응답으로** 호출자에게 보여 준다. 5xx 로 답하면 앱이 재시도 대상으로 오해하고, 이용자는
     * *"서버 오류"* 를 본다 — 실제로는 그 스테이션에 배터리가 없다는 정상적인 사실이다.
     */
    @Test
    fun `Rejected 와 NoBatteryAvailable 은 오류가 아니라 사유가 담긴 정상 응답이다`() {
        val stationId = "CS-API-NO-BATTERY"
        withStation(stationId, ConformanceScenario.emptyStationConfig(port, stationId)) {
            val response = startSwap(stationId, ConformanceScenario.VALID_ID_TOKEN.idToken)

            assertEquals(HttpStatus.OK, response.statusCode, "거부가 오류 상태로 나갔다")

            val body = json(response)
            assertEquals("REJECTED_BY_STATION", body.path("outcome").asText())
            // ★ 이유가 호출자에게 보인다.
            assertEquals(
                BatteryRejectionReason.NO_BATTERY_AVAILABLE.wireValue,
                body.path("reasonCode").asText(),
            )
            assertEquals(BatteryRejectionReason.NO_BATTERY_AVAILABLE.name, body.path("reason").asText())
            // 교환은 열리지 않았다 — 조회할 대상이 없다.
            assertTrue(body.path("swap").isNull, "거부된 개시가 교환을 열었다")
        }
    }

    /**
     * ★ **S02.FR.03 — 인가되지 않은 idToken 으로는 발신하지 않는다(SHALL NOT).**
     *
     * 보내고 나서 거부당한 것과 **아예 보내지 않은 것**은 다르다. 그래서 HTTP 상태뿐 아니라
     * **전선 위에 프레임이 없었다**는 사실을 함께 확인한다.
     */
    @Test
    fun `인가되지 않은 idToken 으로는 발신되지 않는다`() {
        val stationId = "CS-API-UNAUTHORIZED"
        withStation(stationId) { simulator ->
            val response = startSwap(stationId, "NOT-REGISTERED-9999")

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
            val body = json(response)
            assertEquals("NOT_AUTHORIZED", body.path("error").asText())
            assertEquals("Unknown", body.path("idTokenStatus").asText())

            assertTrue(
                simulator.eventLog.of(stationId)
                    .none { it.action == BatterySwapWire.REQUEST_BATTERY_SWAP },
                "인가되지 않은 토큰으로 RequestBatterySwap 이 나갔다",
            )
        }
    }

    /**
     * 연결되지 않은 스테이션 요청이 **예외로 터지지 않는다.**
     *
     * M4 의 `StationCommandBus` 가 "연결이 없다"를 예외가 아니라 결과로 알려 준다
     * (`OcppResult.NotConnected`). REST 는 그것을 503 으로 옮긴다 — 스테이션이 돌아오면
     * 같은 요청이 성립하므로 재시도 가능한 상태다.
     */
    @Test
    fun `연결되지 않은 스테이션 요청은 예외가 아니라 503 이다`() {
        val response = startSwap("CS-API-OFFLINE", ConformanceScenario.VALID_ID_TOKEN.idToken)

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals("SERVICE_UNAVAILABLE", json(response).path("error").asText())
        assertEquals("CS-API-OFFLINE", json(response).path("stationId").asText())
    }

    // ================================================================== 조회

    /**
     * ★ **양쪽 배터리의 `serialNumber`·`soC`·`soH` 를 모두 노출한다** (PLAN §11.2).
     *
     * > *"the price can depend, for example, on **the difference between the state of charge
     * > of the old and new batteries**"* (Part 2 S. Ch.1)
     *
     * 과금이 나중에 **이 위의 순수 계산**이 되려면 여기서 버리면 안 된다. 결과만 남기고
     * SoC 를 버리는 것이 PLAN §11.2 가 지목한 "막히는 전제"다.
     */
    @Test
    fun `GET 이 양쪽 배터리의 serialNumber·soC·soH 를 모두 보여준다`() {
        val stationId = "CS-API-COMPLETED"
        withStation(stationId) { simulator ->
            val created = startSwap(stationId, ConformanceScenario.VALID_ID_TOKEN.idToken)
            runBlocking { simulator.runRemoteSwap() }

            val swap = json(rest.getForEntity(selfOf(created), String::class.java))
            assertEquals("COMPLETED", swap.path("status").asText())

            // 입고 — TC_S_103_CSMS step 11 의 값 그대로.
            assertEquals(
                mapOf("1234" to (23.0 to 85.0), "5678" to (45.0 to 87.0)),
                batteriesOf(swap.path("batteriesIn")),
            )
            // 출고 — step 21 의 값 그대로. **양쪽 다 남는다.**
            assertEquals(
                mapOf("4321" to (80.0 to 95.0), "8765" to (85.0 to 78.0)),
                batteriesOf(swap.path("batteriesOut")),
            )

            // 슬롯도 남는다 — 입고 슬롯과 출고 슬롯은 서로 다르다 (PLAN §7.1).
            assertEquals(
                ConformanceScenario.INSERT_SLOTS.sorted(),
                swap.path("batteriesIn").map { it.path("slotId").asInt() }.sorted(),
            )
            assertEquals(
                ConformanceScenario.DISPENSE_SLOTS.sorted(),
                swap.path("batteriesOut").map { it.path("slotId").asInt() }.sorted(),
            )

            // 과금 근거의 나머지 절반 — 시작·종료 시각 (PLAN §11.2).
            assertFalse(swap.path("startedAt").isNull, "시작 시각이 없다")
            assertFalse(swap.path("endedAt").isNull, "종료 시각이 없다")
            assertEquals(0L, swap.path("durationMillis").asLong(), "고정 시계에서는 0 이어야 한다")
        }
    }

    /**
     * ★ **`OUT_TIMED_OUT` 교환은 장부 불균형이 드러나야 한다** (PLAN §5.3, §5.4 F2).
     *
     * > **S03.FR.06** — *"CSMS ends up with an **orphan BatteryIn for which a BatteryOut is
     * > missing**."*
     *
     * 조용히 "실패"로 뭉개면 보상할 수 없다. **몇 개가 orphan 인지, 어떤 배터리인지** 보여야
     * 하고, 그 채무가 프로세스 밖에도 남았는지(`persisted`)까지 보여야 한다.
     */
    @Test
    fun `OUT_TIMED_OUT 교환 조회에서 장부 불균형이 드러난다`() {
        val stationId = "CS-API-OUT-TIMEOUT"
        withStation(stationId) { simulator ->
            val created = startSwap(stationId, ConformanceScenario.VALID_ID_TOKEN.idToken)
            runBlocking {
                simulator.awaitRemoteStart()
                simulator.insertBatteries()
                simulator.reportBatteryOutTimeout()
            }

            val swap = json(rest.getForEntity(selfOf(created), String::class.java))
            assertEquals("OUT_TIMED_OUT", swap.path("status").asText())

            val imbalance = swap.path("ledgerImbalance")
            assertFalse(imbalance.isMissingNode || imbalance.isNull, "장부 불균형이 드러나지 않았다")
            assertEquals(2, imbalance.path("orphanCount").asInt())
            assertEquals(
                listOf("1234", "5678"),
                imbalance.path("orphanBatteries").map { it.path("serialNumber").asText() }.sorted(),
            )
            // orphan 배터리의 SoC/SoH 도 남는다 — 보상 대상이 무엇인지 알아야 한다.
            assertEquals(
                mapOf("1234" to (23.0 to 85.0), "5678" to (45.0 to 87.0)),
                batteriesOf(imbalance.path("orphanBatteries")),
            )
            // ★ 채무가 H2 에도 남았다 (PLAN §5.3 — 이것만 영속된다).
            assertTrue(imbalance.path("persisted").asBoolean(), "장부가 영속되지 않았다")
        }
    }

    @Test
    fun `없는 교환은 404 다`() {
        val response = rest.getForEntity("/api/swaps/CS-API-NOBODY:12345", String::class.java)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("NOT_FOUND", json(response).path("error").asText())
    }

    /**
     * 모양이 틀린 식별자도 **404** 다.
     *
     * 400 으로 답하면 *"문법은 맞는데 없다"* 와 *"문법이 틀렸다"* 가 갈리고, 그 차이가
     * 스테이션 식별자를 넘겨짚는 수단이 된다. 앱 입장에서 둘 다 *"그런 교환은 없다"* 이다.
     */
    @Test
    fun `모양이 틀린 식별자도 404 다`() {
        listOf("not-a-swap-id", ":42", "CS-API:notanumber").forEach { id ->
            assertEquals(
                HttpStatus.NOT_FOUND,
                rest.getForEntity("/api/swaps/$id", String::class.java).statusCode,
                "식별자 '$id' 가 404 가 아니다",
            )
        }
    }

    // ================================================================== 요청 검증

    @Test
    fun `stationId 나 idToken 이 없으면 400 이다`() {
        listOf(
            """{"idToken":{"idToken":"RFID-0001","type":"ISO14443"}}""",
            """{"stationId":"CS-API-BAD","idToken":{"type":"ISO14443"}}""",
            """{"stationId":"CS-API-BAD","idToken":{"idToken":"RFID-0001"}}""",
            """{"stationId":"","idToken":{"idToken":"RFID-0001","type":"ISO14443"}}""",
        ).forEach { body ->
            val response = post(body)
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode, "본문 '$body' 가 400 이 아니다")
            assertEquals("INVALID_REQUEST", json(response).path("error").asText())
        }
    }

    // ------------------------------------------------------------------ 공통

    private fun startSwap(stationId: String, idToken: String): ResponseEntity<String> = post(
        """{"stationId":"$stationId","idToken":{"idToken":"$idToken","type":"ISO14443"}}""",
    )

    private fun post(body: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        return rest.postForEntity("/api/swaps", HttpEntity(body, headers), String::class.java)
    }

    private fun json(response: ResponseEntity<String>): JsonNode =
        mapper.readTree(assertNotNull(response.body, "본문이 없다: ${response.statusCode}"))

    private fun selfOf(created: ResponseEntity<String>): String {
        assertEquals(HttpStatus.CREATED, created.statusCode, "개시가 승인되지 않았다: ${created.body}")
        return json(created).path("swap").path("self").asText()
    }

    /** `serialNumber → (soC, soH)`. 순서에 기대지 않고 값 전부를 비교하기 위한 모양이다. */
    private fun batteriesOf(array: JsonNode): Map<String, Pair<Double, Double>> =
        array.associate {
            it.path("serialNumber").asText() to (it.path("soC").asDouble() to it.path("soH").asDouble())
        }

    /** 붙이고 부팅시킨 스테이션 하나. 시험이 끝나면 닫힌다. */
    private fun withStation(
        stationId: String,
        config: StationSimConfig = ConformanceScenario.config(port, stationId),
        block: (StationSimulator) -> Unit,
    ) {
        ConformanceScenario.simulator(config).use { simulator ->
            runBlocking {
                simulator.connect()
                simulator.boot()
            }
            block(simulator)
        }
    }
}
