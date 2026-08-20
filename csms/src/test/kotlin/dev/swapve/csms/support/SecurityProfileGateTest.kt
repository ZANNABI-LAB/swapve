package dev.swapve.csms.support

import dev.swapve.csms.config.CsmsProperties
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ContextConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("conformance")
@Tag("audit")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = [BasicAuthStations::class])
class SecurityProfileGateTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var properties: CsmsProperties

    @Test
    fun `게이트는 BASIC 프로파일과 등록 스테이션 자격증명으로 돈다`() {
        assertEquals(CsmsProperties.SecurityProfile.BASIC, properties.security.profile)

        val stationId = TestStations.TC_S_102
        val rejected = HandshakeProbe.handshake(port, "/ocpp/$stationId")
        assertEquals(401, rejected.status)
        assertEquals(listOf("Basic realm=\"ocpp\", charset=\"UTF-8\""), rejected.header("WWW-Authenticate"))

        val accepted = HandshakeProbe.handshake(
            port,
            "/ocpp/$stationId",
            headers = mapOf("Authorization" to TestCredentials.basic(stationId)),
        )
        assertTrue(accepted.isSwitchingProtocols, accepted.statusLine)
    }
}
