package dev.swapve.csms.ws

import dev.swapve.csms.config.CsmsProperties
import dev.swapve.csms.support.TestCredentials
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BasicAuthenticatorTest {

    private val authenticator = BasicAuthenticator(
        listOf(CsmsProperties.StationCredential("CS001", TestCredentials.PASSWORD_HASH)),
    )

    @Test
    fun `Basic 헤더를 첫 콜론으로 username 과 password 로 나눈다`() {
        val parsed = authenticator.parse(TestCredentials.basic("CS001", "pw:with:colon"))

        assertNotNull(parsed)
        assertEquals("CS001", parsed.username)
        assertEquals("pw:with:colon", parsed.password)
    }

    @Test
    fun `형식이 아닌 Authorization 은 거절한다`() {
        assertEquals(null, authenticator.parse(null))
        assertEquals(null, authenticator.parse("Bearer token"))
        assertEquals(null, authenticator.parse("Basic not-base64"))
        val noSeparator = Base64.getEncoder().encodeToString("CS001password".toByteArray(Charsets.UTF_8))
        assertEquals(null, authenticator.parse("Basic $noSeparator"))
    }

    @Test
    fun `등록된 stationId 와 비밀번호가 맞으면 인증된다`() {
        val result = authenticator.authenticate("CS001", TestCredentials.basic("CS001"))

        assertTrue(result.authenticated, result.reason)
    }

    @Test
    fun `경로 stationId 와 Basic username 이 다르면 거절한다`() {
        val result = authenticator.authenticate("CS002", TestCredentials.basic("CS001"))

        assertFalse(result.authenticated)
    }

    @Test
    fun `등록되지 않은 username 과 틀린 비밀번호는 거절한다`() {
        assertFalse(authenticator.authenticate("CS999", TestCredentials.basic("CS999")).authenticated)
        assertFalse(authenticator.authenticate("CS001", TestCredentials.basic("CS001", "wrong")).authenticated)
    }
}
