package dev.swapve.csms.ocpp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.csms.auth.AuthorizationRegistry
import dev.swapve.csms.auth.AuthorizationStatus
import dev.swapve.csms.event.JdbcOcppEventLog
import dev.swapve.csms.station.StationRegistry
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.support.OcppTestClient
import dev.swapve.csms.ws.AuthMethod
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.schema.PayloadValidation
import dev.swapve.ocpp.session.MessageDirection
import dev.swapve.swap.IdToken
import dev.swapve.swap.StationId
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실제 WebSocket 연결 위에서 OCPP 메시지를 주고받는다 — 부팅·하트비트·S01 인가.
 *
 * 클라이언트는 JDK 내장 WebSocket 이다. 서버가 실제로 기동해 연결을 받고, 프레임이 M4 의
 * 세션 계층을 지나 우리 핸들러까지 갔다가 돌아오는 전 경로를 그대로 지난다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
class OcppEndpointTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var stations: StationRegistry

    @Autowired
    private lateinit var authorizations: AuthorizationRegistry

    @Autowired
    private lateinit var validator: OcppPayloadValidator

    @Autowired
    private lateinit var eventLog: JdbcOcppEventLog

    private val mapper = ObjectMapper()

    // ------------------------------------------------------------------ BootNotification

    @Test
    fun `BootNotification 에 스키마를 통과하는 CALLRESULT 로 답한다`() {
        connect("CS-BOOT").use { client ->
            val payload = callResult(client, "b1", "BootNotification", BOOT_PAYLOAD)

            assertEquals("Accepted", payload["status"].asText())
            assertEquals(FixedClockConfig.FIXED_NOW_TEXT, payload["currentTime"].asText())
            assertEquals(300, payload["interval"].asInt())
            assertSchemaValid("BootNotification", payload)
        }
    }

    @Test
    fun `BootNotification 이 스테이션 등록 정보를 남긴다`() {
        connect("CS-REGISTER").use { client ->
            callResult(client, "b1", "BootNotification", BOOT_PAYLOAD)
        }

        val registration = assertNotNull(stations.find(StationId("CS-REGISTER")))
        assertEquals("SwapVe", registration.vendorName)
        assertEquals("SwapVe-Station-1", registration.model)
        assertEquals("PowerUp", registration.bootReason)
        assertEquals(FixedClockConfig.FIXED_NOW, registration.bootedAt)
        // 값이 항상 하나여도 둔다.
        assertEquals("swapve", registration.operatorId.value)
    }

    @Test
    fun `StationPrincipal 이 핸들러까지 전달된다`() {
        connect("CS-PRINCIPAL").use { client ->
            callResult(client, "b1", "BootNotification", BOOT_PAYLOAD)
        }

        // 핸들러가 stationId 문자열이 아니라 StationPrincipal 을 받았다는 증거다 —
        // 문자열만 받았다면 authMethod 를 적을 방법이 없다.
        val registration = assertNotNull(stations.find(StationId("CS-PRINCIPAL")))
        assertEquals(AuthMethod.NONE, registration.authMethod)
    }

    @Test
    fun `퍼센트 인코딩된 식별자가 복원된 채로 핸들러에 닿는다`() {
        OcppTestClient.connect(port, "/ocpp/RDAM%7C123").use { client ->
            callResult(client, "b1", "BootNotification", BOOT_PAYLOAD)
        }

        assertNotNull(stations.find(StationId("RDAM|123")), "RDAM%7C123 이 RDAM|123 으로 복원되지 않았다")
    }

    // ------------------------------------------------------------------ Heartbeat

    @Test
    fun `Heartbeat 의 currentTime 이 주입된 Clock 을 따른다`() {
        connect("CS-HEARTBEAT").use { client ->
            val payload = callResult(client, "h1", "Heartbeat", "{}")

            assertEquals(FixedClockConfig.FIXED_NOW_TEXT, payload["currentTime"].asText())
            assertSchemaValid("Heartbeat", payload)
        }
    }

    // ------------------------------------------------------------------ Authorize (S01)

    @Test
    fun `인가된 idToken 은 Accepted 다`() {
        connect("CS-AUTH-OK").use { client ->
            val payload = callResult(client, "a1", "Authorize", authorizePayload("RFID-0001", "ISO14443"))

            assertEquals("Accepted", payload["idTokenInfo"]["status"].asText())
            assertSchemaValid("Authorize", payload)
        }
    }

    @Test
    fun `인가가 나면 그 사실이 남는다 - 첫 BatterySwap 이 requestId 를 실어 올 때까지`() {
        connect("CS-AUTH-GRANT").use { client ->
            callResult(client, "a1", "Authorize", authorizePayload("RFID-0001", "ISO14443"))
        }

        // S01 의 requestId 는 스테이션이 발번한다. 인가 시점의 CSMS 는 그것을
        // 모르므로 교환 트랜잭션을 열지 않고 인가 사실만 붙잡아 둔다.
        // 근거는 AuthorizationRegistry KDoc 참조.
        val grant = assertNotNull(
            authorizations.grantOf(StationId("CS-AUTH-GRANT"), IdToken("RFID-0001", "ISO14443")),
        )
        assertEquals(FixedClockConfig.FIXED_NOW, grant.at)
    }

    @Test
    fun `미등록 idToken 은 거부되지만 기록은 남는다`() {
        val before = authorizations.attempts().size

        connect("CS-AUTH-UNKNOWN").use { client ->
            val payload = callResult(client, "a1", "Authorize", authorizePayload("RFID-UNKNOWN", "ISO14443"))

            // Invalid 가 아니라 Unknown 이다 — 로밍이 붙으면 외부 조회로 뒤집힐 수 있는 판정이다
            //.
            assertEquals("Unknown", payload["idTokenInfo"]["status"].asText())
            assertSchemaValid("Authorize", payload)
        }

        val attempts = authorizations.attempts()
        assertEquals(before + 1, attempts.size, "거부된 인가 시도가 기록되지 않았다")

        val recorded = attempts.last()
        assertEquals(AuthorizationStatus.UNKNOWN, recorded.status)
        assertEquals(IdToken("RFID-UNKNOWN", "ISO14443"), recorded.idToken)
        assertEquals(StationId("CS-AUTH-UNKNOWN"), recorded.stationId)

        // 거부된 토큰에는 인가 사실이 남지 않는다.
        assertNull(authorizations.grantOf(StationId("CS-AUTH-UNKNOWN"), IdToken("RFID-UNKNOWN", "ISO14443")))
    }

    @Test
    fun `같은 값이라도 type 이 다르면 다른 토큰이다`() {
        connect("CS-AUTH-TYPE").use { client ->
            // (idToken, type) 값 객체다. 문자열 하나로 다루면 여기서 Accepted 가 나온다.
            val payload = callResult(client, "a1", "Authorize", authorizePayload("RFID-0001", "Central"))

            assertEquals("Unknown", payload["idTokenInfo"]["status"].asText())
        }
    }

    // ------------------------------------------------------------------ 오류 처리

    @Test
    fun `손상 프레임에는 CALLERROR 로 답하고 연결은 유지된다`() {
        connect("CS-MALFORMED").use { client ->
            client.send("이것은 JSON 이 아니다")

            val frame = mapper.readTree(client.receive())
            assertEquals(4, frame[0].asInt(), "CALLERROR(4) 가 아니다: $frame")

            // 연결이 살아 있어야 한다 — 프레임 하나가 깨졌다고 세션을 끊으면 재접속 폭풍이 난다.
            assertTrue(client.isOpen)
            val payload = callResult(client, "h1", "Heartbeat", "{}")
            assertEquals(FixedClockConfig.FIXED_NOW_TEXT, payload["currentTime"].asText())
        }
    }

    @Test
    fun `스키마조차 없는 action 은 NotImplemented 다`() {
        connect("CS-UNKNOWN-ACTION").use { client ->
            client.send("""[2,"u1","NoSuchAction",{}]""")

            val frame = mapper.readTree(client.receive())
            assertEquals(4, frame[0].asInt())
            assertEquals("u1", frame[1].asText())
            assertEquals("NotImplemented", frame[2].asText())
        }
    }

    @Test
    fun `스키마는 있지만 구현하지 않은 action 도 NotImplemented 다`() {
        connect("CS-UNIMPLEMENTED").use { client ->
            // DataTransferRequest 스키마는 통과한다. 막히는 곳은 라우터다.
            client.send("""[2,"d1","DataTransfer",{"vendorId":"swapve"}]""")

            val frame = mapper.readTree(client.receive())
            assertEquals(4, frame[0].asInt())
            assertEquals("NotImplemented", frame[2].asText())
        }
    }

    @Test
    fun `스키마를 어긴 페이로드는 CALLERROR 다`() {
        connect("CS-INVALID-PAYLOAD").use { client ->
            // chargingStation 이 없다 — BootNotificationRequest 의 필수 필드다.
            client.send("""[2,"b1","BootNotification",{"reason":"PowerUp"}]""")

            val frame = mapper.readTree(client.receive())
            assertEquals(4, frame[0].asInt())
            assertEquals("OccurrenceConstraintViolation", frame[2].asText())
        }
    }

    // ------------------------------------------------------------------ 재접속과 멱등 (M4 배선 확인)

    @Test
    fun `끊겼다 다시 붙으면 새 세션으로 동작하고 재전송된 CALL 은 멱등 처리된다`() {
        val station = "CS-RECONNECT"
        val frame = """[2,"same-id","Authorize",${authorizePayload("RFID-0001", "ISO14443")}]"""

        val before = authorizations.attempts().size

        val first = connect(station).use { client ->
            client.send(frame)
            client.receive()
        }

        assertEquals(before + 1, authorizations.attempts().size)

        // 새 연결이다. 그런데 멱등 원장은 stationId 로 이어져 있다 (F6).
        val second = connect(station).use { client ->
            client.send(frame)
            val replayed = client.receive()

            // 새 세션이 정상 동작한다는 것도 함께 확인한다.
            val heartbeat = callResult(client, "h-after", "Heartbeat", "{}")
            assertEquals(FixedClockConfig.FIXED_NOW_TEXT, heartbeat["currentTime"].asText())

            replayed
        }

        assertEquals(first, second, "재전송에 저장된 응답이 그대로 돌아오지 않았다")
        assertEquals(
            before + 1,
            authorizations.attempts().size,
            "재전송된 CALL 이 인가를 한 번 더 처리했다 — 멱등이 깨졌다",
        )
    }

    // ------------------------------------------------------------------ 이벤트 로그

    @Test
    fun `오간 메시지가 원문 그대로 이벤트 로그에 남는다`() {
        val stationId = "CS-EVENTLOG-${System.nanoTime()}"
        connect(stationId).use { client ->
            callResult(client, "h1", "Heartbeat", "{}")
        }

        val records = eventLog.of(stationId)
        assertEquals(2, records.size, "수신 1건 · 송신 1건이 남아야 한다: $records")
        assertEquals(MessageDirection.INBOUND, records[0].direction)
        assertEquals("Heartbeat", records[0].action)
        assertEquals(MessageDirection.OUTBOUND, records[1].direction)
        assertEquals(FixedClockConfig.FIXED_NOW, records[0].occurredAt)
    }

    // ------------------------------------------------------------------ 도우미

    private fun connect(stationId: String) = OcppTestClient.connect(port, "/ocpp/$stationId")

    /** CALL 을 보내고 CALLRESULT 페이로드를 돌려준다. CALLERROR 가 오면 실패한다. */
    private fun callResult(client: OcppTestClient, messageId: String, action: String, payload: String): JsonNode {
        client.send("""[2,"$messageId","$action",$payload]""")
        val frame = mapper.readTree(client.receive(RESPONSE_TIMEOUT))

        assertEquals(3, frame[0].asInt(), "CALLRESULT(3) 가 아니다: $frame")
        assertEquals(messageId, frame[1].asText())
        return frame[2]
    }

    /**
     * 우리가 내보낸 응답이 공식 `<Action>Response` 스키마를 통과하는지 클라이언트 쪽에서
     * 다시 확인한다.
     *
     * 라우터도 보내기 전에 같은 검사를 한다. 여기서 또 하는 이유는 그 검사가 실제로 배선돼
     * 있는지가 아니라 **전선을 타고 나온 바이트가 스키마에 맞는지**를 보기 위해서다.
     */
    private fun assertSchemaValid(action: String, payload: JsonNode) {
        val validation = validator.validateCallResult(action, payload)
        assertTrue(
            validation !is PayloadValidation.Invalid,
            "${action}Response 가 공식 스키마를 통과하지 못했다: $validation",
        )
        assertIs<ObjectNode>(payload)
    }

    private fun authorizePayload(idToken: String, type: String) =
        """{"idToken":{"idToken":"$idToken","type":"$type"}}"""

    private companion object {
        val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(5)

        val BOOT_PAYLOAD = """
            {"reason":"PowerUp","chargingStation":{"vendorName":"SwapVe","model":"SwapVe-Station-1",
            "serialNumber":"SN-0001","firmwareVersion":"1.0.0"}}
        """.trimIndent().replace("\n", "")
    }
}
