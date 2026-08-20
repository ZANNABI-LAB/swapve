package dev.swapve.csms.swap

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.csms.auth.AuthorizationRegistry
import dev.swapve.csms.auth.AuthorizationStatus
import dev.swapve.csms.station.StationRegistry
import dev.swapve.csms.ws.AuthMethod
import dev.swapve.ocpp.rpc.SwapRequestIds
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.schema.PayloadValidation
import dev.swapve.ocpp.session.OcppCall
import dev.swapve.ocpp.session.OcppResult
import dev.swapve.ocpp.session.StationCommandBus
import dev.swapve.ocpp.swap.BatteryRejectionReason
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.swap.IdToken
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapKey
import dev.swapve.swap.SwapRequestId
import dev.swapve.swap.SwapTransaction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * S02 원격 개시 한 번의 결말.
 *
 * **예외를 던지지 않는다.** 인가 거부도, 연결 없음도, 스테이션의 `Rejected` 도 전부 정상
 * 운영 중 일어나는 일이지 예외적 사건이 아니다 (M4 [OcppResult] 와 같은 태도).
 */
sealed interface RemoteSwapStart {

    /** 스테이션이 `Accepted` 로 답했다. 교환이 [SwapTransaction.Authorized] 로 열렸다. */
    data class Accepted(val key: SwapKey, val transaction: SwapTransaction) : RemoteSwapStart

    /**
     * 스테이션이 `Rejected` 로 답했다 (F1 = `TC_S_102_CSMS`).
     *
     * 교환은 **열리지 않았다.** 사유는 스테이션이 정한다 (S02.FR.04) — 보통
     * [BatteryRejectionReason.NO_BATTERY_AVAILABLE] 이다.
     */
    data class Rejected(val rejection: SwapRejection) : RemoteSwapStart

    /**
     * ★ **S02.FR.03 — 인가되지 않은 idToken 으로는 보내지 않는다(SHALL NOT).**
     *
     * 보내고 나서 거부당한 것이 아니라 **아예 보내지 않았다.** 그 차이가 요점이라
     * [Rejected] 와 다른 타입이다.
     */
    data class NotAuthorized(val idToken: IdToken, val status: AuthorizationStatus) : RemoteSwapStart

    /** 그 스테이션의 연결이 없거나 응답이 오지 않았다. */
    data class Unreachable(val stationId: StationId, val result: OcppResult) : RemoteSwapStart
}

/**
 * S02 — CSMS 가 교환을 개시한다.
 *
 * > *"EV Driver requests CSMS to initiate a battery swap **via a smartphone app, e.g. by
 * > scanning a QR code** or selecting the appropriate station in the app."*
 *
 * ### 왜 M7 에 있나 (v3.1 범위 조정)
 *
 * v3 는 S02 를 M8 에 두었다. 그런데 **`TC_S_103_CSMS` 의 1단계가 CSMS 의
 * `RequestBatterySwapRequest` 발신**이다. 발신 경로가 없으면 적합성을 통과할 수 없으므로
 * 발신만 M7 로 앞당겼다. 교환 REST API 와 지표는 M8 에 그대로 남는다.
 *
 * ### REST 컨트롤러를 만들지 않는다
 *
 * 이 클래스의 [start] 가 **발신을 촉발하는 내부 진입점의 전부**다 (확정 결정 결정 #2).
 * 앱이 소비할 `POST /api/swaps` 는 M8 에서 이 함수를 부르는 얇은 껍데기가 된다 — 지금
 * 없는 소비자를 위해 엔드포인트를 미리 뚫는 것이 과설계다.
 *
 * ### 세션 객체를 만지지 않는다
 *
 * 발신은 [StationCommandBus] 로만 한다. 이 클래스는 `stationId` 만 알고, 그 스테이션의
 * 세션이 이 JVM 에 있는지조차 모른다. 분산이 필요해지면 버스 구현체만 바뀐다.
 */
