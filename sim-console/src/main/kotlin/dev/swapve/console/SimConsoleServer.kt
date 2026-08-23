package dev.swapve.console

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.swapve.station.SwapOrder
import dev.swapve.swap.IdToken
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors

/**
 * 시뮬레이터 제어 콘솔의 HTTP 얼굴 (B24).
 *
 * ### JDK 내장 서버로 끝낸다
 *
 * `com.sun.net.httpserver.HttpServer` 하나다. Spring 도 Ktor 도 들이지 않는다 —
 * `station-sim` 이 전송에 JDK 내장 WebSocket 을 쓴 것과 같은 취지고, 그 사실은
 * `:sim-console:checkNoForbiddenDependencies` 가 기계로 확인한다.
 *
 * ### 이것은 관제 서버가 아니다
 *
 * 여기 있는 것은 **시험계를 조종하는 손잡이**뿐이다. 교환 이력도 지표도 없다 — 그건 CSMS 의
 * REST API 가 하는 일이고 (docs/API.md), 콘솔이 그것을 흉내내면 두 벌의 진실이 생긴다.
 * 콘솔은 CSMS 를 향해 아무것도 부르지 않는다.
 *
 * @param port `0` 이면 빈 포트를 받아 [SimConsoleServer.port] 로 알려준다. 시험이 쓴다.
 */
