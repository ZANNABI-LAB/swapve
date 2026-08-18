package dev.swapve.csms.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.csms.auth.AuthorizationRegistry
import dev.swapve.csms.auth.AuthorizationStatus
import dev.swapve.csms.swap.OutTimedOutLedger
import dev.swapve.csms.swap.SwapTransactionRegistry
import dev.swapve.ocpp.session.InMemoryOcppEventLog
import dev.swapve.ocpp.session.MessageDirection
import dev.swapve.ocpp.session.OcppEventRecord
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.swap.SwapTransaction
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * ★ **성공 기준 S5 의 계산 — 새 저장소를 만들지 않는다** (PLAN §2, §11.1).
 *
 * ### 왜 Micrometer/Actuator 를 쓰지 않는가
 *
 * 판단할 자유가 주어졌으므로 근거를 남긴다. **쓰지 않는 쪽이 더 단순하다:**
 *
 * 1. **필요한 값이 전부 이미 있는 기록에서 파생 계산된다.** 성공률은
 *    [SwapTransactionRegistry] 의 상태 분포이고, 소요시간은 그 상태에 이미 들어 있는
 *    시각들의 차이이며, 실패 사유는 거부·이상·멱등 기록과 [InMemoryOcppEventLog] 의 원문에
 *    있다. PLAN §11.1 이 이벤트 로그에 건 **유일한 규칙**이 *"파생 상태는 이 로그에서 계산될
 *    수 있어야 한다"* 이고, 지표야말로 그 규칙이 실제로 지켜지는지 확인하는 자리다.
 * 2. **카운터를 따로 두면 진실의 원본이 둘이 된다.** `Counter.increment()` 를 코드 곳곳에
 *    심는 순간, 장부(H2)와 카운터(인메모리)가 재시작·예외·재전송에서 어긋나기 시작한다.
 *    그러면 지표가 시스템을 설명하는 것이 아니라 **지표 자신을 설명해야 하는 대상**이 된다.
 * 3. **소비자가 없다.** PLAN §10 결정 #2 가 대시보드를 제외했다. Micrometer 의 값은
 *    Prometheus 같은 scrape 대상이 있을 때 나오는데, 그 대상이 이 프로젝트에 없다.
 *    S5 가 요구한 것은 **REST 조회**까지다.
 * 4. **의존성이 늘지 않는다.** `spring-boot-starter-actuator` 없이 끝난다.
 *
 * 규모가 커져 **시계열**(시간에 따른 추이)이 필요해지면 그때는 이야기가 다르다. 이 계산은
 * 언제나 "지금까지의 전량"이라 구간 질의를 할 수 없기 때문이다. 그때 붙일 자리도 여기다 —
 * 이벤트 로그가 남아 있으므로 소급 계산이 가능하다.
 *
 * ### 계산 창(window)이 두 개라는 사실을 숨기지 않는다
 *
 * 인메모리 기록은 **이 프로세스가 본 것**이고, [OutTimedOutLedger] 는 **재시작을 가로질러
 * 남는 채무**다. 둘을 한 숫자로 합치면 어느 쪽도 참이 아닌 값이 나온다. 그래서
 * [SwapMetrics.ledger] 를 따로 둔다.
 */
