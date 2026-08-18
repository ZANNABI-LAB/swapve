package dev.swapve.csms.swap

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.csms.auth.AuthorizationRegistry
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.swap.BatteryData
import dev.swapve.swap.IdToken
import dev.swapve.swap.SlotId
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapEvent
import dev.swapve.swap.SwapKey
import dev.swapve.swap.SwapRequestId
import dev.swapve.swap.SwapTransaction
import dev.swapve.swap.SwapStateMachine
import dev.swapve.swap.SwapTransition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * `BatterySwapRequest` 를 교환 트랜잭션의 전이로 옮긴다 (S03).
 *
 * ### ★ 인가는 requestId 를 실어 오는 첫 `BatterySwap` 에서 열린다
 *
 * M5 의 [AuthorizationRegistry] KDoc 이 이미 결론을 내려 두었다. S01 에서 CSMS 는
 * `AuthorizeRequest` 를 처리하는 시점에 **requestId 를 알 방법이 없다** — 그 값은 스테이션이
 * 발번해 나중에 `BatterySwapRequest` 로 실어 온다 (PLAN §4.4). 그래서 M5 는 인가를
 * `(stationId, idToken)` 기준 `GrantedAuthorization` 으로 따로 들고 있었다.
 *
 * M6 가 그 결론을 이어받는 자리가 여기다. 첫 `BatterySwap` 이 도착하면
 * [AuthorizationRegistry.grantOf] 로 인가 사실을 꺼내
 * `SwapEvent.Authorized(SwapKey(stationId, requestId), grant.idToken, grant.at)` 를 **먼저**
 * 흘린 뒤 입고/출고 사건을 잇는다. 인가 시각이 `grant.at` 인 것이 요점이다 — 배터리 투입
 * 시각으로 대신하면 과금 근거가 조용히 훼손된다 (PLAN §11.2).
 *
 * ### 상태머신을 다시 구현하지 않는다
 *
 * 전이는 전부 `swap-domain` 의 [SwapStateMachine] 이 한다 (M3). 이 클래스가 하는 일은
 * **프로토콜 페이로드를 도메인 사건으로 번역**하고 결과를 보관하는 것뿐이다.
 *
 * ### 거부하지 않는다
 *
 * `BatterySwapResponse` 는 *"Empty response by CSMS to confirm receipt"* 다 (PLAN §4.3).
 * 인가가 없어도, 순서가 어긋나도(F5), 중복이어도(F4) **응답은 정상 회신**이고 사실만 기록에
 * 남는다. `customData` 로 배터리를 거부하는 확장(§4.8)은 **M7** 이므로 여기 없다.
 */
@Component
class SwapCoordinator(
    private val authorizations: AuthorizationRegistry,
    private val transactions: SwapTransactionRegistry,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * `BatterySwapRequest` 하나를 반영한다.
     *
     * @return 반영 뒤의 교환 상태. 호출자는 이 값으로 무엇을 회신할지 정하지 않는다 —
     *   회신은 언제나 빈 응답이다.
     */
    fun onBatterySwap(stationId: StationId, payload: ObjectNode): SwapTransaction {
        val requestId = payload.path("requestId").asInt()
        val key = SwapKey(stationId, SwapRequestId(requestId))
        val idToken = idTokenOf(payload.path("idToken"))
        val batteries = batteryDataOf(payload.path("batteryData"))
        val at = clock.instant()

        ensureAuthorizationApplied(key, idToken)

        val event = when (val eventType = payload.path("eventType").asText()) {
            BatterySwapWire.BATTERY_IN -> SwapEvent.BatteryIn(key, idToken, batteries, at)
            BatterySwapWire.BATTERY_OUT -> SwapEvent.BatteryOut(key, idToken, batteries, at)
            BatterySwapWire.BATTERY_OUT_TIMEOUT -> SwapEvent.BatteryOutTimeout(key, at)
            // 스키마가 허용하는 값은 셋뿐이라 여기까지 올 수 없다. 그래도 조용히 넘기지 않는다 —
            // 표준이 값을 늘리면 이 예외가 그 사실을 알리는 자리가 된다.
            else -> error("모르는 BatterySwap eventType: $eventType")
        }

        return apply(key, event, at)
    }

    /**
     * 아직 교환이 열리지 않았다면 인가 사실을 찾아 [SwapEvent.Authorized] 를 먼저 흘린다.
     *
     * 인가가 없으면 **아무것도 하지 않는다.** 그러면 이어지는 입고/출고가 [SwapTransaction.Idle]
     * 에 도착해 상태머신이 `NOT_AUTHORIZED` 이상으로 판정한다 (PLAN §5.4 F5). 없는 인가를
     * 여기서 지어내면 그 위반이 영영 보이지 않게 된다.
     */
    private fun ensureAuthorizationApplied(key: SwapKey, idToken: IdToken) {
        if (transactions.stateOf(key) !is SwapTransaction.Idle) return

        val grant = authorizations.grantOf(key.stationId, idToken)
        if (grant == null) {
            log.info("인가 기록 없이 교환 사건이 왔다: key={} idTokenType={}", key, idToken.type)
            return
        }

        // 인가 시각은 `AuthorizeResponse(Accepted)` 를 낸 그 시각이다 (PLAN §11.2).
        apply(key, SwapEvent.Authorized(key, grant.idToken, grant.at), grant.at)
    }

    /** 전이 한 번 — 결과를 보관하고, 이상과 멱등 무시는 사실로 남긴다. */
    private fun apply(key: SwapKey, event: SwapEvent, at: Instant): SwapTransaction {
        val transition = SwapStateMachine.transition(transactions.stateOf(key), event)
        transactions.store(key, transition.state)

        when (transition) {
            is SwapTransition.Advanced -> Unit

            is SwapTransition.Ignored -> {
                transactions.recordIgnored(SwapIgnored(key, transition.reason, at))
                log.info("교환 사건을 멱등 무시했다: key={} reason={}", key, transition.reason)
            }

            is SwapTransition.Anomaly -> {
                transactions.recordAnomaly(SwapAnomaly(key, transition.reason, transition.description, at))
                // 응답은 그대로 정상 회신된다 (PLAN §5.4). 기록만 남긴다.
                log.warn("교환 이상: key={} reason={} {}", key, transition.reason, transition.description)
            }
        }

        return transition.state
    }

    /**
     * `IdTokenType` → 도메인 값 객체.
     *
     * 로컬 사용자 테이블의 FK 가 아니라 `(idToken, type)` 값 객체다 (PLAN §11.3).
     */
    private fun idTokenOf(node: JsonNode): IdToken =
        IdToken(node.path("idToken").asText(), node.path("type").asText())

    /**
     * `BatteryDataType` → 도메인 값 객체.
     *
     * `evseId` 가 곧 슬롯 번호다 (PLAN §4.1). `soC`/`soH` 는 **양쪽 다 보존된다** —
     * 교환 완료 시 결과만 남기고 버리면 나중에 과금이 스키마 변경 없이는 불가능해진다
     * (PLAN §11.2).
     */
    private fun batteryDataOf(array: JsonNode): List<BatteryData> = array.map { item ->
        BatteryData(
            slotId = SlotId(item.path("evseId").asInt()),
            serialNumber = item.path("serialNumber").asText(),
            soC = item.path("soC").asDouble(),
            soH = item.path("soH").asDouble(),
        )
    }
}
