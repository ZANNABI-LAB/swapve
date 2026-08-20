package dev.swapve.csms.config

import dev.swapve.csms.ws.OcppHandshakeInterceptor
import dev.swapve.csms.ws.OcppWebSocketHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.support.DefaultHandshakeHandler
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean

/**
 * OCPP-J WebSocket 엔드포인트 등록 (Part 4 Edition 2 §3).
 *
 * ### 경로 (§3.1.1)
 *
 * `{endpointPath}/{stationId}` 로 받는다. 마지막 세그먼트가 스테이션 식별자라서 와일드카드
 * 패턴으로 등록한다. 그 값을 꺼내고 검증하는 일은 [OcppHandshakeInterceptor] 가 한다.
 *
 * ### 서브프로토콜 (§3.1.2, §3.3)
 *
 * 우리가 지원하는 것은 `ocpp2.1` 하나다. [DefaultHandshakeHandler] 가 클라이언트가 제시한
 * 목록과 대조해 **하나를 골라** 101 응답의 `Sec-WebSocket-Protocol` 에 싣는다.
 * 클라이언트가 `ocpp2.1` 을 제시하지 않은 경우의 **거절**은 인터셉터가 한다 — 여기서는
 * 겹치는 것이 없으면 헤더 없이 101 이 나가 버리기 때문이다.
 *
 * ### ★ RFC 7692 압축 (§3.4) — 적합성 항목이므로 결론을 남긴다
 *
 * 스펙은 **CSMS 가 permessage-deflate 를 지원해야 한다(SHALL)** 고 요구한다(충전소는 선택).
 *
 * **결론: 켜져 있다.** 추측이 아니라 관측이다 — `WebSocketHandshakeTest` 의
 * `permessage-deflate 협상 결과를 관측한다` 가 101 응답의 `Sec-WebSocket-Extensions` 헤더를
 * 직접 읽고, 실제로 `permessage-deflate;client_max_window_bits=15` 가 돌아온다.
 *
 * 배선은 이렇다. Spring 의 `AbstractHandshakeHandler` 가 클라이언트의 확장 요청을 컨테이너의
 * 설치된 확장 목록과 대조해 통과시키고, 내장 Tomcat 이 `permessage-deflate` 를 서버 측
 * 확장으로 구현하고 있다. **우리가 코드로 켜는 자리는 없고, 켤 필요도 없다.**
 *
 * 언젠가 꺼진 것이 관측된다면 손댈 곳도 여기가 아니다. 확장 협상의 주체가 서블릿
 * 컨테이너이므로 (1) 내장 컨테이너를 바꾸거나 (2) 앞단 리버스 프록시에서 처리하거나
 * 둘 중 하나다. 그때 이 시험이 먼저 빨갛게 된다.
 */
@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val handler: OcppWebSocketHandler,
    private val handshakeInterceptor: OcppHandshakeInterceptor,
    private val properties: CsmsProperties,
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry
            .addHandler(handler, properties.endpointPath.trimEnd('/') + "/*")
            .addInterceptors(handshakeInterceptor)
            .setHandshakeHandler(ocppHandshakeHandler())
            // 스테이션은 브라우저가 아니라서 Origin 을 보내지 않는다. 보내더라도 그것으로
            // 신원을 판단하지 않는다 — 신원은 StationPrincipal 하나로만 정해진다.
            .setAllowedOriginPatterns("*")
    }

    private fun ocppHandshakeHandler() = DefaultHandshakeHandler().apply {
        setSupportedProtocols(OcppHandshakeInterceptor.OCPP_SUBPROTOCOL)
    }

    /**
     * 텍스트 프레임 버퍼 상한.
     *
     * 기본값(8 KiB)은 OCPP 에 좁다 — `GetCompositeSchedule` 이나 배터리 여러 개를 실은
     * `BatterySwap` 이 넘길 수 있고, 넘치면 프레임이 아니라 **연결이** 끊긴다.
     */
    @Bean
    fun servletServerContainerFactoryBean() = ServletServerContainerFactoryBean().apply {
        setMaxTextMessageBufferSize(properties.maxTextMessageSize)
        setMaxBinaryMessageBufferSize(properties.maxTextMessageSize)
    }
}