@Component
class SwapMetricsService(
    private val transactions: SwapTransactionRegistry,
    private val outTimedOutLedger: OutTimedOutLedger,
    private val authorizations: AuthorizationRegistry,
    private val eventLog: InMemoryOcppEventLog,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    fun collect(): SwapMetrics {
        // 한 시점의 스냅샷으로 계산한다. 계산 도중 새 교환이 들어와 분자와 분모가 서로 다른
        // 순간을 보면, 성공률이 100% 를 넘는 것 같은 값이 잠깐 나올 수 있다.
        val states = transactions.all().values.filterNot { it is SwapTransaction.Idle }
        val rejections = transactions.rejections()
        val anomalies = transactions.anomalies()
        val ignored = transactions.ignoredEvents()
        val batteryRejections = batteryRejectionReasons()

        val completed = states.filterIsInstance<SwapTransaction.Completed>()
        val outTimedOut = states.filterIsInstance<SwapTransaction.OutTimedOut>()
        val inProgress = states.size - completed.size - outTimedOut.size

        // 분모는 **스테이션에 실제로 도달한 개시**다. 인가가 막은 시도는 여기 없다.
        val attempted = states.size + rejections.size

        val counts = SwapCounts(
            attempted = attempted,
            completed = completed.size,
            inProgress = inProgress,
            failed = outTimedOut.size + rejections.size,
            blockedStarts = rejectedAuthorizations(),
        )

        val failureScenarios = mapOf(
            // F1 — 배터리 부족. 재고 판정은 스테이션이 한다 (PLAN §4.5, S02.FR.04).
            "F1" to rejections.size,
            // F2 — 수령 타임아웃. orphan BatteryIn 이 남았다 (S03.FR.06).
            "F2" to outTimedOut.size,
            // F3 — 미등록 배터리. customData 로 거부했다 (PLAN §4.8).
            "F3" to batteryRejections.values.sum(),
            // F5 — 순서 위반. 응답은 정상 회신됐고 사실만 기록됐다 (PLAN §5.4).
            "F5" to anomalies.size,
        )

        return SwapMetrics(
            generatedAt = clock.instant(),
            swaps = counts,
            successRate = if (attempted == 0) null else completed.size.toDouble() / attempted,
            duration = SwapDurationMetrics(
                completed = DurationSummary.of(
                    completed.map { Duration.between(it.startedAt, it.completedAt) },
                ),
                outTimedOut = DurationSummary.of(
                    outTimedOut.map { Duration.between(it.startedAt, it.timedOutAt) },
                ),
            ),
            failures = SwapFailureMetrics(
                total = failureScenarios.values.sum(),
                byScenario = failureScenarios,
                byReasonCode = reasonCodes(rejections.mapNotNull { it.reasonCode }, batteryRejections),
                byAnomalyReason = anomalies.groupingBy { it.reason.name }.eachCount(),
                rejectedAuthorizations = counts.blockedStarts,
            ),
            idempotency = SwapIdempotencyMetrics(
                byScenario = mapOf(
                    // F4 — 새 messageId 로 온 중복. 상태머신이 잡았다.
                    "F4" to ignored.size,
                    // F6 — 같은 messageId 재전송. 세션의 멱등 원장이 잡았다.
                    "F6" to sessionReplays(),
                ),
                stateMachineIgnores = ignored.size,
                byIgnoreReason = ignored.groupingBy { it.reason.name }.eachCount(),
                sessionReplays = sessionReplays(),
            ),
            ledger = outTimedOutLedger.all().let { records ->
                SwapLedgerMetrics(
                    openImbalances = records.size,
                    orphanBatteries = records.sumOf { it.ledgerImbalance },
                )
            },
        )
    }

    /**
     * 인가되지 않은 토큰으로 들어온 시도 (S02.FR.03 포함).
     *
     * [AuthorizationRegistry] 는 **판정 결과와 무관하게** 시도를 남긴다 (PLAN §11.3 — 미인식
     * 토큰도 거부하되 기록한다). 그 목록에서 통과하지 못한 것만 센다.
     */
    private fun rejectedAuthorizations(): Int =
        authorizations.attempts().count { it.status != AuthorizationStatus.ACCEPTED }

    /**
     * 두 종류의 거부 사유를 한 표로 모은다 (PLAN §4.9.1).
     *
     * 스테이션이 개시를 거부한 사유(`NoBatteryAvailable`)와 우리가 배터리를 거부한
     * 사유(`BatteryUnknown`)는 **같은 부록 표(`reason_codes.csv`)에서 온다.** 나누어 세면
     * 호출자가 다시 합쳐야 한다.
     */
    private fun reasonCodes(
        startRejections: List<String>,
        batteryRejections: Map<String, Int>,
    ): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        startRejections.forEach { counts.merge(it, 1, Int::plus) }
        batteryRejections.forEach { (code, count) -> counts.merge(code, count, Int::plus) }
        return counts
    }

    /**
     * F3 — **이벤트 로그에서 파생 계산한다** (PLAN §11.1).
     *
     * ### 왜 새 기록을 만들지 않았나
     *
     * 미등록 배터리 거부는 `OcppMessageRouter` 가 `BatterySwapResponse` 의 `customData` 로
     * 내보내고 (PLAN §4.8), 그 응답 **원문이 이벤트 로그에 그대로 남아 있다.** 지표를 위해
     * 카운터나 표를 새로 만들기 전에 *"이미 있는 것으로 계산되는지"* 를 먼저 본 결과가 이것이다.
     *
     * 이 함수가 성립한다는 것 자체가 §11.1 의 규칙이 지켜지고 있다는 실행 가능한 증거다.
     * 원문 대신 해석 결과만 남겼다면 여기서 셀 수 있는 것이 없었을 것이다.
     *
     * @return `reasonCode` 별 건수. 지금은 `BatteryUnknown` 하나지만, 표(§4.9.1)의 다른
     *   사유를 쓰게 되면 코드 수정 없이 따라온다.
     */
    private fun batteryRejectionReasons(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()

        eventLog.all().forEach { record ->
            if (record.direction != MessageDirection.OUTBOUND) return@forEach
            if (record.action != BatterySwapWire.BATTERY_SWAP) return@forEach

            val payload = callResultPayload(record) ?: return@forEach
            val customData = payload.path("customData")
            if (customData.path("vendorId").asText() != BatterySwapWire.VENDOR_ID_BATTERY_SWAP_RESPONSE) return@forEach
            if (customData.path("status").asText() != BatterySwapWire.GENERIC_REJECTED) return@forEach

            val reasonCode = customData.path("statusInfo").path("reasonCode")
                .takeIf { it.isTextual }?.asText() ?: UNSPECIFIED_REASON
            counts.merge(reasonCode, 1, Int::plus)
        }

        return counts
    }

    /**
     * F6 — 같은 `(stationId, messageId)` 로 **두 번 이상 도착한 수신 CALL** (PLAN §5.4 F6).
     *
     * 세션의 멱등 원장은 재전송을 잡아 저장된 응답을 그대로 내고 상위 계층을 부르지 않으므로,
     * 그 사실은 **어떤 도메인 기록에도 남지 않는다.** 남는 곳은 이벤트 로그뿐이다 —
     * `OcppSession` 이 멱등 판정 **전에** 수신 원문을 적기 때문이다.
     *
     * action 을 가리지 않는다. 멱등 원장은 `BatterySwap` 만이 아니라 모든 수신 CALL 에
     * 적용되고, 재전송이 일어났다는 사실 자체가 관측 대상이다.
     */
    private fun sessionReplays(): Int {
        var replays = 0

        eventLog.all()
            .filter { it.direction == MessageDirection.INBOUND && isCall(it) }
            .groupingBy { it.stationId to it.messageId }
            .eachCount()
            .forEach { (_, count) -> if (count > 1) replays += count - 1 }

        return replays
    }

    /** 프레임 원문이 CALL(타입 2)인가. 읽지 못하면 아니라고 본다. */
    private fun isCall(record: OcppEventRecord): Boolean =
        frame(record)?.let { it.isArray && it.size() > 0 && it.get(0).asInt() == MESSAGE_TYPE_CALL } == true

    /** CALLRESULT(타입 3) 의 페이로드 — `[3,"<id>",{payload}]`. 아니면 `null`. */
    private fun callResultPayload(record: OcppEventRecord): JsonNode? {
        val frame = frame(record) ?: return null
        if (!frame.isArray || frame.size() < 3) return null
        if (frame.get(0).asInt() != MESSAGE_TYPE_CALL_RESULT) return null
        return frame.get(2)
    }

    /**
     * 프레임 원문을 읽는다. **읽지 못해도 예외를 던지지 않는다** — 이벤트 로그에는 디코딩에
     * 실패한 원문도 그대로 남아 있고 (PLAN §11.1), 지표 조회가 그것 때문에 500 이 되어서는
     * 안 된다.
     */
    private fun frame(record: OcppEventRecord): JsonNode? = try {
        mapper.readTree(record.payload)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MESSAGE_TYPE_CALL = 2
        const val MESSAGE_TYPE_CALL_RESULT = 3

        /** `statusInfo` 없이 거부된 경우. 표(§4.9.1)의 값이 아니므로 그 자리에 섞지 않는다. */
        const val UNSPECIFIED_REASON = "Unspecified"
    }
}
