package dev.swapve.csms.audit

import dev.swapve.csms.audit.EventLogReplay.ReplayedStation
import dev.swapve.csms.swap.ChargingTransactionRegistry
import dev.swapve.csms.swap.SlotStateRegistry
import dev.swapve.csms.swap.SwapTransactionRegistry
import dev.swapve.ocpp.rpc.MessageType
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.schema.PayloadValidation
import dev.swapve.swap.SlotId
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapTransaction

/**
 * 감사 항목 하나의 결과.
 *
 * ### `checked` 를 함께 남기는 이유
 *
 * "통과"만 찍히면 **검사를 안 한 것과 구분되지 않는다.** 필터가 잘못 걸려 0 건을 검사하고
 * 초록으로 끝나는 감사는 감사가 아니라 장식이다. 그래서 항목마다 몇 건을 봤는지 세고,
 * [InvariantAudit] 이 0 건인 항목을 실패로 취급한다.
 */
data class AuditItem(
    val name: String,
    val basis: String,
    val checked: Int,
    val unit: String,
    val violations: List<String>,
) {
    val passed: Boolean get() = violations.isEmpty() && checked > 0
}

/** 감사 결과 전체. [render] 가 사람이 읽을 표를 만든다. */
data class AuditReport(val scale: String, val items: List<AuditItem>) {

    val failures: List<AuditItem> get() = items.filterNot { it.passed }

    fun render(): String = buildString {
        appendLine("──────────────────────────────────────────────────────────────────────")
        appendLine(" 불변식 감사 — 성공 기준 S4 (PLAN §2 · §5.3 · §11.1)")
        appendLine(" $scale")
        appendLine("──────────────────────────────────────────────────────────────────────")
        appendLine(" %-34s %-9s %10s  %s".format("항목", "근거", "검사", "판정"))
        items.forEach { item ->
            appendLine(
                " %-34s %-9s %10s  %s".format(
                    item.name,
                    item.basis,
                    "${item.checked}${item.unit}",
                    if (item.passed) "✅ 통과" else "❌ 실패",
                ),
            )
            item.violations.take(MAX_SHOWN).forEach { appendLine("     └ $it") }
            if (item.violations.size > MAX_SHOWN) {
                appendLine("     └ … 외 ${item.violations.size - MAX_SHOWN} 건")
            }
            if (item.checked == 0) appendLine("     └ 검사 대상이 0 건이다 — 통과가 아니라 미검사다")
        }
        appendLine("──────────────────────────────────────────────────────────────────────")
    }

    private companion object {
        const val MAX_SHOWN = 10
    }
}

/**
 * ★★ **부하 뒤의 불변식 감사** (PLAN §2 성공 기준 S4, §7.3 L3).
 *
 * > *"스테이션 20대 동시 접속 후 불변식 감사 전항목 통과 — 배터리 수량 보존, 슬롯 이중 예약 0,
 * > 유실 메시지 0"*
 *
 * ### 감사는 이벤트 로그에서 재구성해 검증한다 (PLAN §11.1)
 *
 * §11.1 은 *"파생 상태는 이 로그에서 계산될 수 있어야 한다"* 를 유일한 규칙으로 둔다.
 * 그래서 이 감사는 인메모리 레지스트리를 **정답으로 쓰지 않는다.** [EventLogReplay] 가 로그
 * 원문만으로 상태를 다시 계산하고, 항목 7 이 그 결과를 레지스트리와 대조한다. 둘이 어긋나면
 * 그 자체가 감사 실패다 — 로그로 설명되지 않는 상태가 있다는 뜻이기 때문이다.
 *
 * ### 감사 기준을 낮춰서 통과시키지 않는다
 *
 * 항목이 빨개지면 그것은 고쳐야 할 버그다. 임계값을 완화하거나 대상을 좁히는 것은 답이 아니다.
 */
