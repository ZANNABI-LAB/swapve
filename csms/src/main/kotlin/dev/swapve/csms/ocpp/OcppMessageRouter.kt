package dev.swapve.csms.ocpp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.csms.auth.AuthorizationRegistry
import dev.swapve.csms.auth.AuthorizationStatus
import dev.swapve.csms.config.CsmsProperties
import dev.swapve.csms.station.StationRegistration
import dev.swapve.csms.station.StationRegistry
import dev.swapve.csms.ws.StationPrincipal
import dev.swapve.ocpp.rpc.RpcErrorCode
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.schema.PayloadValidation
import dev.swapve.ocpp.session.InboundResponse
import dev.swapve.ocpp.session.OcppCall
import dev.swapve.swap.IdToken
import dev.swapve.swap.OperatorId
import dev.swapve.swap.StationId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * 수신한 CALL 을 action 별 처리로 보낸다.
 *
 * ### 여기서 하지 않는 일
 *
 * 프레이밍도, 스키마 검증도, 멱등도, 스테이션 직렬화도 하지 않는다. **전부 M4 의
 * `OcppSession` 이 이미 했다.** 이 클래스가 불렸다는 것은 곧 프레임이 읽혔고, 페이로드가
 * 공식 `<Action>Request` 스키마를 통과했고, 같은 messageId 의 재전송이 아니며, 같은
 * 스테이션의 다른 메시지와 겹치지 않는다는 뜻이다.
 *
 * ### `stationId: String` 이 아니라 [StationPrincipal] 을 받는다
 *
 * PLAN §11.4. 신원이 **무엇으로** 확인됐는지가 값으로 함께 온다.
 *
 * ### 우리가 보내는 응답도 공식 스키마로 검증한다
 *
 * [respond] 가 만들어진 페이로드를 `<Action>Response` 스키마에 통과시킨다. 손으로 필드를
 * 짐작하다 틀리면 그 자리에서 터진다 — 스키마가 정본이라는 원칙(PLAN §6 설계원칙 2)이
 * 산문이 아니라 실행되는 검사가 된다.
 */
