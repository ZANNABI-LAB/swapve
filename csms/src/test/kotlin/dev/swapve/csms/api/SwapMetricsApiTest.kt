package dev.swapve.csms.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.csms.conformance.ConformanceScenario
import dev.swapve.csms.support.ApiCredentialsInitializer
import dev.swapve.csms.support.MutableClock
import dev.swapve.csms.support.MutableClockConfig
import dev.swapve.ocpp.swap.BatteryRejectionReason
import dev.swapve.station.StationSimConfig
import dev.swapve.station.StationSimulator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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
import org.springframework.test.context.ContextConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ★★ **성공 기준 S5 — 교환 성공률·소요시간·실패 사유가 REST 로 조회된다** (PLAN §2).
 *
 * ### 왜 한 시나리오를 통째로 돌리고 끝에서 한 번 단언하나
 *
 * 지표는 **여러 건이 섞였을 때** 비로소 틀릴 수 있다. 성공만 있는 상태에서 성공률 1.0 이
 * 나오는 것은 아무것도 증명하지 않는다. 그래서 성공 2건 · 수령 타임아웃 1건 · 배터리 부족
 * 거부 1건 · 미등록 배터리 1건 · 순서 위반 1건 · 중복 입고 1건 · 재전송 1건 · 인가 차단 1건을
 * **한 번에** 만들어 두고, 각 값이 **서로 섞이지 않고** 제 자리에 들어가는지 본다.
 *
 * ### 격리 — [MutableClockConfig] 를 쓰는 이유는 둘이다
 *
 * 1. **소요시간.** 고정 시계에서는 모든 교환이 0 밀리초라 분포가 아무 의미도 없다.
 *    시험이 시각을 직접 밀어 90 초·30 초짜리 교환을 만든다. `sleep` 은 없다.
 * 2. **컨텍스트 격리.** 이 설정을 `@Import` 하면 다른 시험들과 **다른 Spring 컨텍스트**를
 *    받는다. 인메모리 보관소가 적합성 시험이 남긴 교환으로 오염되면 *"성공률이 정확히 2/7"*
 *    같은 단언 자체가 불가능하다.
 *
 * ### 단 하나, 영속 장부만은 정확한 수를 단언할 수 없다
 *
 * `OUT_TIMED_OUT` 장부는 H2 파일에 남고 **같은 Gradle test 태스크 실행 안에서 다른 시험
 * 클래스가 남긴 행과 같은 파일을 공유한다** (PLAN §5.3 — 이것만 영속된다). 그건 결함이
 * 아니라 그 장부의 성질이다 — 프로세스 안에서 남는 채무이기 때문이다. 그래서 이 블록만
 * `>=` 로 단언한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MutableClockConfig::class)
