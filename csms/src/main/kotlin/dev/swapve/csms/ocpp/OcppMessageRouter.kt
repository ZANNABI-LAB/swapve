package dev.swapve.csms.ocpp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.csms.auth.AuthorizationRegistry
import dev.swapve.csms.auth.AuthorizationStatus
import dev.swapve.csms.config.CsmsProperties
import dev.swapve.csms.devicemodel.DeviceModelReportRegistry
import dev.swapve.csms.devicemodel.ReportedVariable
import dev.swapve.csms.station.StationRegistration
import dev.swapve.csms.station.StationRegistry
import dev.swapve.csms.swap.BatteryRegistry
import dev.swapve.csms.swap.BatteryRejection
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
import dev.swapve.ocpp.swap.VariableRef
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
 * 신원이 **무엇으로** 확인됐는지가 값으로 함께 온다.
 *
 * ### 우리가 보내는 응답도 공식 스키마로 검증한다
 *
 * [respond] 가 만들어진 페이로드를 `<Action>Response` 스키마에 통과시킨다. 손으로 필드를
 * 짐작하다 틀리면 그 자리에서 터진다 — 스키마가 정본이라는 원칙(설계원칙 2)이
 * 산문이 아니라 실행되는 검사가 된다.
 */
@Component
class OcppMessageRouter(
    private val stations: StationRegistry,
    private val authorizations: AuthorizationRegistry,
    private val swaps: SwapCoordinator,
    private val batteries: BatteryRegistry,
    private val slotStates: SlotStateRegistry,
    private val chargingTransactions: ChargingTransactionRegistry,
    private val reports: DeviceModelReportRegistry,
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
        BatterySwapWire.NOTIFY_REPORT -> notifyReport(principal, call.payload)

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
     * 지금 없는데 있는 척하는 분기를 두면 그게 과설계다.
     */
    private fun bootNotification(principal: StationPrincipal, payload: ObjectNode): ObjectNode {
        val chargingStation = payload.path("chargingStation")
        val now = clock.instant()

        val registration = StationRegistration(
            stationId = StationId(principal.stationId),
            // 값이 항상 하나여도 둔다.
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
     * S01 인가.
     *
     * **여기서 교환 트랜잭션을 열지 않는다.** S01 의 requestId 는 스테이션이 발번하고 나중에
     * `BatterySwapRequest` 가 실어 오므로, 이 시점의 CSMS 는 그것을 알 수 없다. 왜 그렇게
     * 판단했는지는 [AuthorizationRegistry] 의 KDoc 에 적어 두었다.
     *
     * **인식하지 못한 토큰도 기록한다**. 로밍이 붙으면 그 목록이 외부 조회
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
     * ### 여기서 프로토콜 어휘를 도메인 어휘로 바꾼다 (원칙 4)
     *
     * `Available`/`Occupied` 는 **직관과 반대다**. 그 반전 지식은 `ocpp-core` 의
     * [AvailabilityState] 한 곳에만 있고, 이 함수는 그것을 지나 [SlotState] 로 옮길 뿐이다 —
     * 코드 어디에도 `"Occupied"` 라는 리터럴이 없다.
     *
     * `StatusNotificationRequest` 가 아니라 이 메시지를 받는 이유는 전자가 2.1 에서
     * **deprecated** 이기 때문이다 (Part 2 S03 Remark).
     *
     * 슬롯 상태와 무관한 `NotifyEvent` 도 온다 (다른 컴포넌트·변수). **거르되 버리지 않는다** —
     * 원문은 이벤트 로그에 그대로 남는다.
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
     * 이벤트 로그에 원문으로 남았고, 그것을 해석하는 `SecurityCtrlr` 구현은
     * 범위 밖이다 ("지금 하지 않을 것").
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
     * 충전 트랜잭션 — **기록만 한다** (MVP 범위, 확정 결정 결정 #8).
     *
     * ### ★ 교환 트랜잭션과 절대 합치지 않는다
     *
     * 다른 저장소, 다른 키다. 들어온 배터리의 충전은 교환이 끝난 뒤에도 며칠 계속되므로,
     * 둘을 한 객체로 묶으면 그 배터리가 언제까지 어느 교환에 매여 있는지가 거짓이 된다.
     *
     * ### ★ 무인가 요청에도 `idTokenInfo.status = Accepted` 를 싣는다 (M7 정정)
     *
     * M6 은 *"`NoAuthorization` 은 애초에 인가가 아니므로 대상이 아니다"* 라고 판단해
     * `idToken` 이 있을 때만 실었다. **그 판단이 틀렸다.**
     *
     * `TC_S_103_CSMS` step 5/9/13/17 의 요청은 전부 `idToken.type = NoAuthorization` 에
     * `idToken.idToken = ""` 이다. 인가 대상이 아니다. 그런데 같은 케이스의 Tool validation 은
     * **step 6/10/14/18 응답의 `idTokenInfo.status` 가 `Accepted`** 이길 요구한다
     * (Part 6 p.1366–1369, 적합성 케이스의 함정). "토큰이 없으니 생략한다"가 자연스러운 구현이고,
     * 그러면 적합성에 떨어진다.
     *
     * `TransactionEventResponse` 스키마에는 top-level `required` 가 없어 `idTokenInfo` 는
     * **선택 필드**다. 즉 **스키마 검증만으로는 절대 잡히지 않는다** — 적합성 시험이 유일한
     * 검출 수단이라 `TcS103CsmsTest` 가 이 필드를 명시적으로 단언한다.
     *
     * 응답에 싣는 것과 **기록하는 것은 다르다.** [ChargingEvent.idToken] 에는 여전히 `null` 을
     * 남긴다 — 없는 인가를 있는 것처럼 적으면 그게 조용히 훼손된 장부다.
     *
     * ### SoC 주기 보고도 여기로 온다 (M9, S04.FR.04)
     *
     * 충전 중에는 `eventType = Updated` 에 `meterValue` 가 실려 온다. 그 값이
     * [ChargingEvent.socPercent] 로 남아 트랜잭션의 SoC 자취가 된다 ([socPercent] 참조).
     *
     * ### 시작을 본 적 없는 트랜잭션, 끝나지 않은 트랜잭션 둘 다 정상이다
     *
     * 앞의 것은 `TC_S_103_CSMS` 의 함정 4 이고, 뒤의 것은 **S04.FR.11** 이다 — 스테이션이
     * 재부팅하면 진행 중이던 트랜잭션은 종료 통보 없이 사라지고 `Started` 가 무더기로 온다.
     * 이 함수는 어느 쪽도 특별 취급하지 않는다. 처음 보는 `transactionId` 면 그냥 새로
     * 생기고, 옛 것은 끝나지 않은 채 남는다 — 그 자체가 재부팅의 기록이다.
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
                socPercent = payload.socPercent(),
            ),
        )

        // 요청에 토큰이 있든 없든 싣는다. 이유는 위 KDoc — Tool validation 이 요구하고,
        // 스키마는 선택 필드라 잡아 주지 않는다.
        val response = objectNode().apply {
            putObject("idTokenInfo").put("status", BatterySwapWire.AUTHORIZATION_ACCEPTED)
        }
        return respond(BatterySwapWire.TRANSACTION_EVENT, response)
    }

    // ------------------------------------------------------------------ BatterySwap (S03)

    /**
     * 교환 사건 — **빈 응답으로 확인한다.**
     *
     * *"Empty response by CSMS to confirm receipt"*. **거부할 수 없다.**
     * 인가가 없어도(F5), 중복이어도(F4) 응답은 정상 회신이고 사실만 기록에 남는다
     * (*"모든 위반은 CALLRESULT 로 정상 응답한다"*).
     *
     * ### F3 — 미등록 배터리는 `customData` 로 거부한다 (M7)
     *
     * 거부하더라도 **응답의 성격은 수신 확인 그대로**다. `CALLERROR` 로 답하지 않고,
     * 상태 진행도 멈추지 않는다 — 그 배터리는 실제로 스테이션 안에 들어와 있고, 그 사실을
     * 기록하지 않으면 장부가 현실과 어긋난다. 우리가 알리는 것은 *"받았지만 우리 것이
     * 아니다"* 이지 *"없던 일로 하자"* 가 아니다.
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

        linkBatteriesToCharging(StationId(principal.stationId), payload)

        val serials = payload.path("batteryData").mapNotNull { it.text("serialNumber") }
        val rejection = batteries.rejectionFor(serials)
        if (rejection != null) {
            log.warn(
                "미등록 배터리: station={} serials={}",
                principal.stationId, rejection.unknownSerials,
            )
        }

        return respond(BatterySwapWire.BATTERY_SWAP, batterySwapResponse(rejection))
    }

    /**
     * 들어온 배터리의 **신원**을 그 슬롯의 충전 트랜잭션에 붙인다 (S04).
     *
     * ### 두 생명주기를 합치는 것이 아니다
     *
     * 붙이는 것은 `(슬롯, 일련번호)` 라는 사실 하나뿐이고, 충전 트랜잭션은 교환을 참조하지
     * 않는다. 그래서 교환이 `Completed` 가 된 뒤에도 — 며칠 뒤까지도 — 그 슬롯의 충전은
     * 그대로 살아 있다. **여기가 생명주기 분리 이 요구하는 분리가 실제로 성립하는 자리다.**
     *
     * ### 입고(`BatteryIn`)에서만 붙인다
     *
     * 반출되는 배터리는 이미 트랜잭션이 `Ended` 로 닫힌 뒤에 `BatteryOut` 이 온다
     * (`TC_S_103_CSMS` step 13 → 21). 닫힌 트랜잭션에 뒤늦게 신원을 붙이는 것은
     * 기록을 고치는 일이라 하지 않는다 — 그 배터리가 무엇이었는지는 **교환 기록**에
     * `serialNumber`·`soC`·`soH` 로 온전히 남아 있다.
     *
     * 부팅 시점부터 꽂혀 있던 배터리는 붙일 근거가 없다. `TransactionEvent` 도 `NotifyEvent` 도
     * 일련번호를 싣지 않기 때문이다. 그때 CSMS 가 아는 것은 슬롯뿐이고, 알고 싶으면
     * `GetVariables` 로 물어야 한다.
     */
    private fun linkBatteriesToCharging(stationId: StationId, payload: ObjectNode) {
        if (payload.text("eventType") != BatterySwapWire.BATTERY_IN) return

        payload.path("batteryData").forEach { entry ->
            val slotId = entry.path("evseId").takeIf { it.isInt }?.asInt() ?: return@forEach
            val serialNumber = entry.text("serialNumber") ?: return@forEach
            chargingTransactions.linkBattery(stationId, SlotId(slotId), serialNumber)
        }
    }

    /**
     * `BatterySwapResponse` — 빈 응답, 또는 거부를 실은 `customData`.
     *
     * OCA 가 정한 공식 우회다 (Part 2 S03 Error handling):
     *
     * ```jsonc
     * "customData": {
     *   "vendorId": "org.openchargealliance.batteryswapresponse",
     *   "status": "Rejected",
     *   "statusInfo": { "reasonCode": "BatteryUnknown", "additionalInfo": "…" }
     * }
     * ```
     *
     * 공식 스키마의 `CustomDataType` 에는 `additionalProperties: false` 가 **없다** — OCA 가
     * *"so it can be extended with arbitrary JSON properties"* 라고 명시했다. 그래서 이
     * customData 를 붙여도 응답이 공식 스키마를 통과한다. [respond] 가 그 사실을 매번
     * 실행되는 검사로 확인한다.
     */
    private fun batterySwapResponse(rejection: BatteryRejection?): ObjectNode = objectNode().apply {
        if (rejection == null) return@apply
        putObject("customData").apply {
            put("vendorId", BatterySwapWire.VENDOR_ID_BATTERY_SWAP_RESPONSE)
            put("status", BatterySwapWire.GENERIC_REJECTED)
            putObject("statusInfo").apply {
                put("reasonCode", rejection.reason.wireValue)
                put("additionalInfo", rejection.additionalInfo)
            }
        }
    }

    // ------------------------------------------------------------------ NotifyReport (B03)

    /**
     * 디바이스 모델 보고 한 조각 (`TC_S_104_CS`).
     *
     * ### 여기서 하는 일은 **읽어서 넘기는 것**뿐이다
     *
     * 조각을 잇는 규칙(`seqNo` 연속·`tbc` 종결·유실 표시)은 전부
     * [DeviceModelReportRegistry] 에 있다. 이 함수가 그것을 조금이라도 흉내 내면 규칙이
     * 두 곳에 생기고, 그중 하나만 고치는 날이 온다.
     *
     * ### `Actual` 속성만 값으로 읽는다
     *
     * 한 변수에 `Target`/`MinSet`/`MaxSet` 이 함께 실릴 수 있다 (스키마 `maxItems: 4`).
     * 배열의 첫 항목을 값으로 삼으면 **목표치를 현재치로 기록**하게 되므로, 종류를 보고
     * 고른다. 속성이 하나뿐이고 `type` 이 생략됐으면 그것이 `Actual` 이다 — 스키마의
     * 기본값이 그렇다.
     *
     * ### 응답은 빈 객체다
     *
     * `NotifyReportResponse` 에는 실을 필드가 없다. 보고를 거부할 자리도 없다 —
     * 스테이션이 이미 보내 버린 사실이기 때문이다.
     */
    private fun notifyReport(principal: StationPrincipal, payload: ObjectNode): InboundResponse {
        val variables = payload.path("reportData").mapNotNull { entry ->
            val ref = VariableRef.read(entry) ?: return@mapNotNull null
            val attribute = entry.path("variableAttribute").firstOrNull { attr ->
                val type = attr.text("type")
                type == null || type == BatterySwapWire.ATTRIBUTE_ACTUAL
            }
            val characteristics = entry.path("variableCharacteristics")

            ReportedVariable(
                ref = ref,
                value = attribute?.text("value"),
                mutability = attribute?.text("mutability"),
                dataType = characteristics.text("dataType"),
                unit = characteristics.text("unit"),
            )
        }

        reports.record(
            stationId = StationId(principal.stationId),
            requestId = payload.path("requestId").asInt(),
            seqNo = payload.path("seqNo").asInt(),
            // 생략되면 거짓이다 — "여기서 끝"이라는 뜻 (공식 스키마 기본값).
            tbc = payload.path("tbc").asBoolean(false),
            generatedAt = payload.instant("generatedAt"),
            variables = variables,
        )

        return respond(BatterySwapWire.NOTIFY_REPORT, objectNode())
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
     * 원문은 이벤트 로그에 그대로 남으므로 정보가 사라지지 않는다. 읽히지 않는
     * 시각 하나 때문에 메시지 전체를 실패로 만드는 쪽이 손해다.
     */
    private fun JsonNode.instant(field: String): Instant =
        text(field)?.let(OcppDateTime::parse) ?: clock.instant()

    /**
     * `meterValue` 에서 배터리 SoC 를 꺼낸다 (S04.FR.04).
     *
     * **`measurand` 가 명시적으로 `SoC` 인 표본만** 읽는다. 그 필드는 선택이고 생략하면
     * 기본값이 에너지(Wh)이므로 (공식 스키마), 아무 표본이나 SoC 로 읽으면 전력량을
     * 충전율로 기록하게 된다. 여러 개면 마지막 값이다 — 같은 시각의 표본 묶음에서 나중에
     * 적힌 것이 더 최신이다.
     */
    private fun ObjectNode.socPercent(): Double? =
        path("meterValue")
            .flatMap { it.path("sampledValue") }
            .lastOrNull { it.text("measurand") == BatterySwapWire.MEASURAND_SOC }
            ?.path("value")
            ?.takeIf { it.isNumber }
            ?.asDouble()
}
