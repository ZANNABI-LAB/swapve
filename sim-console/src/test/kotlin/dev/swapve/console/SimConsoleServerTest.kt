package dev.swapve.console

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 서버의 표면 — **스테이션을 하나도 붙이지 않고** 확인할 수 있는 것 전부.
 *
 * `csms` 의 `SimConsoleControlTest` 는 CSMS 를 띄우고 교환까지 완주시키는 통합 시험이라
 * 느리고, 그래서 인증 거절·경로·메서드 같은 자리는 덮지 않는다. 여기서는 콘솔만 띄운다.
 *
 * 포트는 `0` 으로 띄워 OS 가 준 것을 쓴다 — 고정 포트를 쓰면 시험끼리, 그리고 개발자가
 * 띄워 둔 콘솔과 충돌한다.
 */
class SimConsoleServerTest {

    private val mapper = ObjectMapper()
    private val client: HttpClient = HttpClient.newHttpClient()

    private fun server(credentials: SimConsoleServer.Credentials? = null) =
        SimConsoleServer(ControlledStations("ws://localhost:8080/ocpp"), port = 0, credentials = credentials).start()

    private fun get(
        server: SimConsoleServer,
        path: String,
        authorization: String? = null,
        method: String = "GET",
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .apply { authorization?.let { header("Authorization", it) } }
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun basic(user: String, password: String) =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    @Test
    fun `화면 한 장이 그대로 나온다`() {
        server().use { console ->
            val response = get(console, "/")

            assertEquals(200, response.statusCode())
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"))
            assertTrue("<html" in response.body().lowercase(), "HTML 이 아니다")
        }
    }

    @Test
    fun `상태에는 장애 시나리오 목록이 코드에서 실려 온다`() {
        server().use { console ->
            val response = get(console, "/api/state")
            assertEquals(200, response.statusCode())

            val body = mapper.readTree(response.body())
            assertEquals("ws://localhost:8080/ocpp", body.path("defaultCsmsUrl").asText())
            assertTrue(body.path("stations").isEmpty, "아무것도 안 붙였는데 스테이션이 있다")

            // 화면이 F1~F6 의 뜻을 따로 적지 않는다는 약속. 목록의 출처는 코드 한 곳이다.
            val ids = body.path("faults").map { it.path("id").asText() }
            assertEquals(FaultScenario.entries.map { it.name }, ids)
            assertTrue(body.path("faults").all { it.path("expectation").asText().isNotBlank() })
        }
    }

    @Test
    fun `없는 경로와 안 되는 메서드가 갈린다`() {
        server().use { console ->
            assertEquals(404, get(console, "/nope").statusCode())
            assertEquals(405, get(console, "/", method = "DELETE").statusCode())
            assertEquals(405, get(console, "/api/state", method = "DELETE").statusCode())
        }
    }

    @Test
    fun `자격 증명을 걸면 없이는 아무것도 안 준다`() {
        server(SimConsoleServer.Credentials("ops", "s3cret")).use { console ->
            assertEquals(401, get(console, "/").statusCode())
            assertEquals(401, get(console, "/api/state").statusCode())

            // 틀린 비밀번호와 없는 헤더가 같은 답을 받는다 — 어느 쪽이 틀렸는지 알려주지 않는다.
            assertEquals(401, get(console, "/api/state", basic("ops", "wrong")).statusCode())
            assertEquals(401, get(console, "/api/state", basic("nobody", "s3cret")).statusCode())
            assertEquals(401, get(console, "/api/state", "Basic not-base64").statusCode())
            assertEquals(401, get(console, "/api/state", "Bearer s3cret").statusCode())

            assertEquals(200, get(console, "/api/state", basic("ops", "s3cret")).statusCode())
        }
    }

    /**
     * 밖으로 열린 주소에 인증 없이 뜨면, 같은 망의 누구나 남의 CSMS 로 스테이션을 붙일 수
     * 있다. 그래서 이것은 경고가 아니라 **거절**이다.
     */
    @Test
    fun `루프백이 아닌 주소는 자격 증명 없이 열리지 않는다`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SimConsoleServer(ControlledStations("ws://localhost:8080/ocpp"), port = 0, bindAddress = "0.0.0.0")
        }
        assertTrue("requires --user and --password" in failure.message.orEmpty(), failure.message.orEmpty())

        // 자격 증명을 주면 같은 주소로 뜬다.
        SimConsoleServer(
            ControlledStations("ws://localhost:8080/ocpp"),
            port = 0,
            bindAddress = "0.0.0.0",
            credentials = SimConsoleServer.Credentials("ops", "s3cret"),
        ).use { assertTrue(it.port > 0) }
    }

    @Test
    fun `없는 스테이션을 조종하려 하면 404 다`() {
        server().use { console ->
            assertEquals(404, get(console, "/api/stations/CS-nope", method = "DELETE").statusCode())
        }
    }
}
