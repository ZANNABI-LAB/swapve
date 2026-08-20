package dev.swapve.csms.ws

import dev.swapve.csms.station.StationRegistry
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.support.HandshakeProbe
import dev.swapve.csms.support.TestCredentials
import dev.swapve.station.SimBattery
import dev.swapve.station.SlotConfig
import dev.swapve.station.StationSimConfig
import dev.swapve.station.StationSimulator
import dev.swapve.swap.IdToken
import dev.swapve.swap.StationId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BasicAuthTest {

    @Nested
    @SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = [
            "csms.security.profile=BASIC",
            "csms.security.stations[0].station-id=CS001",
            "csms.security.stations[0].password-hash=${TestCredentials.PASSWORD_HASH}",
        ],
    )
    @Import(FixedClockConfig::class)
    inner class BasicProfile {

        @LocalServerPort
        private var port: Int = 0

        @Autowired
        private lateinit var stations: StationRegistry

        @Test
        fun `자격증명이 없으면 401 과 WWW-Authenticate 로 거절한다`() {
            val response = HandshakeProbe.handshake(port, "/ocpp/CS001")

            assertEquals(401, response.status)
            assertEquals(listOf("Basic realm=\"ocpp\", charset=\"UTF-8\""), response.header("WWW-Authenticate"))
            assertNoAuthReasonLeak(response)
        }

        @Test
        fun `비밀번호가 틀리면 401 로 거절한다`() {
            val response = HandshakeProbe.handshake(
                port,
                "/ocpp/CS001",
                headers = mapOf("Authorization" to TestCredentials.basic("CS001", "wrong")),
            )

            assertEquals(401, response.status)
            assertNoAuthReasonLeak(response)
        }

        @Test
        fun `미등록 스테이션은 401 로 거절한다`() {
            val response = HandshakeProbe.handshake(
                port,
                "/ocpp/CS999",
                headers = mapOf("Authorization" to TestCredentials.basic("CS999")),
            )

            assertEquals(401, response.status)
            assertNoAuthReasonLeak(response)
        }

        @Test
        fun `경로 id 와 Basic username 이 다르면 401 로 거절한다`() {
            val response = HandshakeProbe.handshake(
                port,
                "/ocpp/CS001",
                headers = mapOf("Authorization" to TestCredentials.basic("CS002")),
            )

            assertEquals(401, response.status)
            assertNoAuthReasonLeak(response)
        }

        @Test
        fun `올바른 자격증명은 101 로 연결되고 등록에 BASIC 이 남는다`() {
            val response = HandshakeProbe.handshake(
                port,
                "/ocpp/CS001",
                headers = mapOf("Authorization" to TestCredentials.basic("CS001")),
            )

            assertTrue(response.isSwitchingProtocols, response.statusLine)

            StationSimulator(config(port, "CS001", password = TestCredentials.PASSWORD)).use { simulator ->
                runBlocking {
                    simulator.connect()
                    simulator.boot()
                }
            }

            assertEquals(AuthMethod.BASIC, stations.find(StationId("CS001"))?.authMethod)
        }
    }

    @Nested
    @SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = ["csms.security.profile=NONE"],
    )
    @Import(FixedClockConfig::class)
    inner class NoneProfile {

        @LocalServerPort
        private var port: Int = 0

        @Autowired
        private lateinit var stations: StationRegistry

        @Test
        fun `자격증명 없이 연결되고 등록에 NONE 이 남는다`() {
            val response = HandshakeProbe.handshake(port, "/ocpp/CS-NONE")

            assertTrue(response.isSwitchingProtocols, response.statusLine)

            StationSimulator(config(port, "CS-NONE", password = null)).use { simulator ->
                runBlocking {
                    simulator.connect()
                    simulator.boot()
                }
            }

            assertEquals(AuthMethod.NONE, stations.find(StationId("CS-NONE"))?.authMethod)
        }
    }

    private fun assertNoAuthReasonLeak(response: HandshakeProbe.Response) {
        assertFalse(response.headers.values.flatten().any { "자격" in it || "credential" in it.lowercase() })
    }

    private fun config(port: Int, stationId: String, password: String?) = StationSimConfig(
        csmsUrl = "ws://localhost:$port/ocpp",
        stationId = stationId,
        password = password,
        slots = listOf(
            SlotConfig(1, battery = null),
            SlotConfig(2, battery = SimBattery("BAT-BASIC-2", soC = 90.0, soH = 95.0)),
        ),
        idToken = IdToken("RFID-0001", "ISO14443"),
        requestId = 1,
        insertSlots = listOf(1),
        dispenseSlots = listOf(2),
        incomingBatteries = listOf(SimBattery("BAT-BASIC-1", soC = 20.0, soH = 90.0)),
    )
}
