package dev.swapve.csms.ws

import dev.swapve.csms.config.CsmsProperties
import dev.swapve.csms.config.CsmsProperties.SecurityProfile
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor

/**
 * 핸드셰이크 관문 — 서브프로토콜 협상과 스테이션 식별자 검증 (Part 4 Edition 2 §3.1).
 *
 * ### `ocpp2.1` 을 제시하지 않으면 거절한다 (§3.1.2)
 *
 * 버전은 **오직 `Sec-WebSocket-Protocol` 협상으로만** 정해진다. URL 에 버전 문자열이 들어
 * 있어도 그것이 버전을 정하지 않는다. 클라이언트는 선호 순으로 여러 개를 보낼 수 있고,
 * 서버는 그중 **하나**를 골라 101 에 실어 답한다 (§3.3) — 고르는 일은
 * `DefaultHandshakeHandler` 가 한다 ([dev.swapve.csms.config.WebSocketConfig] 참조).
 *
 * 여기서 명시적으로 거절해야 하는 이유는, Spring 의 기본 동작이 **겹치는 서브프로토콜이
 * 없으면 서브프로토콜 헤더 없이 101 을 내주는 것**이기 때문이다. 그러면 스펙이 요구하는
 * 협상이 사실상 없는 연결이 열린다.
 *
 * ### 식별자 검증은 [StationIdentity] 가 한다
 *
 * 여기서는 결과를 HTTP 상태로 옮길 뿐이다. 판정 자체는 순수 함수라 서버 없이 시험된다.
 *
 * 통과하면 [StationPrincipal] 을 세션 속성에 넣는다. **핸들러는 경로를 다시 파싱하지 않는다** —
 * 신원이 정해지는 곳은 핸드셰이크 한 곳뿐이어야 한다 (PLAN §11.4).
 */
@Component
class OcppHandshakeInterceptor(private val properties: CsmsProperties) : HandshakeInterceptor {

    private val log = LoggerFactory.getLogger(javaClass)
    private val basicAuthenticator = BasicAuthenticator(properties.security.stations)

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        if (!offersOcppSubprotocol(request)) {
            log.warn("서브프로토콜 협상 실패 — {} 를 제시하지 않았다: {}", OCPP_SUBPROTOCOL, request.uri)
            return reject(response)
        }

        return when (val outcome = StationIdentity.fromRawPath(request.uri.rawPath, properties.endpointPath)) {
            is StationIdentity.Outcome.Rejected -> {
                log.warn("스테이션 식별자 거절: {}", outcome.reason)
                reject(response)
            }

            is StationIdentity.Outcome.Identified -> {
                val principal = when (properties.security.profile) {
                    SecurityProfile.NONE -> outcome.principal
                    SecurityProfile.BASIC -> {
                        val result = basicAuthenticator.authenticate(
                            outcome.principal.stationId,
                            request.headers.getFirst(HttpHeaders.AUTHORIZATION),
                        )
                        if (!result.authenticated) {
                            log.warn("Basic 인증 실패: station={} reason={}", outcome.principal.stationId, result.reason)
                            response.headers.add(
                                HttpHeaders.WWW_AUTHENTICATE,
                                "Basic realm=\"ocpp\", charset=\"UTF-8\"",
                            )
                            return reject(response, HttpStatus.UNAUTHORIZED)
                        }
                        outcome.principal.copy(authMethod = AuthMethod.BASIC)
                    }
                }
                attributes[STATION_PRINCIPAL_ATTRIBUTE] = principal
                true
            }
        }
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) = Unit

    /**
     * 클라이언트가 제시한 서브프로토콜 중에 `ocpp2.1` 이 있는가.
     *
     * 헤더는 여러 줄로 오기도 하고 한 줄에 쉼표로 오기도 한다. 둘 다 같은 뜻이다.
     * 서브프로토콜 이름 비교는 대소문자를 가리지 않게 둔다 — 스펙이 소문자로 정의하지만,
     * 대문자로 보내는 구현 하나 때문에 연결을 거절하는 것은 얻는 것 없이 잃기만 한다.
     */
    private fun offersOcppSubprotocol(request: ServerHttpRequest): Boolean =
        request.headers[SEC_WEBSOCKET_PROTOCOL]
            .orEmpty()
            .flatMap { it.split(',') }
            .any { it.trim().equals(OCPP_SUBPROTOCOL, ignoreCase = true) }

    /**
     * 거절 — 기본은 400 으로 답하고 업그레이드를 진행하지 않는다. 인증 실패만 401 을 쓴다.
     *
     * 사유를 본문이나 헤더에 싣지 않는다. 붙지 못한 상대에게 우리 내부 판정 기준을 알려 줄
     * 이유가 없다. 사유는 서버 로그에 남는다.
     */
    private fun reject(response: ServerHttpResponse, status: HttpStatus = HttpStatus.BAD_REQUEST): Boolean {
        response.setStatusCode(status)
        return false
    }

    companion object {

        /** OCPP 2.1 의 WebSocket 서브프로토콜 이름 (Part 4 Edition 2 §3.1.2). */
        const val OCPP_SUBPROTOCOL = "ocpp2.1"

        /** 핸드셰이크에서 정해진 [StationPrincipal] 이 담기는 세션 속성 키. */
        const val STATION_PRINCIPAL_ATTRIBUTE = "swapve.stationPrincipal"

        private const val SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol"
    }
}
