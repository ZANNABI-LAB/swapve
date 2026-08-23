package dev.swapve.csms.ws

import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.support.OcppTestClient
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 같은 스테이션이 다시 접속하면 이전 연결의 소켓까지 닫힌다.
 *
 * 세션만 닫고 소켓을 열어 두면 그 연결은 프레임을 받고도 아무 답을 하지 않는다. 상대는
 * 타임아웃까지 기다린다 — 끊어 주는 편이 낫다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
class SessionReplacementTest {

    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    fun `재접속하면 이전 연결의 WebSocket 이 서버에 의해 닫힌다`() {
        val station = "CS-REPLACE-SOCKET"

        OcppTestClient.connect(port, "/ocpp/$station").use { first ->
            OcppTestClient.connect(port, "/ocpp/$station").use { second ->
                assertTrue(first.awaitClosed(), "이전 연결이 닫히지 않았다 — 조용히 살아 있는 소켓이 남는다")
                assertFalse(first.isOpen)

                // 새 연결은 멀쩡하다. 동일성이 아니라 stationId 로 이전 연결을 찾으면 여기가 깨진다.
                assertTrue(second.isOpen)
                val frame = mapper.readTree(bootNotification(second))
                assertEquals(3, frame[0].asInt(), "CALLRESULT(3) 가 아니다: $frame")
                assertEquals("b1", frame[1].asText())
                assertEquals("Accepted", frame[2]["status"].asText())
            }
        }
    }

    private fun bootNotification(client: OcppTestClient): String {
        client.send("""[2,"b1","BootNotification",$BOOT_PAYLOAD]""")
        return client.receive()
    }

    private companion object {
        val BOOT_PAYLOAD = """
            {"reason":"PowerUp","chargingStation":{"vendorName":"SwapVe","model":"SwapVe-Station-1",
            "serialNumber":"SN-0001","firmwareVersion":"1.0.0"}}
        """.trimIndent().replace("\n", "")
    }
}