@Component
class RemoteSwapStarter(
    private val commandBus: StationCommandBus,
    private val authorizations: AuthorizationRegistry,
    private val stations: StationRegistry,
    private val coordinator: SwapCoordinator,
    private val validator: OcppPayloadValidator,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [stationId] 에 교환을 시작하라고 지시한다.
     *
     * @param requestId 상관 번호. 생략하면 **CSMS 가 발번한다** — S02 에서 발번 주체는
     * CSMS 다 (표). 스테이션은 이어지는 `BatterySwapRequest` 에 **같은 값을
     *   써야 한다(SHALL)** (S02.FR.02).
     */
    suspend fun start(
        stationId: StationId,
        idToken: IdToken,
        requestId: Int = SwapRequestIds.newId(),
    ): RemoteSwapStart {
        val at = clock.instant()

        // ★ S02.FR.03 — 인가되지 않은 idToken 으로 RequestBatterySwap 을 보내면 안 된다(SHALL NOT).
        // 판정을 먼저 하고, 통과하지 못하면 프레임을 만들지도 않는다.
        val attempt = authorizations.authorize(stationId, idToken, authMethodOf(stationId), at)
        if (attempt.status != AuthorizationStatus.ACCEPTED) {
            log.info(
                "S02.FR.03 — 인가되지 않은 토큰이라 RequestBatterySwap 을 보내지 않는다: station={} status={}",
                stationId, attempt.status.wireValue,
            )
            return RemoteSwapStart.NotAuthorized(idToken, attempt.status)
        }

        val key = SwapKey(stationId, SwapRequestId(requestId))
        val payload = requestPayload(requestId, idToken)

        // 우리가 만든 요청도 공식 스키마로 자기 검증한다 (설계원칙 2). 손으로 필드를
        // 짐작하다 틀리면 스테이션이 아니라 여기서 터진다.
        val validation = validator.validateCall(BatterySwapWire.REQUEST_BATTERY_SWAP, payload)
        if (validation is PayloadValidation.Invalid) {
            error("우리가 만든 RequestBatterySwapRequest 가 공식 스키마를 통과하지 못했다: ${validation.errorDescription}")
        }

        val result = commandBus.send(
            stationId.value,
            OcppCall(BatterySwapWire.REQUEST_BATTERY_SWAP, payload),
        )
        if (result !is OcppResult.Accepted) {
            log.warn("RequestBatterySwap 을 보내지 못했다: station={} result={}", stationId, result)
            return RemoteSwapStart.Unreachable(stationId, result)
        }

        return interpret(key, idToken, result.payload)
    }

    /**
     * `RequestBatterySwapResponse` 를 읽는다.
     *
     * `Accepted` 면 **여기서 교환을 연다.** S01 과 달리 CSMS 가 requestId 를 알고 있으므로
     * 상태머신의 *"AUTHORIZED — requestId 확정"* 이 S02 에서는 그대로 성립한다
     * (S01 에서 왜 성립하지 않는지는 `AuthorizationRegistry` KDoc 에 적혀 있다).
     */
    private fun interpret(key: SwapKey, idToken: IdToken, response: JsonNode): RemoteSwapStart {
        val statusInfo = response.path("statusInfo")

        if (response.path("status").asText() != BatterySwapWire.GENERIC_ACCEPTED) {
            val rejection = SwapRejection(
                key = key,
                idToken = idToken,
                reasonCode = statusInfo.path("reasonCode").takeIf { it.isTextual }?.asText(),
                additionalInfo = statusInfo.path("additionalInfo").takeIf { it.isTextual }?.asText(),
                at = clock.instant(),
            )
            coordinator.onRemoteStartRejected(rejection)
            return RemoteSwapStart.Rejected(rejection)
        }

        val transaction = coordinator.onRemoteStartAccepted(key, idToken, clock.instant())
        return RemoteSwapStart.Accepted(key, transaction)
    }

    private fun requestPayload(requestId: Int, idToken: IdToken): ObjectNode =
        JsonNodeFactory.instance.objectNode().apply {
            // Tool validation (step 1): requestId 가 있어야 하고, idToken.idToken 이
            // 설정된 valid_idtoken 이어야 한다 (Part 6 p.1366).
            put("requestId", requestId)
            putObject("idToken").apply {
                put("idToken", idToken.idToken)
                put("type", idToken.type)
            }
        }

    /**
     * 이 스테이션의 신원이 **어떻게 확인됐는지**.
     *
     * 부팅 기록에 남아 있으면 그 값을, 아직 부팅 기록이 없으면 [AuthMethod.NONE] 이다 —
     * MVP 의 모든 연결이 그 값이라 실질적 차이는 없지만, 프로파일이 올라가면 여기가
     * 달라진다. 그때 고칠 자리가 한 곳이 되게 둔다.
     */
    private fun authMethodOf(stationId: StationId): AuthMethod =
        stations.find(stationId)?.authMethod ?: AuthMethod.NONE
}