class InvariantAudit(
    private val replayed: List<ReplayedStation>,
    private val swaps: SwapTransactionRegistry,
    private val slotStates: SlotStateRegistry,
    private val charging: ChargingTransactionRegistry,
    private val validator: OcppPayloadValidator,
) {

    fun run(): AuditReport {
        val messageCount = replayed.sumOf { it.records.size }
        val swapCount = replayed.sumOf { it.swaps.size }
        return AuditReport(
            scale = "스테이션 ${replayed.size}대 · 교환 ${swapCount}건 · 메시지 ${messageCount}건",
            items = listOf(
                batteryConservation(),
                slotDoubleBooking(),
                lostMessages(),
                swapKeyUniqueness(),
                swapChargingSeparation(),
                eventLogOrdering(),
                replayMatchesRegistry(),
                schemaCompliance(),
                noUnexpectedCallErrors(),
            ),
        )
    }

    // ------------------------------------------------------------------ 1. 배터리 수량 보존 (§5.3)

    /**
     * **완료된 교환마다 들어온 수 = 나간 수** (PLAN §5.3 `COMPLETED` 불변식).
     *
     * 로그 재구성과 레지스트리 양쪽에서 센다. 한쪽만 보면 "상태 객체가 균형을 강제한다"는
     * 사실(도메인 생성자의 `require`)에 기대게 되는데, 그건 **균형이 깨진 교환이 애초에
     * Completed 가 되지 못했다**는 뜻일 뿐 장부가 맞다는 뜻은 아니다. 로그 쪽 계산은 그
     * 강제를 지나지 않으므로 진짜 대조가 된다.
     *
     * 일련번호 수준도 본다 — 같은 배터리가 두 교환에서 동시에 들어오거나, 들어온 배터리가
     * 같은 교환에서 그대로 나가면 수량은 맞아도 장부는 거짓이다.
     */
    private fun batteryConservation(): AuditItem {
        val violations = mutableListOf<String>()
        var checked = 0
        val seenIncoming = mutableMapOf<String, String>()

        replayed.forEach { station ->
            station.swaps.values.filter { it.isCompleted }.forEach { swap ->
                checked++
                if (swap.batteriesIn.size != swap.batteriesOut.size) {
                    violations += "${station.stationId}#${swap.requestId}: 입고 ${swap.batteriesIn.size}개 ≠ " +
                        "출고 ${swap.batteriesOut.size}개 (로그 재구성)"
                }

                val inSerials = swap.batteriesIn.map { it.serialNumber }.toSet()
                val outSerials = swap.batteriesOut.map { it.serialNumber }.toSet()
                (inSerials intersect outSerials).forEach {
                    violations += "${station.stationId}#${swap.requestId}: 들어온 배터리가 그대로 나갔다 ($it)"
                }
                inSerials.forEach { serial ->
                    val owner = "${station.stationId}#${swap.requestId}"
                    seenIncoming.put(serial, owner)?.let { previous ->
                        violations += "배터리 $serial 이 두 교환에 입고됐다 ($previous, $owner)"
                    }
                }

                val stored = swaps.find(StationId(station.stationId), swap.requestId)
                val completed = stored as? SwapTransaction.Completed
                if (completed == null) {
                    violations += "${station.stationId}#${swap.requestId}: 레지스트리 상태가 Completed 가 아니다 " +
                        "(${stored?.let { it::class.simpleName }})"
                } else if (completed.batteriesIn.size != completed.batteriesOut.size) {
                    violations += "${station.stationId}#${swap.requestId}: 레지스트리 장부 불균형"
                }
            }
        }

        return AuditItem("배터리 수량 보존", "§5.3", checked, "건 교환", violations)
    }

    // ------------------------------------------------------------------ 2. 슬롯 이중 예약 (§5.3)

    /**
     * **한 슬롯이 동시에 두 교환에 묶이지 않는다** (PLAN §5.3).
     *
     * 교환 하나가 슬롯을 쥐고 있는 구간을 `[첫 사건 seq, 마지막 사건 seq]` 로 잡고, 같은
     * 스테이션의 같은 슬롯에서 두 구간이 겹치는지 본다. `seq` 는 **스테이션 안에서만** 순서를
     * 보장하므로 (PLAN §11.1) 비교도 스테이션 안에서만 한다 — 스테이션을 가로지르는 전역
     * 순서는 애초에 없는 약속이다.
     *
     * 한 교환 안에서 입고 슬롯과 출고 슬롯이 겹치는 경우도 이중 예약이다. `TC_S_103_CSMS` 가
     * 입고 A·B 와 출고 C·D 를 **다른 슬롯**으로 지정하는 이유가 그것이다 (PLAN §7.1).
     */
    private fun slotDoubleBooking(): AuditItem {
        val violations = mutableListOf<String>()
        var checked = 0

        replayed.forEach { station ->
            station.swaps.values.forEach { swap ->
                val inSlots = swap.batteriesIn.map { it.slotId }.toSet()
                val outSlots = swap.batteriesOut.map { it.slotId }.toSet()
                (inSlots intersect outSlots).forEach {
                    violations += "${station.stationId}#${swap.requestId}: 슬롯 $it 이 입고와 출고에 동시에 묶였다"
                }
            }

            val bindings = station.swaps.values.flatMap { swap ->
                swap.slots.map { slot -> Triple(slot, swap.requestId, swap.firstSeq..swap.lastSeq) }
            }
            bindings.groupBy { it.first }.forEach { (slot, held) ->
                val sorted = held.sortedBy { it.third.first }
                sorted.zipWithNext().forEach { (earlier, later) ->
                    checked++
                    if (earlier.third.last >= later.third.first) {
                        violations += "${station.stationId} 슬롯 $slot: 교환 #${earlier.second}(seq ${earlier.third}) 과 " +
                            "#${later.second}(seq ${later.third}) 이 겹친다"
                    }
                }
                // 구간이 하나뿐인 슬롯도 검사 대상이다 — 겹칠 짝이 없다는 사실을 확인한 것이다.
                if (sorted.size == 1) checked++
            }
        }

        return AuditItem("슬롯 이중 예약 0", "§5.3", checked, "개 슬롯점유", violations)
    }

    // ------------------------------------------------------------------ 3. 유실 메시지 (§5.3)

    /**
     * **보낸 CALL 은 모두 응답됐다** (PLAN §5.3).
     *
     * 짝은 `messageId` 로 맺고 방향이 반대여야 한다. 응답 없는 CALL 이 유실이다.
     *
     * *"또는 명시적으로 타임아웃됐다"* 는 조건이 이 부하에서는 **비어 있어야 한다.** 시뮬레이터의
     * `call()` 은 `OcppResult.TimedOut` 을 받으면 시나리오를 그 자리에서 실패시키므로
     * (`StationSimulator.call`), 타임아웃이 한 건이라도 있었다면 감사 이전에 부하가 먼저 깨졌다.
     * 즉 여기 도달했다는 것은 침묵이 용인된 자리가 없었다는 뜻이고, 그래서 응답 없는 CALL 은
     * 예외 없이 위반이다.
     */
    private fun lostMessages(): AuditItem {
        val violations = mutableListOf<String>()
        var checked = 0

        replayed.forEach { station ->
            station.exchanges.forEach { exchange ->
                checked++
                if (!exchange.isAnswered) {
                    violations += "${station.stationId}: ${exchange.action} (messageId=${exchange.messageId}, " +
                        "seq=${exchange.callSeq}) 이 응답되지 않았다"
                }
            }
            station.orphanResponses.forEach {
                violations += "${station.stationId}: 짝 없는 응답 ${it.messageId} — ${it.payload}"
            }
        }

        return AuditItem("유실 메시지 0", "§5.3", checked, "건 CALL", violations)
    }

    // ------------------------------------------------------------------ 4. (stationId, requestId) 유일 (§5.3)

    /**
     * **같은 키로 두 교환이 열리지 않았다** (PLAN §5.3).
     *
     * ### 부하가 이 항목을 실제로 시험한다
     *
     * 20 대가 **같은 `requestId`** 로 동시에 교환을 연다 ([LoadScenario] KDoc). `requestId` 는
     * 스테이션 범위에서만 유일하므로 (스펙에 전역 유일성 규정이 없다), 이 상황에서 교환은
     * 20 건으로 갈려야 한다. 키를 `requestId` 하나로 잡은 구현이라면 여기서 무너진다.
     */
    private fun swapKeyUniqueness(): AuditItem {
        val violations = mutableListOf<String>()
        var checked = 0

        val byRequestId = mutableMapOf<Int, MutableSet<String>>()

        replayed.forEach { station ->
            val stationId = StationId(station.stationId)
            val registryKeys = swaps.of(stationId).keys.map { it.requestId.value }.toSet()

            station.swaps.values.forEach { swap ->
                checked++
                byRequestId.getOrPut(swap.requestId) { mutableSetOf() } += station.stationId
                if (swap.duplicateEvents > 0) {
                    violations += "${station.stationId}#${swap.requestId}: 같은 반쪽이 " +
                        "${swap.duplicateEvents}회 더 왔다 (부하 중 재전송은 주입하지 않았다)"
                }
                if (swap.requestId !in registryKeys) {
                    violations += "${station.stationId}#${swap.requestId}: 로그에는 있는데 레지스트리에 없다"
                }
            }

            val extra = registryKeys - station.swaps.keys
            extra.forEach {
                violations += "${station.stationId}#$it: 레지스트리에는 있는데 로그로 설명되지 않는다"
            }
        }

        // 같은 requestId 를 쓴 스테이션들이 서로 다른 교환으로 갈렸는가.
        byRequestId.forEach { (requestId, stations) ->
            val opened = stations.count { stationId ->
                swaps.find(StationId(stationId), requestId) != null
            }
            if (opened != stations.size) {
                violations += "requestId $requestId 을 ${stations.size}대가 썼는데 교환은 ${opened}건뿐이다 " +
                    "— (stationId, requestId) 복합키가 무너졌다"
            }
        }

        return AuditItem("(stationId, requestId) 유일", "§5.3", checked, "개 상관키", violations)
    }

    // ------------------------------------------------------------------ 5. 교환/충전 분리 (§5.1)

    /**
     * **충전 트랜잭션이 교환 완료로 인해 끊기지 않았다** (PLAN §5.1).
     *
     * > *"들어온 배터리의 충전은 교환이 끝난 뒤에도 며칠 계속된다."*
     *
     * 그래서 교환이 `Completed` 인데도 **입고 슬롯의 충전은 살아 있어야** 하고, 끝난 것은
     * 반출된 배터리의 충전(출고 슬롯)뿐이어야 한다. 둘을 한 애그리게이트로 합친 구현이라면
     * 교환 종료가 충전까지 닫아 버리고, 이 항목이 그것을 잡는다.
     */
    private fun swapChargingSeparation(): AuditItem {
        val violations = mutableListOf<String>()
        var checked = 0

        replayed.forEach { station ->
            val stationId = StationId(station.stationId)
            val liveBySlot = station.charging.values.filter { !it.isEnded }.mapNotNull { it.slotId }.toSet()
            val endedBySlot = station.charging.values.filter { it.isEnded }.mapNotNull { it.slotId }.toSet()

            station.swaps.values.filter { it.isCompleted }.forEach { swap ->
                swap.batteriesIn.forEach { battery ->
                    checked++
                    if (battery.slotId !in liveBySlot) {
                        violations += "${station.stationId}#${swap.requestId}: 입고 슬롯 ${battery.slotId} 의 충전이 " +
                            "교환 완료와 함께 끊겼다"
                    }
                }
                swap.batteriesOut.forEach { battery ->
                    checked++
                    if (battery.slotId !in endedBySlot) {
                        violations += "${station.stationId}#${swap.requestId}: 반출 슬롯 ${battery.slotId} 의 충전이 " +
                            "닫히지 않았다"
                    }
                }
            }

            // 레지스트리 쪽에서도 같은 사실이 읽혀야 한다.
            charging.of(stationId).forEach { transaction ->
                val replay = station.charging[transaction.transactionId]
                if (replay != null && replay.isEnded != transaction.isEnded) {
                    violations += "${station.stationId}: 충전 ${transaction.transactionId} 의 종료 여부가 " +
                        "로그(${replay.isEnded})와 레지스트리(${transaction.isEnded})에서 다르다"
                }
            }
        }

        return AuditItem("교환/충전 분리", "§5.1", checked, "개 슬롯", violations)
    }

    // ------------------------------------------------------------------ 6. 이벤트 로그 순서 (§11.1)

    /**
     * **스테이션별 `seq` 가 빠짐없이 증가한다** (PLAN §11.1).
     *
     * 결번이 있으면 그 사이에 무슨 일이 있었는지 알 수 없고, 리플레이로 상태를 재구성한다는
     * 약속(§11.1 표)이 무너진다. 20 대가 동시에 쓰는 로그이므로 이 항목이 곧 **순번 부여의
     * 경합 안전성 시험**이다 — 락 밖에서 번호를 매기면 여기서 중복이나 결번으로 드러난다.
     */
    private fun eventLogOrdering(): AuditItem {
        val violations = mutableListOf<String>()
        var checked = 0

        replayed.forEach { station ->
            checked++
            val seqs = station.records.map { it.seq }
            val expected = (1L..station.records.size).toList()
            if (seqs != expected) {
                val duplicates = seqs.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                violations += "${station.stationId}: seq 가 1..${station.records.size} 로 이어지지 않는다 " +
                    "(중복 ${duplicates.take(5)}, 실제 범위 ${seqs.minOrNull()}..${seqs.maxOrNull()})"
            }
        }

        return AuditItem("이벤트 로그 순서", "§11.1", checked, "대 스테이션", violations)
    }

    // ------------------------------------------------------------------ 7. 재구성 ↔ 레지스트리 (§11.1)

    /**
     * ★ **로그에서 되살린 상태가 인메모리 레지스트리와 일치한다** (PLAN §11.1).
     *
     * §11.1 의 유일한 규칙이 *"파생 상태는 이 로그에서 계산될 수 있어야 한다"* 이므로, 이
     * 항목이 그 규칙의 시험이다. 어긋나면 둘 중 하나다 — 로그에 남지 않은 입력으로 상태가
     * 바뀌었거나(로그가 불완전하다), 같은 입력을 두 코드가 다르게 해석하고 있거나.
     * **어느 쪽이든 감사 실패다.**
     */
    private fun replayMatchesRegistry(): AuditItem {
        val violations = mutableListOf<String>()
        var checked = 0

        replayed.forEach { station ->
            val stationId = StationId(station.stationId)

            station.swaps.values.forEach { swap ->
                checked++
                val stored = swaps.find(stationId, swap.requestId)
                val completed = stored as? SwapTransaction.Completed ?: return@forEach

                val replaySignature = signature(swap.batteriesIn.map { it.slotId to it.serialNumber })
                val storedSignature = signature(completed.batteriesIn.map { it.slotId.value to it.serialNumber })
                if (replaySignature != storedSignature) {
                    violations += "${station.stationId}#${swap.requestId}: 입고 배터리가 다르다 " +
                        "(로그 $replaySignature ≠ 레지스트리 $storedSignature)"
                }
                val replayOut = signature(swap.batteriesOut.map { it.slotId to it.serialNumber })
                val storedOut = signature(completed.batteriesOut.map { it.slotId.value to it.serialNumber })
                if (replayOut != storedOut) {
                    violations += "${station.stationId}#${swap.requestId}: 출고 배터리가 다르다 " +
                        "(로그 $replayOut ≠ 레지스트리 $storedOut)"
                }
            }

            station.slotStates.forEach { (slot, state) ->
                checked++
                val stored = slotStates.stateOf(stationId, SlotId(slot))
                if (stored != state) {
                    violations += "${station.stationId} 슬롯 $slot: 로그 $state ≠ 레지스트리 $stored"
                }
            }

            val storedCharging = charging.of(stationId).associateBy { it.transactionId }
            (station.charging.keys + storedCharging.keys).forEach { transactionId ->
                checked++
                val replay = station.charging[transactionId]
                val stored = storedCharging[transactionId]
                when {
                    replay == null -> violations += "${station.stationId}: 충전 $transactionId 이 로그로 설명되지 않는다"
                    stored == null -> violations += "${station.stationId}: 충전 $transactionId 이 레지스트리에 없다"
                    replay.slotId != stored.slotId?.value ->
                        violations += "${station.stationId}: 충전 $transactionId 의 슬롯이 다르다 " +
                            "(로그 ${replay.slotId} ≠ 레지스트리 ${stored.slotId?.value})"
                }
            }
        }

        return AuditItem("로그 재구성 ↔ 레지스트리", "§11.1", checked, "개 대조", violations)
    }

    // ------------------------------------------------------------------ 8·9. 부하 중 프로토콜 무결성

    /**
     * **부하 중에도 공식 스키마를 어긴 메시지가 하나도 없다** (PLAN §6 설계원칙 2).
     *
     * 검사 대상이 우리가 만든 객체가 아니라 **실제로 소켓을 지난 원문**이라는 것이 요점이다.
     * 20 대가 동시에 붙는다고 해서 페이로드가 달라질 이유는 없지만, 그것은 확인하기 전까지
     * 주장일 뿐이다.
     */
    private fun schemaCompliance(): AuditItem {
        val violations = mutableListOf<String>()
        var checked = 0

        replayed.forEach { station ->
            station.records.forEach { record ->
                val validation = when (EventLogReplay.typeOf(record)) {
                    MessageType.CALL -> validator.validateCall(
                        record.action ?: return@forEach,
                        EventLogReplay.callPayload(record),
                    )

                    MessageType.CALL_RESULT -> validator.validateCallResult(
                        record.action ?: return@forEach,
                        EventLogReplay.resultPayload(record),
                    )
                    // 페이로드 스키마가 없는 프레임이다 (Part 4 §4.2.3).
                    else -> return@forEach
                }

                checked++
                if (validation is PayloadValidation.Invalid) {
                    violations += "${station.stationId} ${record.direction} ${record.action}: " +
                        "${validation.errorDescription}\n       원문: ${record.payload}"
                }
            }
        }

        return AuditItem("공식 스키마 위반 0", "§6 원칙2", checked, "건 메시지", violations)
    }

    /**
     * **CALLERROR 가 예상 밖으로 발생하지 않았다.**
     *
     * 부하 시나리오에는 잘못된 프레임도 미구현 action 도 없다. 그런 상황에서 오류 프레임이
     * 하나라도 오갔다면 어느 한쪽이 상대의 정상 메시지를 처리하지 못한 것이고, 그건 부하가
     * 드러낸 버그다.
     */
    private fun noUnexpectedCallErrors(): AuditItem {
        val violations = mutableListOf<String>()
        var checked = 0

        replayed.forEach { station ->
            station.records.forEach { record ->
                checked++
                val type = EventLogReplay.typeOf(record)
                if (type == MessageType.CALL_ERROR || type == MessageType.CALL_RESULT_ERROR) {
                    violations += "${station.stationId} ${record.direction} seq=${record.seq}: ${record.payload}"
                }
            }
        }

        return AuditItem("CALLERROR 0", "§7.1", checked, "건 프레임", violations)
    }

    private fun signature(entries: List<Pair<Int, String>>): List<String> =
        entries.map { "${it.first}:${it.second}" }.sorted()
}
