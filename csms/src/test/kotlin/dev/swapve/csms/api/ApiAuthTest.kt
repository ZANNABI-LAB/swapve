package dev.swapve.csms.api

import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.console.ControlledStations
import dev.swapve.console.SimConsoleServer
import dev.swapve.csms.support.ApiCredentialsInitializer
import dev.swapve.csms.support.TestCredentials
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ContextConfiguration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiAuthTest {

    @Nested
    @SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = [
            "csms.security.stations[0].station-id=CS001",
            "csms.security.stations[0].password-hash=${TestCredentials.PASSWORD_HASH}",
        ],
    )
    @ContextConfiguration(initializers = [ApiCredentialsInitializer::class])
    inner class Rest {

        @LocalServerPort
        private var port: Int = 0

        @Autowired
        private lateinit var rest: TestRestTemplate

        @Test
        fun `자격증명이 없으면 401 과 WWW-Authenticate 로 거절한다`() {
            val response = TestRestTemplate().getForEntity(url("/api/metrics/swaps"), String::class.java)

            assertEquals(401, response.statusCode.value())
            assertEquals("Basic realm=\"swapve-api\", charset=\"UTF-8\"", response.headers.getFirst("WWW-Authenticate"))
            assertEquals("UNAUTHORIZED", mapper.readTree(requireNotNull(response.body)).path("error").asText())
        }

        @Test
        fun `비밀번호가 틀리면 401 로 거절한다`() {
            val response = TestRestTemplate().withBasicAuth(TestCredentials.API_USER, "wrong")
                .getForEntity(url("/api/metrics/swaps"), String::class.java)

            assertEquals(401, response.statusCode.value())
        }

        @Test
        fun `올바른 API 자격증명은 기존 API 응답을 그대로 돌려준다`() {
            val response = rest.getForEntity("/api/metrics/swaps", String::class.java)

            assertEquals(200, response.statusCode.value())
            assertTrue(mapper.readTree(requireNotNull(response.body)).path("swaps").isObject)
        }

        @Test
        fun `스테이션 자격증명은 REST API 를 열 수 없다`() {
            val response = TestRestTemplate().withBasicAuth("CS001", TestCredentials.PASSWORD)
                .getForEntity(url("/api/metrics/swaps"), String::class.java)

            assertEquals(401, response.statusCode.value())
        }

        private fun url(path: String) = "http://localhost:$port$path"
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    inner class EmptyRestCredentials {

        @LocalServerPort
        private var port: Int = 0

        @Test
        fun `자격증명 목록이 비어 있으면 모든 REST 요청을 401 로 닫는다`() {
            val response = TestRestTemplate()
                .withBasicAuth(TestCredentials.API_USER, TestCredentials.API_PASSWORD)
                .getForEntity("http://localhost:$port/api/metrics/swaps", String::class.java)

            assertEquals(401, response.statusCode.value())
            assertEquals("Basic realm=\"swapve-api\", charset=\"UTF-8\"", response.headers.getFirst("WWW-Authenticate"))
        }
    }

    @Nested
    inner class Console {

        private val http: HttpClient = HttpClient.newHttpClient()

        @Test
        fun `기본 생성은 loopback 에 바인딩한다`() {
            SimConsoleServer(ControlledStations("ws://localhost:1/ocpp"), port = 0).use { console ->
                assertTrue(console.bindAddress.isLoopbackAddress)
            }
        }

        @Test
        fun `loopback 이 아닌 주소는 자격증명 없이는 기동을 거부한다`() {
            val failure = kotlin.runCatching {
                SimConsoleServer(ControlledStations("ws://localhost:1/ocpp"), port = 0, bindAddress = "0.0.0.0")
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
        }

        @Test
        fun `자격증명을 지정하면 콘솔 전 경로를 Basic 으로 보호한다`() {
            SimConsoleServer(
                ControlledStations("ws://localhost:1/ocpp"),
                port = 0,
                credentials = SimConsoleServer.Credentials("console", "secret"),
            ).start().use { console ->
                val unauthorized = http.send(
                    HttpRequest.newBuilder(uri(console, "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(401, unauthorized.statusCode())
                assertEquals(
                    "Basic realm=\"sim-console\", charset=\"UTF-8\"",
                    unauthorized.headers().firstValue("WWW-Authenticate").orElse(""),
                )

                val authorized = http.send(
                    HttpRequest.newBuilder(uri(console, "/"))
                        .header("Authorization", basic("console", "secret"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, authorized.statusCode())
            }
        }

        private fun uri(console: SimConsoleServer, path: String): URI =
            URI.create("http://localhost:${console.port}$path")

        private fun basic(username: String, password: String): String {
            val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
            return "Basic $encoded"
        }
    }

    private companion object {
        val mapper = ObjectMapper()
    }
}