@ContextConfiguration(initializers = [ApiCredentialsInitializer::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SwapMetricsApiTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var rest: TestRestTemplate

    @Autowired
    private lateinit var clock: MutableClock

    private val mapper = ObjectMapper()

    /**
     * 시나리오를 한 번만 돌린다. 지표는 누적값이라 케이스마다 다시 돌리면 수가 어긋난다.
     *
     * `@BeforeAll` 이 아니라 `@BeforeEach` + 플래그인 이유는 주입 시점 때문이다 — 이 시험은
     * 주입된 포트와 시계를 시나리오 준비에 쓰므로, 주입이 끝난 것이 확실한 자리에서 돈다.
     * 플래그는 `PER_CLASS` 수명 덕에 케이스 사이에 유지된다.
     */
    private var prepared = false

    @BeforeEach
    fun runScenario() {
        if (prepared) return
        prepared = true

        // ---------------------------------------------------------- 성공 2건 (소요시간이 다르다)
        completeSwap("CS-M-OK-FAST", durationMillis = 30_000)
        completeSwap("CS-M-OK-SLOW", durationMillis = 90_000)

        // ---------------------------------------------------------- F2 — 수령 타임아웃
        withStation("CS-M-TIMEOUT") { simulator ->
            assertEquals(HttpStatus.CREATED, startSwap("CS-M-TIMEOUT").statusCode)
            runBlocking {
                simulator.awaitRemoteStart()
                simulator.insertBatteries()
                clock.advance(45_000)
                simulator.reportBatteryOutTimeout()
            }
        }

        // ---------------------------------------------------------- F1 — 배터리 부족
        val emptyStation = "CS-M-NO-BATTERY"
        withStation(emptyStation, ConformanceScenario.emptyStationConfig(port, emptyStation)) {
            assertEquals(HttpStatus.OK, startSwap(emptyStation).statusCode)
        }

        // ---------------------------------------------------------- F3 — 미등록 배터리
        val unknownStation = "CS-M-UNKNOWN-BATTERY"
        withStation(unknownStation, ConformanceScenario.unknownBatteryConfig(port, unknownStation)) { simulator ->
            assertEquals(HttpStatus.CREATED, startSwap(unknownStation).statusCode)
            runBlocking {
                simulator.awaitRemoteStart()
                simulator.insertBatteries()
            }
        }

        // ---------------------------------------------------------- F5 — 인가 없이 도착한 교환 사건
        withStation("CS-M-NOT-AUTHORIZED") { simulator ->
            // 개시도 인가도 하지 않고 곧바로 넣는다.
            runBlocking { simulator.insertBatteries() }
        }

        // ---------------------------------------------------------- F4 — 새 messageId 로 온 중복 입고
        withStation("CS-M-DUPLICATE") { simulator ->
            assertEquals(HttpStatus.CREATED, startSwap("CS-M-DUPLICATE").statusCode)
            runBlocking {
                simulator.awaitRemoteStart()
                simulator.insertBatteries()
                simulator.resendLastBatterySwap(sameMessageId = false)
            }
        }

        // ---------------------------------------------------------- F6 — 같은 messageId 재전송
        withStation("CS-M-REPLAY") { simulator ->
            assertEquals(HttpStatus.CREATED, startSwap("CS-M-REPLAY").statusCode)
            runBlocking {
                simulator.awaitRemoteStart()
                simulator.insertBatteries()
                simulator.resendLastBatterySwap(sameMessageId = true)
            }
        }

        // ---------------------------------------------------------- S02.FR.03 — 보내지 않은 시도
        assertEquals(
            HttpStatus.FORBIDDEN,
            startSwap("CS-M-BLOCKED", idToken = "NOT-REGISTERED-9999").statusCode,
        )
    }

    // ================================================================== 성공률

    /**
     * **성공률 = 완료된 교환 / 시도된 교환** (PLAN §2 S5).
     *
     * 분모는 **스테이션에 실제로 도달한 개시**다: 열린 교환 6건(완주 2 · 타임아웃 1 · 반쪽 3)
     * 과 스테이션이 거부한 1건. 인가가 막아 **보내지 않은** 시도는 여기 없다 — 전선에 나가지도
     * 않은 것을 교환 시도로 세면 성공률이 그만큼 낮아 보인다.
     *
     * 인가 없이 도착한 사건(F5)도 분모에 없다. 그 교환은 **열린 적이 없다.**
     */
    @Test
    fun `성공률이 시도 대비 완주로 계산된다`() {
        val swaps = metrics().path("swaps")

        assertEquals(7, swaps.path("attempted").asInt(), "시도 건수가 다르다")
        assertEquals(2, swaps.path("completed").asInt())
        assertEquals(3, swaps.path("inProgress").asInt(), "반쪽으로 남은 교환 수가 다르다")
        assertEquals(2, swaps.path("failed").asInt(), "타임아웃 1건 + 거부 1건이어야 한다")
        assertEquals(1, swaps.path("blockedStarts").asInt(), "S02.FR.03 으로 막힌 시도")

        assertEquals(2.0 / 7.0, metrics().path("successRate").asDouble(), 1e-9)
    }

    // ================================================================== 소요시간

    /**
     * **소요시간 — 분포를 볼 수 있어야 한다** (PLAN §2 S5).
     *
     * 30 초와 90 초 두 건이므로 평균 60 초, 최소 30 초, 최대 90 초다. 백분위는 보간하지 않고
     * 실제 표본을 고르므로 p50 = 30 초(`ceil(0.5 × 2) = 1` 번째), p95 = 90 초다.
     */
    @Test
    fun `완주 교환의 소요시간 분포가 나온다`() {
        val completed = metrics().path("duration").path("completed")

        assertEquals(2, completed.path("count").asInt())
        assertEquals(30_000, completed.path("minMillis").asLong())
        assertEquals(60_000, completed.path("meanMillis").asLong())
        assertEquals(90_000, completed.path("maxMillis").asLong())
        assertEquals(30_000, completed.path("p50Millis").asLong())
        assertEquals(90_000, completed.path("p95Millis").asLong())
    }

    /**
     * 반쪽으로 끝난 교환의 소요시간은 **따로 센다.**
     *
     * `OUT_TIMED_OUT` 의 45 초는 "교환이 이만큼 걸렸다"가 아니라 "이용자가 이만큼 안 꺼내
     * 갔다"이다. 완주 분포에 섞으면 백분위가 그것 때문에 늘어난다.
     */
    @Test
    fun `수령 타임아웃의 소요시간은 완주 분포와 섞이지 않는다`() {
        val duration = metrics().path("duration")

        assertEquals(1, duration.path("outTimedOut").path("count").asInt())
        assertEquals(45_000, duration.path("outTimedOut").path("maxMillis").asLong())
        // 완주 분포에는 45 초가 없다.
        assertEquals(2, duration.path("completed").path("count").asInt())
    }

    // ================================================================== 실패 사유

    /**
     * ★ **실패 사유가 F1~F6 별로 구분된다** — *"실패 12건"은 정보가 아니다* (PLAN §2 S5, §5.4).
     */
    @Test
    fun `실패 사유가 시나리오별로 구분되어 집계된다`() {
        val failures = metrics().path("failures")
        val byScenario = failures.path("byScenario")

        assertEquals(1, byScenario.path("F1").asInt(), "F1 배터리 부족")
        assertEquals(1, byScenario.path("F2").asInt(), "F2 수령 타임아웃")
        assertEquals(1, byScenario.path("F3").asInt(), "F3 미등록 배터리")
        assertEquals(1, byScenario.path("F5").asInt(), "F5 순서 위반")
        assertEquals(4, failures.path("total").asInt())

        // F5 의 세부 — 무엇이 이상이었는지.
        assertEquals(1, failures.path("byAnomalyReason").path("NOT_AUTHORIZED").asInt())

        // S02.FR.03 으로 막힌 시도. 실패 총계에 섞이지 않는다 — 교환이 시작되지도 않았다.
        assertEquals(1, failures.path("rejectedAuthorizations").asInt())
    }

    /**
     * ★ **§4.9.1 `reason_codes.csv` 의 사유별로도 갈린다.**
     *
     * 스테이션이 개시를 거부한 사유(`NoBatteryAvailable`)와 우리가 배터리를 거부한
     * 사유(`BatteryUnknown`)는 같은 부록 표에서 오므로 한 map 에 모인다. 둘이 각각
     * 1 건이라는 것이 *"무엇 때문에 실패했는지"* 다.
     */
    @Test
    fun `실패 사유가 reasonCode 별로 집계된다`() {
        val byReasonCode = metrics().path("failures").path("byReasonCode")

        assertEquals(1, byReasonCode.path(BatteryRejectionReason.NO_BATTERY_AVAILABLE.wireValue).asInt())
        assertEquals(1, byReasonCode.path(BatteryRejectionReason.BATTERY_UNKNOWN.wireValue).asInt())
    }

    /**
     * ★ **F4 와 F6 은 실패가 아니고, 서로 구분된다** (PLAN §5.4).
     *
     * 둘 다 "두 번 반영되지 않았다"가 올바른 동작이다. 걸린 층이 달라 남는 자리도 다르다:
     * F4 는 상태머신 기록에, F6 은 **이벤트 로그의 중복 수신 CALL** 에만 남는다
     * (멱등 원장이 상위 계층을 부르지 않으므로 — PLAN §11.1 이 이 계산을 가능하게 한다).
     */
    @Test
    fun `멱등 처리는 실패로 세지 않고 F4 와 F6 을 구분한다`() {
        val idempotency = metrics().path("idempotency")

        assertEquals(1, idempotency.path("byScenario").path("F4").asInt())
        assertEquals(1, idempotency.path("byScenario").path("F6").asInt())
        assertEquals(1, idempotency.path("stateMachineIgnores").asInt())
        assertEquals(1, idempotency.path("byIgnoreReason").path("DUPLICATE_BATTERY_IN").asInt())
        assertEquals(1, idempotency.path("sessionReplays").asInt())

        // 실패 총계에 섞이지 않았다.
        assertEquals(4, metrics().path("failures").path("total").asInt())
    }

    // ================================================================== 영속 장부

    /**
     * 영속 장부는 **다른 질문에 답한다** (PLAN §5.3).
     *
     * 위의 모든 수치가 이 프로세스가 본 것인 반면 이 블록은 H2 에서 읽으므로 재시작과
     * 시험 클래스를 가로질러 남는다. 그래서 정확한 수가 아니라 **이번 시나리오가 남긴 채무가
     * 포함돼 있는지**를 확인한다.
     */
    @Test
    fun `영속된 장부 불균형이 함께 조회된다`() {
        val ledger = metrics().path("ledger")

        assertTrue(ledger.path("openImbalances").asInt() >= 1, "장부 불균형이 조회되지 않았다: $ledger")
        assertTrue(ledger.path("orphanBatteries").asInt() >= 2, "orphan 배터리 수가 모자라다: $ledger")
    }

    @Test
    fun `지표에 생성 시각이 있다`() {
        assertTrue(metrics().path("generatedAt").isTextual, "generatedAt 이 없다")
    }

    // ------------------------------------------------------------------ 공통

    private fun metrics(): JsonNode {
        val response = rest.getForEntity("/api/metrics/swaps", String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
        return mapper.readTree(assertNotNull(response.body))
    }

    /** 인가 → 입고 → (시간이 흐른다) → 출고. 교환 1건을 완주시킨다. */
    private fun completeSwap(stationId: String, durationMillis: Long) {
        withStation(stationId) { simulator ->
            assertEquals(HttpStatus.CREATED, startSwap(stationId).statusCode)
            runBlocking {
                simulator.awaitRemoteStart()
                simulator.insertBatteries()
                clock.advance(durationMillis)
                simulator.removeBatteries()
            }
        }
    }

    private fun startSwap(
        stationId: String,
        idToken: String = ConformanceScenario.VALID_ID_TOKEN.idToken,
    ): ResponseEntity<String> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"stationId":"$stationId","idToken":{"idToken":"$idToken","type":"ISO14443"}}"""
        return rest.postForEntity("/api/swaps", HttpEntity(body, headers), String::class.java)
    }

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