@Component
class OcppMessageRouter(
    private val stations: StationRegistry,
    private val authorizations: AuthorizationRegistry,
    private val validator: OcppPayloadValidator,
    private val properties: CsmsProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 스테이션이 보낸 CALL 하나를 처리한다.
     *
     * 모르는 action 은 [RpcErrorCode.NotImplemented] 로 답한다 (Part 4 §4.3). **억지로 만들지
     * 않는다** — 스키마조차 없는 action 은 애초에 여기까지 오지 못하고 M2 가 같은 코드로 돌려보낸다.
     */
    fun handle(principal: StationPrincipal, call: OcppCall): InboundResponse = when (call.action) {
        BOOT_NOTIFICATION -> respond(call.action, bootNotification(principal, call.payload))
        HEARTBEAT -> respond(call.action, heartbeat())
        AUTHORIZE -> authorize(principal, call.payload)

        else -> {
            log.info("미구현 action: station={} action={}", principal.stationId, call.action)
            InboundResponse.Fail(RpcErrorCode.NotImplemented, "action not implemented: ${call.action}")
        }
    }

    // ------------------------------------------------------------------ BootNotification

    /**
     * 부팅 통보 — 상태·현재시각·하트비트 간격으로 답하고 등록 정보를 남긴다.
     *
     * `status = Accepted` 로 고정한다. 등록 승인 절차(`Pending`)도 거부 정책(`Rejected`)도
     * 지금 없는데 있는 척하는 분기를 두면 그게 과설계다 (PLAN §11.0).
     */
    private fun bootNotification(principal: StationPrincipal, payload: ObjectNode): ObjectNode {
        val chargingStation = payload.path("chargingStation")
        val now = clock.instant()

        val registration = StationRegistration(
            stationId = StationId(principal.stationId),
            // 값이 항상 하나여도 둔다 (PLAN §11.3).
            operatorId = OperatorId(properties.operatorId),
            // 스키마가 필수로 둔 두 필드다. 없을 수 없지만, 없으면 빈 문자열로 두고 기록은 남긴다.
            vendorName = chargingStation.text("vendorName").orEmpty(),
            model = chargingStation.text("model").orEmpty(),
            serialNumber = chargingStation.text("serialNumber"),
            firmwareVersion = chargingStation.text("firmwareVersion"),
            bootReason = payload.text("reason").orEmpty(),
            authMethod = principal.authMethod,
            bootedAt = now,
        )
        stations.record(registration)

        log.info(
            "BootNotification: station={} vendor={} model={} reason={} auth={}",
            registration.stationId, registration.vendorName, registration.model,
            registration.bootReason, registration.authMethod,
        )

        return objectNode().apply {
            put("status", REGISTRATION_ACCEPTED)
            put("currentTime", OcppDateTime.format(now))
            put("interval", properties.heartbeatInterval.seconds.toInt())
        }
    }

    // ------------------------------------------------------------------ Heartbeat

    /** 하트비트 — 현재 시각만 답한다. `HeartbeatResponse` 의 필수 필드가 그것 하나다. */
    private fun heartbeat(): ObjectNode = objectNode().apply {
        put("currentTime", OcppDateTime.format(clock.instant()))
    }

    // ------------------------------------------------------------------ Authorize (S01)

    /**
     * S01 인가 (PLAN §4.4).
     *
     * **여기서 교환 트랜잭션을 열지 않는다.** S01 의 requestId 는 스테이션이 발번하고 나중에
     * `BatterySwapRequest` 가 실어 오므로, 이 시점의 CSMS 는 그것을 알 수 없다. 왜 그렇게
     * 판단했는지는 [AuthorizationRegistry] 의 KDoc 에 적어 두었다.
     *
     * **인식하지 못한 토큰도 기록한다** (PLAN §11.3). 로밍이 붙으면 그 목록이 외부 조회
     * 대상이 된다.
     */
    private fun authorize(principal: StationPrincipal, payload: ObjectNode): InboundResponse {
        val node = payload.path("idToken")
        val value = node.text("idToken")
        val type = node.text("type")

        // 스키마는 두 필드의 존재만 보장하고 공백 문자열은 막지 않는다. 값 객체가 거부하는
        // 입력이므로 여기서 프로토콜 오류로 돌려보낸다 — 예외로 터뜨려 InternalError 가
        // 되게 두면 원인이 우리 쪽에 있는 것처럼 보인다.
        if (value.isNullOrBlank() || type.isNullOrBlank()) {
            return InboundResponse.Fail(
                RpcErrorCode.PropertyConstraintViolation,
                "idToken.idToken 과 idToken.type 은 비어 있을 수 없다",
            )
        }

        val idToken = IdToken(value, type)
        val attempt = authorizations.authorize(principal, idToken, clock.instant())

        if (attempt.status != AuthorizationStatus.ACCEPTED) {
            // 거부도 기록으로 남았다. 로그는 그 사실을 운영자가 보게 할 뿐이다.
            log.info(
                "인가 거부: station={} idTokenType={} status={}",
                principal.stationId, idToken.type, attempt.status.wireValue,
            )
        }

        val response = objectNode().apply {
            putObject("idTokenInfo").put("status", attempt.status.wireValue)
        }
        return respond(AUTHORIZE, response)
    }

    // ------------------------------------------------------------------ 공통

    /**
     * 응답을 공식 `<Action>Response` 스키마로 **자기 검증**한 뒤 돌려준다.
     *
     * 실패하면 예외를 던진다. 그러면 M4 가 멱등 기록을 지우고
     * [RpcErrorCode.InternalError] CALLERROR 를 회신한다 — 연결은 살아 있고, 우리 버그라는
     * 사실은 로그와 오류 코드에 정확히 남는다. 조용히 잘못된 페이로드를 내보내는 것보다 낫다.
     */
    private fun respond(action: String, payload: ObjectNode): InboundResponse {
        val validation = validator.validateCallResult(action, payload)
        if (validation is PayloadValidation.Invalid) {
            error("우리가 만든 ${action}Response 가 공식 스키마를 통과하지 못했다: ${validation.errorDescription}")
        }
        return InboundResponse.Respond(payload)
    }

    private fun objectNode(): ObjectNode = JsonNodeFactory.instance.objectNode()

    /** 있으면 문자열, 없거나 문자열이 아니면 `null`. */
    private fun JsonNode.text(field: String): String? = path(field).takeIf { it.isTextual }?.asText()

    private companion object {
        const val BOOT_NOTIFICATION = "BootNotification"
        const val HEARTBEAT = "Heartbeat"
        const val AUTHORIZE = "Authorize"

        /** `RegistrationStatusEnumType` (BootNotificationResponse 스키마). */
        const val REGISTRATION_ACCEPTED = "Accepted"
    }
}