class SimConsoleServer(
    private val stations: ControlledStations,
    port: Int = DEFAULT_PORT,
    bindAddress: String = DEFAULT_BIND_ADDRESS,
    private val credentials: Credentials? = null,
) : AutoCloseable {

    private val address = InetAddress.getByName(bindAddress)
    private val http: HttpServer

    /** 실제로 바인딩된 포트. `0` 으로 띄웠을 때 이 값을 봐야 한다. */
    val port: Int get() = http.address.port

    val bindAddress: InetAddress get() = http.address.address

    init {
        require(address.isLoopbackAddress || credentials != null) {
            "sim-console 을 loopback 이 아닌 주소($bindAddress)에 바인딩하려면 --user 와 --password 가 필요하다"
        }
        http = HttpServer.create(InetSocketAddress(address, port), BACKLOG)
        // 요청 처리는 짧다 — 오래 걸리는 교환 시퀀스는 스테이션마다의 작업 스레드로 넘긴다.
        http.executor = Executors.newFixedThreadPool(HTTP_THREADS) { runnable ->
            Thread(runnable, "sim-console-http").apply { isDaemon = true }
        }
        // 긴 접두사가 먼저 잡히므로 /api/stations/CS001/swap 은 stations 핸들러로 간다.
        http.createContext("/api/state") { exchange -> handle(exchange) { stateRoute(it) } }
        http.createContext("/api/stations") { exchange -> handle(exchange) { stationRoute(it) } }
        http.createContext("/") { exchange -> handle(exchange) { staticRoute(it) } }
    }

    fun start(): SimConsoleServer {
        http.start()
        return this
    }

    override fun close() {
        http.stop(0)
        stations.close()
    }

    // ------------------------------------------------------------------ 라우팅

    /**
     * 제어 API — 스테이션 띄우기/내리기, 교환 시작, 장애 주입.
     *
     * - `POST   /api/stations`            붙인다. 이미 있는 `stationId` 면 409
     * - `DELETE /api/stations/{id}`       내린다. 없으면 404
     * - `POST   /api/stations/{id}/swap`  교환을 시작한다. 본문의 `fault` 로 F1~F6 을 건다
     * - `POST   /api/stations/{id}/op`    조작 하나를 건다. 본문의 `op` 로 고른다 ([StationOp])
     *
     * ### `swap` 은 202 인데 `op` 는 200 이다
     *
     * 교환 한 건은 몇 초짜리 각본이라 접수만 하고 돌아간다 — 붙들고 있으면 화면이 멈춘다.
     * 조작 하나는 프레임 몇 개라 끝까지 기다렸다가 **그 뒤의 스냅샷**을 그대로 싣는다.
     * 상태 코드가 다른 것은 두 요청이 실제로 다른 약속을 하기 때문이다.
     */
    private fun stationRoute(exchange: HttpExchange): Reply {
        val segments = exchange.requestURI.path
            .removePrefix("/api/stations")
            .split('/')
            .filter { it.isNotBlank() }
            .map { URLDecoder.decode(it, StandardCharsets.UTF_8) }

        return when {
            segments.isEmpty() && exchange.requestMethod == "POST" ->
                Reply(201, snapshotOf(stations.attach(specOf(body(exchange))).snapshot()))

            segments.size == 1 && exchange.requestMethod == "DELETE" -> {
                stations.detach(segments[0])
                Reply(200, node().put("detached", segments[0]))
            }

            segments.size == 2 && segments[1] == "swap" && exchange.requestMethod == "POST" -> {
                val station = stations.find(segments[0])
                station.start(faultOf(body(exchange)))
                Reply(202, snapshotOf(station.snapshot()))
            }

            segments.size == 2 && segments[1] == "op" && exchange.requestMethod == "POST" -> {
                // 없는 스테이션은 여기서 404 로 갈린다. 본문을 읽기도 전이다.
                val station = stations.find(segments[0])
                val body = body(exchange)
                Reply(200, snapshotOf(station.operate(opOf(body), paramsOf(body))))
            }

            else -> throw ControlError(405, "다루지 않는 요청이다: ${exchange.requestMethod} ${exchange.requestURI.path}")
        }
    }

    /**
     * 상태 조회 — 화면이 주기적으로 물어 갱신한다.
     *
     * 장애 시나리오 목록도 여기 함께 싣는다. 화면이 F1~F6 의 뜻을 따로 적어 두면 코드와
     * 화면이 언젠가 어긋나므로, 설명의 출처를 [FaultScenario] 한 곳으로 둔다.
     */
    private fun stateRoute(exchange: HttpExchange): Reply {
        if (exchange.requestMethod != "GET") throw ControlError(405, "GET 만 받는다")

        val faults = array()
        FaultScenario.entries.forEach { scenario ->
            faults.add(
                node()
                    .put("id", scenario.name)
                    .put("title", scenario.title)
                    .put("expectation", scenario.expectation),
            )
        }

        val stationNodes = array()
        stations.snapshots().forEach { stationNodes.add(snapshotOf(it)) }

        return Reply(
            200,
            node()
                .put("defaultCsmsUrl", stations.defaultCsmsUrl)
                .set<ObjectNode>("faults", faults)
                .set<ObjectNode>("stations", stationNodes),
        )
    }

    /** 화면 한 장. 외부 자원을 링크하지 않으므로 이 파일 하나로 뜬다. */
    private fun staticRoute(exchange: HttpExchange): Reply {
        if (exchange.requestMethod != "GET") throw ControlError(405, "GET 만 받는다")
        if (exchange.requestURI.path !in setOf("/", "/index.html")) {
            throw ControlError(404, "없는 경로다: ${exchange.requestURI.path}")
        }

        val html = javaClass.getResourceAsStream("/console/index.html")
            ?.use { it.readBytes() }
            ?: throw ControlError(500, "화면 파일이 클래스패스에 없다: /console/index.html")
        return Reply(200, body = html, contentType = "text/html; charset=utf-8")
    }

    // ------------------------------------------------------------------ 입출력

    private fun handle(exchange: HttpExchange, route: (HttpExchange) -> Reply) {
        val reply = try {
            if (!authorized(exchange)) {
                Reply.unauthorized()
            } else {
                route(exchange)
            }
        } catch (failure: ControlError) {
            Reply(failure.status, node().put("error", failure.message))
        } catch (failure: IllegalArgumentException) {
            // 구성이 성립하지 않는다 — StationSimConfig 의 require 가 여기로 온다.
            Reply(400, node().put("error", failure.message ?: "잘못된 요청이다"))
        } catch (failure: Exception) {
            Reply(500, node().put("error", failure.message ?: failure.toString()))
        }

        try {
            exchange.responseHeaders.add("Content-Type", reply.contentType)
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.sendResponseHeaders(reply.status, reply.body.size.toLong())
            exchange.responseBody.write(reply.body)
        } finally {
            exchange.close()
        }
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        val expected = credentials ?: return true
        val parsed = parseBasic(exchange.requestHeaders.getFirst("Authorization"))
        val username = parsed?.first.orEmpty()
        val password = parsed?.second.orEmpty()

        val usernameMatches = constantEquals(username, expected.username)
        val passwordMatches = constantEquals(password, expected.password)
        return parsed != null && usernameMatches && passwordMatches
    }

    private fun parseBasic(authorization: String?): Pair<String, String>? {
        if (authorization == null) return null
        val parts = authorization.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2 || !parts[0].equals("Basic", ignoreCase = true)) return null

        val decoded = runCatching {
            Base64.getDecoder().decode(parts[1]).toString(StandardCharsets.UTF_8)
        }.getOrNull() ?: return null

        val separator = decoded.indexOf(':')
        if (separator < 0) return null
        return decoded.substring(0, separator) to decoded.substring(separator + 1)
    }

    private fun constantEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(StandardCharsets.UTF_8), right.toByteArray(StandardCharsets.UTF_8))

    private fun body(exchange: HttpExchange): JsonNode {
        val text = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        return if (text.isBlank()) node() else MAPPER.readTree(text)
    }

    private fun specOf(body: JsonNode): StationSpec {
        val csmsUrl = body.path("csmsUrl").asText(stations.defaultCsmsUrl)
        val stationId = body.path("stationId").asText("")
        if (stationId.isBlank()) throw ControlError(400, "stationId 를 적어야 한다")

        return StationSpec(
            csmsUrl = csmsUrl.ifBlank { stations.defaultCsmsUrl },
            stationId = stationId,
            slotCount = body.path("slots").asInt(DEFAULT_SLOTS),
            setSize = body.path("setSize").asInt(DEFAULT_SET_SIZE),
            swapOrder = swapOrderOf(body.path("swapOrder").asText("")),
            idToken = IdToken(
                body.path("idToken").asText(DEFAULT_ID_TOKEN).ifBlank { DEFAULT_ID_TOKEN },
                body.path("idTokenType").asText(DEFAULT_ID_TOKEN_TYPE).ifBlank { DEFAULT_ID_TOKEN_TYPE },
            ),
            requestId = body.path("requestId").asInt(DEFAULT_REQUEST_ID),
        )
    }

    private fun swapOrderOf(value: String): SwapOrder {
        if (value.isBlank()) return SwapOrder.IN_OUT
        return SwapOrder.entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) || it.name == value }
            ?: throw ControlError(400, "모르는 교환 순서다: $value (In-Out 또는 Out-In)")
    }

    private fun faultOf(body: JsonNode): FaultScenario? {
        val value = body.path("fault").asText("")
        if (value.isBlank()) return null
        return FaultScenario.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw ControlError(400, "모르는 장애 시나리오다: $value (F1~F6)")
    }

    private fun opOf(body: JsonNode): StationOp {
        val value = body.path("op").asText("")
        if (value.isBlank()) throw ControlError(400, "op 을 적어야 한다")
        return StationOp.entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) || it.name == value }
            ?: throw ControlError(
                400,
                "모르는 조작이다: $value (${StationOp.entries.joinToString { it.wireValue }})",
            )
    }

    /**
     * 인자는 **적힌 것만** 읽는다.
     *
     * 없는 값을 `0` 이나 `false` 로 채우지 않는다 — `asInt()` 의 기본값을 쓰면 `slotId` 를
     * 빠뜨린 요청이 "0번 슬롯" 요청이 되어 400 대신 422 로 돌아온다. 빠진 것은 빠진 것으로
     * 넘겨 [ControlledStation.operate] 가 400 으로 되묻게 한다.
     */
    private fun paramsOf(body: JsonNode) = StationOpParams(
        slotId = body.path("slotId").takeIf { it.isNumber }?.asInt(),
        byPercent = body.path("byPercent").takeIf { it.isNumber }?.asDouble(),
        sameMessageId = body.path("sameMessageId").takeIf { it.isBoolean }?.asBoolean(),
    )

    private fun snapshotOf(snapshot: StationSnapshot): ObjectNode {
        val slots = array()
        snapshot.slots.forEach { slot ->
            val battery = slot.battery
            slots.add(
                node()
                    .put("slotId", slot.slotId)
                    .put("role", slot.role)
                    .put("chargingTransactionId", slot.chargingTransactionId)
                    .put("chargingSuspended", slot.chargingSuspended)
                    // 빈 슬롯은 빈 슬롯이다. 없는 배터리를 그럴듯하게 채우지 않는다.
                    .set<ObjectNode>(
                        "battery",
                        if (battery == null) {
                            JsonNodeFactory.instance.nullNode()
                        } else {
                            node()
                                .put("serialNumber", battery.serialNumber)
                                .put("soC", battery.soC)
                                .put("soH", battery.soH)
                        },
                    ),
            )
        }

        val events = array()
        snapshot.events.forEach { event ->
            events.add(
                node()
                    .put("seq", event.seq)
                    .put("direction", event.direction)
                    .put("action", event.action)
                    .put("messageId", event.messageId)
                    .put("occurredAt", event.occurredAt.toString()),
            )
        }

        return node()
            .put("stationId", snapshot.stationId)
            .put("csmsUrl", snapshot.csmsUrl)
            .put("connectUrl", snapshot.connectUrl)
            .put("connected", snapshot.connected)
            .put("swapOrder", snapshot.swapOrder)
            .put("idToken", snapshot.idToken.idToken)
            .put("idTokenType", snapshot.idToken.type)
            .put("progress", snapshot.progress.name)
            .put("fault", snapshot.fault?.name)
            .put("note", snapshot.note)
            .put("error", snapshot.error)
            .put("requestId", snapshot.requestId)
            .put("messageCount", snapshot.messageCount)
            .put("subprotocol", snapshot.subprotocol)
            .put("lastTransmitFailure", snapshot.lastTransmitFailure)
            .set<ObjectNode>("slots", slots)
            .set<ObjectNode>("events", events)
    }

    private fun node(): ObjectNode = JsonNodeFactory.instance.objectNode()

    private fun array(): ArrayNode = JsonNodeFactory.instance.arrayNode()

    /** 응답 한 건. JSON 이 기본이고, 화면만 HTML 로 나간다. */
    private class Reply(
        val status: Int,
        val body: ByteArray,
        val contentType: String,
        val headers: Map<String, String> = emptyMap(),
    ) {
        constructor(status: Int, json: JsonNode) : this(
            status,
            MAPPER.writeValueAsBytes(json),
            "application/json; charset=utf-8",
        )

        companion object {
            fun unauthorized() = Reply(
                401,
                MAPPER.writeValueAsBytes(JsonNodeFactory.instance.objectNode().put("error", "UNAUTHORIZED")),
                "application/json; charset=utf-8",
                mapOf("WWW-Authenticate" to "Basic realm=\"sim-console\", charset=\"UTF-8\""),
            )
        }
    }

    data class Credentials(val username: String, val password: String)

    companion object {
        /** CSMS 의 8080 과 부딪히지 않아야 한다. */
        const val DEFAULT_PORT = 8090

        const val DEFAULT_BIND_ADDRESS = "127.0.0.1"

        const val DEFAULT_CSMS_URL = "ws://localhost:8080/ocpp"

        private const val BACKLOG = 0
        private const val HTTP_THREADS = 4
        private const val DEFAULT_SLOTS = 4
        private const val DEFAULT_SET_SIZE = 2
        private const val DEFAULT_ID_TOKEN = "RFID-0001"
        private const val DEFAULT_ID_TOKEN_TYPE = "ISO14443"
        private const val DEFAULT_REQUEST_ID = 1001

        private val MAPPER = ObjectMapper()
    }
}
