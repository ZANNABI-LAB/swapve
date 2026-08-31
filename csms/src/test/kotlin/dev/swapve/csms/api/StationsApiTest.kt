package dev.swapve.csms.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.csms.conformance.ConformanceScenario
import dev.swapve.csms.event.JdbcOcppEventLog
import dev.swapve.csms.support.ApiCredentialsInitializer
import dev.swapve.ocpp.session.MessageDirection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.ContextConfiguration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ★★ **운영 화면이 읽는 두 엔드포인트** ([StationsController]).
 *
 * ### 무엇을 지키려고 이 시험이 있나
 *
 * 이 화면의 규칙은 하나다 — **모르는 것을 아는 척하지 않는다.** 그래서 단언도 세 가지에 몰려
 * 있다: 등록 정보가 없을 때 없다고 말하는가, 최근 200 건만 보여 주면서 전체 건수를 함께
 * 말하는가, 프레임 원문을 **손대지 않고** 싣는가.
 *
 * ### 원문을 그대로 싣는지가 왜 중요한가
 *
 * 이 화면을 여는 이유는 *"상대가 실제로 무엇을 보냈는가"* 하나다. 우리가 다시 포맷하거나
 * 필드를 골라 담으면 그 목적이 사라진다. 그래서 저장된 문자열과 응답의 `payload` 가
 * **문자 단위로 같은지**를 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = [ApiCredentialsInitializer::class])
class StationsApiTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var rest: TestRestTemplate

    @Autowired
    private lateinit var eventLog: JdbcOcppEventLog

    private val mapper = ObjectMapper()

    @Test
    fun `부팅한 스테이션이 등록 정보와 함께 목록에 나온다`() {
        val stationId = "CS-OPS-BOOTED"

        ConformanceScenario.simulator(ConformanceScenario.config(port, stationId)).use { simulator ->
            runBlocking {
                simulator.connect()
                simulator.boot()
            }

            val station = assertNotNull(
                stations().firstOrNull { it.path("stationId").asText() == stationId },
                "부팅한 스테이션이 목록에 없다",
            )

            // 붙어 있는 동안에는 세션이 등록돼 있다. **보낼 수 있다는 뜻은 아니다** —
            // 그 구분은 필드 이름과 화면 각주가 지고 있고, 여기서는 값만 확인한다.
            assertTrue(station.path("sessionRegistered").asBoolean(), "붙어 있는데 세션이 없다고 한다")

            val registration = station.path("registration")
            assertTrue(registration.isObject, "BootNotification 을 받았는데 등록 정보가 없다")
            // 시뮬레이터가 보낸 값 그대로다. 우리가 아는 것은 상대가 말해 준 것뿐이다.
            assertEquals("PowerUp", registration.path("bootReason").asText())
            assertTrue(registration.path("vendorName").asText().isNotBlank(), "vendorName 이 비어 있다")
            assertTrue(station.path("messageCount").asInt() > 0, "프레임이 하나도 세어지지 않았다")
        }
    }

    /**
     * ★ **이벤트 로그에만 있는 스테이션도 목록에 나온다.**
     *
     * [dev.swapve.csms.station.StationRegistry] 는 인메모리라 프로세스를 다시 띄우면 비고,
     * 그 뒤 부팅 통보가 오기 전까지는 로그만 남는다. 목록을 등록 표에서만 만들면 **재시작 직후
     * 화면이 텅 비어 "아무 일도 없었다"고 거짓말한다.** 여기서는 그 상황을 로그에 직접 한 줄
     * 적어 재현한다 — 부팅한 적 없는 스테이션이 목록에 있고 `registration` 이 `null` 이어야 한다.
     */
    @Test
    fun `등록 정보가 없어도 로그에 있으면 목록에 나오고 없다고 말한다`() {
        val stationId = "CS-OPS-LOG-ONLY"
        eventLog.append(
            stationId = stationId,
            direction = MessageDirection.INBOUND,
            action = "Heartbeat",
            messageId = "ops-log-only-1",
            payload = """[2,"ops-log-only-1","Heartbeat",{}]""",
            occurredAt = Instant.parse("2026-08-31T00:00:00Z"),
        )

        val station = assertNotNull(
            stations().firstOrNull { it.path("stationId").asText() == stationId },
            "로그에 있는 스테이션이 목록에서 빠졌다",
        )
        assertTrue(station.path("registration").isNull, "부팅한 적 없는데 등록 정보가 있다")
        assertEquals(false, station.path("sessionRegistered").asBoolean(), "붙은 적 없는데 세션이 있다")
    }

    /**
     * ★ **원문을 손대지 않는다.** 저장된 문자열과 응답의 `payload` 가 문자 단위로 같아야 한다.
     */
    @Test
    fun `프레임 원문이 저장된 그대로 실린다`() {
        val stationId = "CS-OPS-VERBATIM"
        val payload = """[2,"ops-verbatim-1","BootNotification",{"reason":"PowerUp","chargingStation":{"model":"m","vendorName":"v"}}]"""
        eventLog.append(
            stationId = stationId,
            direction = MessageDirection.INBOUND,
            action = "BootNotification",
            messageId = "ops-verbatim-1",
            payload = payload,
            occurredAt = Instant.parse("2026-08-31T00:00:01Z"),
        )

        val body = events(stationId)
        val event = assertNotNull(
            body.path("events").firstOrNull { it.path("messageId").asText() == "ops-verbatim-1" },
            "방금 적은 프레임이 응답에 없다",
        )
        assertEquals(payload, event.path("payload").asText(), "원문이 바뀌었다")
        assertEquals("INBOUND", event.path("direction").asText())
        assertEquals("BootNotification", event.path("action").asText())
    }

    /**
     * ★ **꼬리만 보여 주면서 전체 건수를 함께 말한다.**
     *
     * 200 건만 보여 주며 "이게 전부"라고 하면 거짓이 된다. `limit` 을 3 으로 줄여 그 상황을
     * 만들고, `total` 이 실제 건수를 말하는지 본다. 아울러 **가장 최근 것**이 오는지도 본다 —
     * 앞에서 자르면 화면은 늘 옛날 것만 보여 준다.
     */
    @Test
    fun `limit 은 최근 것을 자르고 total 은 전체를 말한다`() {
        val stationId = "CS-OPS-TAIL"
        repeat(5) { index ->
            eventLog.append(
                stationId = stationId,
                direction = MessageDirection.OUTBOUND,
                action = "Heartbeat",
                messageId = "ops-tail-$index",
                payload = """[3,"ops-tail-$index",{}]""",
                occurredAt = Instant.parse("2026-08-31T00:01:0${index}Z"),
            )
        }

        val body = events(stationId, limit = 3)
        assertEquals(5, body.path("total").asInt(), "전체 건수가 틀렸다")
        assertEquals(3, body.path("limit").asInt())

        val ids = body.path("events").map { it.path("messageId").asText() }
        assertEquals(listOf("ops-tail-2", "ops-tail-3", "ops-tail-4"), ids, "최근 것이 아니라 옛것을 잘랐다")
    }

    /** 상한을 넘겨 물어도 서버가 잘라 준다 — 응답 하나가 수십 MB 가 되는 길을 막는다. */
    @Test
    fun `limit 은 상한에서 멈춘다`() {
        val stationId = "CS-OPS-CAP"
        eventLog.append(
            stationId = stationId,
            direction = MessageDirection.INBOUND,
            action = "Heartbeat",
            messageId = "ops-cap-1",
            payload = """[2,"ops-cap-1","Heartbeat",{}]""",
            occurredAt = Instant.parse("2026-08-31T00:02:00Z"),
        )

        assertEquals(1000, events(stationId, limit = 99_999).path("limit").asInt(), "상한을 넘겼다")
        assertEquals(1, events(stationId, limit = 0).path("limit").asInt(), "0 이하를 그대로 받았다")
    }

    /**
     * ★ **화면이 실제로 서빙되는지 아무도 확인하지 않고 있었다.**
     *
     * 빌드 검사는 이 파일의 **내용**만 본다(밖을 참조하지 않는가). 그런데 리소스 디렉토리가
     * 바뀌거나 `spring.web.resources.add-mappings` 가 꺼지거나 `@EnableWebMvc` 가 붙으면
     * `/` 는 조용히 404 가 되고 **게이트는 전부 초록으로 남는다.** README 가 "`/` 에 화면이
     * 뜬다"고 약속하는 이상, 그 약속을 붙드는 단언이 하나는 있어야 한다.
     *
     * 화면 자체는 `/api` 밖이라 자격증명 없이 열린다 — 그 사실도 여기서 함께 고정된다.
     */
    @Test
    fun `운영 화면이 인증 없이 HTML 로 서빙된다`() {
        val page = TestRestTemplate().getForEntity("http://localhost:$port/", String::class.java)

        assertEquals(HttpStatus.OK, page.statusCode, "운영 화면이 뜨지 않는다")
        assertTrue(
            page.headers.contentType?.toString().orEmpty().contains("text/html"),
            "HTML 로 내려오지 않는다: ${page.headers.contentType}",
        )
        assertTrue(
            requireNotNull(page.body).contains("SwapVe — CSMS operations"),
            "다른 문서가 그 자리에 있다",
        )
    }

    // ------------------------------------------------------------------ 지원

    private fun stations(): List<JsonNode> {
        val response = rest.getForEntity("/api/stations", String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode, "목록을 받지 못했다: ${response.body}")
        return mapper.readTree(response.body).toList()
    }

    private fun events(stationId: String, limit: Int? = null): JsonNode {
        val query = if (limit == null) "" else "?limit=$limit"
        val response = rest.getForEntity("/api/stations/$stationId/events$query", String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode, "프레임을 받지 못했다: ${response.body}")
        return mapper.readTree(response.body)
    }
}
