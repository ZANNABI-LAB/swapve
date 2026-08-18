package dev.swapve.csms.ocpp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.csms.auth.AuthorizationRegistry
import dev.swapve.csms.auth.AuthorizationStatus
import dev.swapve.csms.config.CsmsProperties
import dev.swapve.csms.station.StationRegistration
import dev.swapve.csms.station.StationRegistry
import dev.swapve.csms.swap.ChargingEvent
import dev.swapve.csms.swap.ChargingTransactionRegistry
import dev.swapve.csms.swap.SlotStateRegistry
import dev.swapve.csms.swap.SwapCoordinator
import dev.swapve.csms.ws.StationPrincipal
import dev.swapve.ocpp.json.OcppDateTime
import dev.swapve.ocpp.rpc.RpcErrorCode
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.schema.PayloadValidation
import dev.swapve.ocpp.session.InboundResponse
import dev.swapve.ocpp.session.OcppCall
import dev.swapve.ocpp.swap.AvailabilityState
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.swap.IdToken
import dev.swapve.swap.OperatorId
import dev.swapve.swap.SlotId
import dev.swapve.swap.SlotState
import dev.swapve.swap.StationId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

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
    private val swaps: SwapCoordinator,
    private val slotStates: SlotStateRegistry,
    private val chargingTransactions: ChargingTransactionRegistry,
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
        BatterySwapWire.BOOT_NOTIFICATION -> respond(call.action, bootNotification(principal, call.payload))
        BatterySwapWire.HEARTBEAT -> respond(call.action, heartbeat())
        BatterySwapWire.AUTHORIZE -> authorize(principal, call.payload)
        BatterySwapWire.NOTIFY_EVENT -> notifyEvent(principal, call.payload)
        BatterySwapWire.SECURITY_EVENT_NOTIFICATION -> securityEventNotification(principal, call.payload)
        BatterySwapWire.TRANSACTION_EVENT -> transactionEvent(principal, call.payload)
        BatterySwapWire.BATTERY_SWAP -> batterySwap(principal, call.payload)

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
            put("status", BatterySwapWire.REGISTRATION_ACCEPTED)
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
        return respond(BatterySwapWire.AUTHORIZE, response)
    }

    // ------------------------------------------------------------------ NotifyEvent (S03.FR.02/04)

    /**
     * 슬롯 점유 상태 갱신.
     *
     * ### 여기서 프로토콜 어휘를 도메인 어휘로 바꾼다 (PLAN §6 원칙 4)
     *
     * `Available`/`Occupied` 는 **직관과 반대다** (PLAN §4.2). 그 반전 지식은 `ocpp-core` 의
     * [AvailabilityState] 한 곳에만 있고, 이 함수는 그것을 지나 [SlotState] 로 옮길 뿐이다 —
     * 코드 어디에도 `"Occupied"` 라는 리터럴이 없다.
     *
     * `StatusNotificationRequest` 가 아니라 이 메시지를 받는 이유는 전자가 2.1 에서
     * **deprecated** 이기 때문이다 (Part 2 S03 Remark, PLAN §4.5).
     *
     * 슬롯 상태와 무관한 `NotifyEvent` 도 온다 (다른 컴포넌트·변수). **거르되 버리지 않는다** —
     * 원문은 이벤트 로그에 그대로 남는다 (PLAN §11.1).
     */
    private fun notifyEvent(principal: StationPrincipal, payload: ObjectNode): InboundResponse {
        val stationId = StationId(principal.stationId)

        payload.path("eventData").forEach { event ->
            val component = event.path("component")
            val variable = event.path("variable")
            if (component.text("name") != BatterySwapWire.COMPONENT_CONNECTOR) return@forEach
            if (variable.text("name") != BatterySwapWire.VARIABLE_AVAILABILITY_STATE) return@forEach

            val evseId = component.path("evse").path("id").takeIf { it.isInt }?.asInt() ?: return@forEach
            val actualValue = event.text("actualValue") ?: return@forEach
            val holdsBattery = AvailabilityState.holdsBattery(actualValue)

            if (holdsBattery == null) {
                // `Unavailable` 이거나 우리가 모르는 값이다. 배터리 유무를 아는 척하지 않는다.
                log.info(
                    "슬롯 가용성을 배터리 유무로 해석할 수 없다: station={} evse={} value={}",
                    principal.stationId, evseId, actualValue,
                )
                return@forEach
            }

            slotStates.observe(
                stationId = stationId,
                slotId = SlotId(evseId),
                state = if (holdsBattery) SlotState.HOLDS_BATTERY else SlotState.EMPTY,
                at = event.instant("timestamp"),
            )
        }

        return respond(BatterySwapWire.NOTIFY_EVENT, objectNode())
    }

    // ------------------------------------------------------------------ SecurityEventNotification

    /**
     * 보안 이벤트 (Part 6 `BootedBatterySwapping` 5·6단계).
     *
     * 응답에 실을 필드가 없다. 판단도 하지 않는다 — 스테이션이 알려 온 사실은 이미
     * 이벤트 로그에 원문으로 남았고 (PLAN §11.1), 그것을 해석하는 `SecurityCtrlr` 구현은
     * 범위 밖이다 (PLAN §11.4 "지금 하지 않을 것").
     */
    private fun securityEventNotification(principal: StationPrincipal, payload: ObjectNode): InboundResponse {
        log.info(
            "보안 이벤트: station={} type={} at={}",
            principal.stationId, payload.text("type"), payload.text("timestamp"),
        )
        return respond(BatterySwapWire.SECURITY_EVENT_NOTIFICATION, objectNode())
    }

    // ------------------------------------------------------------------ TransactionEvent (S04)

    /**
     * 충전 트랜잭션 — **기록만 한다** (PLAN §4.10 MVP 범위, §10 결정 #8).
     *
     * ### ★ 교환 트랜잭션과 절대 합치지 않는다 (PLAN §5.1)
     *
     * 다른 저장소, 다른 키다. 들어온 배터리의 충전은 교환이 끝난 뒤에도 며칠 계속되므로,
     * 둘을 한 객체로 묶으면 그 배터리가 언제까지 어느 교환에 매여 있는지가 거짓이 된다.
     *
     * 응답에 `idTokenInfo.status = Accepted` 를 싣는다. 요청에 `idToken` 이 실려 있을 때
     * 적합성 시험이 그것을 확인하기 때문이다 (PLAN §7.1 `TC_S_103_CSMS` step 6/10/14/18).
     * `NoAuthorization` 은 애초에 인가가 아니므로 대상이 아니다.
     */
    private fun transactionEvent(principal: StationPrincipal, payload: ObjectNode): InboundResponse {
        val stationId = StationId(principal.stationId)
        val transactionInfo = payload.path("transactionInfo")
        val transactionId = transactionInfo.text("transactionId")

        if (transactionId.isNullOrBlank()) {
            return InboundResponse.Fail(
                RpcErrorCode.PropertyConstraintViolation,
                "transactionInfo.transactionId 는 비어 있을 수 없다",
            )
        }

        val idTokenNode = payload.path("idToken")
        val idTokenValue = idTokenNode.text("idToken")
        val idTokenType = idTokenNode.text("type")
        // 인가 없는 트랜잭션은 `type=NoAuthorization` 에 **빈 문자열**로 온다 (Part 6 Tool
        // validation). 값 객체로 만들 수 없고, 만들어서도 안 된다 — 없는 인가를 있는 것처럼
        // 적는 셈이기 때문이다.
        val idToken = if (!idTokenValue.isNullOrBlank() && !idTokenType.isNullOrBlank()) {
            IdToken(idTokenValue, idTokenType)
        } else {
            null
        }

        chargingTransactions.record(
            stationId = stationId,
            transactionId = transactionId,
            event = ChargingEvent(
                eventType = payload.text("eventType").orEmpty(),
                triggerReason = payload.text("triggerReason").orEmpty(),
                seqNo = payload.path("seqNo").asInt(),
                slotId = payload.path("evse").path("id").takeIf { it.isInt }?.let { SlotId(it.asInt()) },
                chargingState = transactionInfo.text("chargingState"),
                stoppedReason = transactionInfo.text("stoppedReason"),
                idToken = idToken,
                at = payload.instant("timestamp"),
            ),
        )

        val response = objectNode().apply {
            if (idToken != null) {
                putObject("idTokenInfo").put("status", BatterySwapWire.AUTHORIZATION_ACCEPTED)
            }
        }
        return respond(BatterySwapWire.TRANSACTION_EVENT, response)
    }

    // ------------------------------------------------------------------ BatterySwap (S03)

    /**
     * 교환 사건 — **빈 응답으로 확인한다.**
     *
     * *"Empty response by CSMS to confirm receipt"* (PLAN §4.3). **거부할 수 없다.**
     * 인가가 없어도(F5), 중복이어도(F4) 응답은 정상 회신이고 사실만 기록에 남는다
     * (PLAN §5.4 — *"모든 위반은 CALLRESULT 로 정상 응답한다"*).
     *
     * `customData` 로 배터리를 거부하는 확장(PLAN §4.8)은 **M7** 이다. 지금 만들지 않는다.
     *
     * 상태 진행은 [SwapCoordinator] 가 M3 상태머신에 위임한다 — 여기서 다시 구현하지 않는다.
     */
    private fun batterySwap(principal: StationPrincipal, payload: ObjectNode): InboundResponse {
        val idTokenNode = payload.path("idToken")
        if (idTokenNode.text("idToken").isNullOrBlank() || idTokenNode.text("type").isNullOrBlank()) {
            // 도메인 값 객체가 거부하는 입력이다. 교환을 거부하는 것이 아니라 프레임 자체가
            // 성립하지 않는다고 답하는 것이라, 빈 응답 규칙과 충돌하지 않는다 (Part 4 §4.3).
            return InboundResponse.Fail(
                RpcErrorCode.PropertyConstraintViolation,
                "idToken.idToken 과 idToken.type 은 비어 있을 수 없다",
            )
        }

        val state = swaps.onBatterySwap(StationId(principal.stationId), payload)
        log.info(
            "교환 사건: station={} eventType={} requestId={} → {}",
            principal.stationId,
            payload.text("eventType"),
            payload.path("requestId").asInt(),
            state::class.simpleName,
        )

        return respond(BatterySwapWire.BATTERY_SWAP, objectNode())
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

    /**
     * 스테이션이 적어 보낸 시각. 읽을 수 없으면 **우리 시계로 대신한다.**
     *
     * 원문은 이벤트 로그에 그대로 남으므로 (PLAN §11.1) 정보가 사라지지 않는다. 읽히지 않는
     * 시각 하나 때문에 메시지 전체를 실패로 만드는 쪽이 손해다.
     */
    private fun JsonNode.instant(field: String): Instant =
        text(field)?.let(OcppDateTime::parse) ?: clock.instant()
}
