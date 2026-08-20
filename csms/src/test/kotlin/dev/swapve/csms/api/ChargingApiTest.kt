package dev.swapve.csms.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.csms.charging.ChargingScenario
import dev.swapve.csms.support.ApiCredentialsInitializer
import dev.swapve.csms.support.FixedClockConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ContextConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 충전 트랜잭션 조회 API (S04).
 *
 * ### 교환 조회와 **다른 자원**임을 고정한다
 *
 * 이 시험이 확인하는 핵심은 필드 하나하나가 아니라 **경로의 소속**이다. 충전은 스테이션의
 * 슬롯에 매인 것이라 `/api/stations/{stationId}/charging-transactions` 아래 있고, 교환이
 * 완료된 뒤에도 그 자원은 살아 있다.
 *
 * 응답을 DTO 로 되읽지 않고 [JsonNode] 로 **필드 이름을 직접** 본다 (`SwapApiTest` 와 같은
 * 이유 — 우리 DTO 로 되읽으면 이름이 바뀌어도 시험은 통과하고 깨지는 것은 소비자다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
@ContextConfiguration(initializers = [ApiCredentialsInitializer::class])
class ChargingApiTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var rest: TestRestTemplate

    private val mapper = ObjectMapper()

    @Test
    fun `목록에 슬롯과 배터리와 SoC 가 읽힌다`() {
        val stationId = "CS-CHG-API-LIST"
        runScenario(stationId)

        val body = json(rest.getForEntity(listUrl(stationId), String::class.java))
        assertTrue(body.isArray, "목록이 배열이 아니다: $body")

        // 슬롯 1·2 는 방금 들어온 배터리의 충전, 3·4 는 반출되며 끝난 충전이다.
        assertEquals(4, body.size(), "충전 트랜잭션 네 건이 있어야 한다")

        val inserted = assertNotNull(body.firstOrNull { it.path("slotId").asInt() == 1 }, "슬롯 1 의 충전이 없다")
        assertEquals(stationId, inserted.path("stationId").asText())
        assertEquals(ChargingScenario.INCOMING[0].serialNumber, inserted.path("batterySerialNumber").asText())
        assertEquals("SUSPENDED", inserted.path("status").asText(), "상한에 닿아 멈춰 있다")
        assertEquals(ChargingScenario.MAX_SOC.toDouble(), inserted.path("socPercent").asDouble())
        assertTrue(inserted.path("startedAt").isTextual)
    }

    @Test
    fun `교환이 완료돼도 들어온 배터리의 충전은 조회된다`() {
        val stationId = "CS-CHG-API-ALIVE"
        runScenario(stationId)

        val body = json(rest.getForEntity(listUrl(stationId), String::class.java))

        // 반출된 배터리의 충전은 끝났고 —
        ChargingScenario.DISPENSE_SLOTS.forEach { slotId ->
            val ended = assertNotNull(body.firstOrNull { it.path("slotId").asInt() == slotId })
            assertEquals("ENDED", ended.path("status").asText(), "슬롯 $slotId")
        }

        // — 들어온 배터리의 충전은 그대로 살아 있다.
        ChargingScenario.INSERT_SLOTS.forEach { slotId ->
            val live = assertNotNull(body.firstOrNull { it.path("slotId").asInt() == slotId })
            assertTrue(live.path("status").asText() != "ENDED", "슬롯 $slotId 의 충전이 교환과 함께 닫혔다")
        }
    }

    @Test
    fun `1건 조회는 self 경로로 그대로 다시 읽힌다`() {
        val stationId = "CS-CHG-API-ONE"
        runScenario(stationId)

        val first = json(rest.getForEntity(listUrl(stationId), String::class.java)).first()
        val self = first.path("self").asText()

        val one = json(rest.getForEntity("http://localhost:$port$self", String::class.java))
        assertEquals(first.path("transactionId").asText(), one.path("transactionId").asText())
        assertEquals(self, one.path("self").asText())
    }

    @Test
    fun `없는 트랜잭션은 404 다`() {
        val stationId = "CS-CHG-API-404"
        runScenario(stationId)

        val response = rest.getForEntity("${listUrl(stationId)}/no-such-tx", String::class.java)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("NOT_FOUND", json(response).path("error").asText())
    }

    @Test
    fun `충전이 없는 스테이션은 빈 목록이다`() {
        val response = rest.getForEntity(listUrl("CS-CHG-API-EMPTY"), String::class.java)

        // 404 가 아니다 — "그 스테이션에 도는 충전이 없다"는 정상적인 답이다.
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(0, json(response).size())
    }

    // ------------------------------------------------------------------ 공통

    /** 교환 1건을 완주하되, 들어온 배터리 하나는 상한까지 충전해 둔다. */
    private fun runScenario(stationId: String) {
        val (simulator, clock) = ChargingScenario.simulator(ChargingScenario.config(port, stationId))

        simulator.use {
            runBlocking {
                it.connect()
                it.boot()
                it.authorize()
                it.insertBatteries()
                it.reportChargingStarted()

                while (!it.isChargingSuspended(1)) {
                    clock.advance(ChargingScenario.SOC_STEP_INTERVAL.toMillis())
                    it.advanceCharging(slotId = 1, byPercent = ChargingScenario.SOC_STEP)
                }

                it.removeBatteries()
            }
        }
    }

    private fun listUrl(stationId: String) =
        "http://localhost:$port/api/stations/$stationId/charging-transactions"

    private fun json(response: ResponseEntity<String>): JsonNode =
        mapper.readTree(assertNotNull(response.body, "본문이 비어 있다"))
}
