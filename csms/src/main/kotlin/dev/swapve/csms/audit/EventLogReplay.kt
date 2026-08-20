package dev.swapve.csms.audit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.ocpp.rpc.MessageType
import dev.swapve.ocpp.session.MessageDirection
import dev.swapve.ocpp.session.OcppEventRecord
import dev.swapve.ocpp.swap.AvailabilityState
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.swap.IdToken
import dev.swapve.swap.SlotState
import java.time.Instant

/**
 * ★ **이벤트 로그에서 파생 상태를 되살린다** (PLAN §11.1).
 *
 * §11.1 은 이벤트 로그에 대해 규칙을 딱 하나만 둔다:
 *
 * > *"파생 상태(교환 트랜잭션 등)는 이 로그에서 계산될 수 있어야 한다. 그게 유일한 규칙이다."*
 *
 * 그 규칙은 산문으로 두면 지켜지는지 알 수 없다. 여기서 **로그 원문만 읽어** 교환·슬롯·충전
 * 상태를 다시 계산하고, 감사가 그 결과를 인메모리 레지스트리와 대조한다. 둘이 어긋나면
 * 그 자체가 감사 실패다 — 레지스트리에만 있고 로그로는 설명되지 않는 상태가 있다는 뜻이고,
 * 그러면 재시작 복구(BACKLOG B02)도 수평 확장(§11.5)도 근거를 잃는다.
 *
 * ### 레지스트리를 참조하지 않는다
 *
 * 이 파일은 `dev.swapve.csms.swap` 의 어떤 레지스트리도 읽지 않는다. 읽으면 대조가 아니라
 * 자기 자신과의 비교가 된다.
 */
object EventLogReplay {

    private val MAPPER = ObjectMapper()

    // ------------------------------------------------------------------ 재구성 결과

    /** 로그에서 되살린 배터리 한 개 (`BatteryDataType`). */
    data class ReplayBattery(
        val slotId: Int,
        val serialNumber: String,
        val soC: Double,
        val soH: Double,
    )

    /**
     * 로그에서 되살린 교환 한 건. 상관키는 `(stationId, requestId)` 복합키다 (PLAN §5.3).
     *
     * @param authorizedBeforeFirstEvent 첫 교환 사건 이전에 그 토큰의 인가가 로그에 있었는가.
     *   CSMS 는 인가 기록이 없으면 교환을 열지 않는다 (`SwapCoordinator`), 그래서 재구성도
     *   그 규칙을 그대로 따른다 — 없는 인가를 지어내면 대조가 무의미해진다.
     * @param duplicateEvents 같은 반쪽이 두 번 온 횟수. 멱등 무시 대상이다 (PLAN §5.4 F4).
     */
    data class ReplaySwap(
        val stationId: String,
        val requestId: Int,
        val idToken: IdToken,
        val authorizedBeforeFirstEvent: Boolean,
        val batteriesIn: List<ReplayBattery>,
        val batteriesOut: List<ReplayBattery>,
        val outTimedOut: Boolean,
        val duplicateEvents: Int,
        val firstSeq: Long,
        val lastSeq: Long,
        val authorizedAt: Instant?,
        val inAt: Instant?,
        val outAt: Instant?,
        val timedOutAt: Instant?,
    ) {
        val isCompleted: Boolean get() = batteriesIn.isNotEmpty() && batteriesOut.isNotEmpty() && !outTimedOut

        /** 이 교환이 건드린 슬롯 전부 — 입고 슬롯과 출고 슬롯은 다르다 (PLAN §7.1). */
        val slots: Set<Int> get() = (batteriesIn + batteriesOut).map { it.slotId }.toSet()
    }

    /** 로그에서 되살린 충전 트랜잭션 사건 하나. */
    data class ReplayChargingEvent(
        val eventType: String,
        val triggerReason: String,
        val seqNo: Int,
        val slotId: Int?,
        val chargingState: String?,
        val stoppedReason: String?,
        val idToken: IdToken?,
        val at: Instant,
        val socPercent: Double?,
    )

    /** 로그에서 되살린 충전 트랜잭션 (S04). 교환과 **다른 키**다 (PLAN §5.1). */
    data class ReplayCharging(
        val stationId: String,
        val transactionId: String,
        val slotId: Int?,
        val events: List<ReplayChargingEvent>,
        val eventTypes: List<String>,
        val isEnded: Boolean,
        val startSeq: Long?,
        val endSeq: Long?,
        val startedAt: Instant?,
        val endedAt: Instant?,
    )

    /** CALL 한 건과 그 응답. 응답이 없으면 [response] 가 `null` — 그것이 유실이다. */
    data class ReplayExchange(
        val stationId: String,
        val messageId: String,
        val action: String?,
        val direction: MessageDirection,
        val callSeq: Long,
        val response: OcppEventRecord?,
    ) {
        val isAnswered: Boolean get() = response != null
        val isError: Boolean
            get() = response?.let { typeOf(it) == MessageType.CALL_ERROR || typeOf(it) == MessageType.CALL_RESULT_ERROR } == true
    }

    /** 스테이션 한 대의 재구성 결과 전부. */
    data class ReplayedStation(
        val stationId: String,
        val records: List<OcppEventRecord>,
        val swaps: Map<Int, ReplaySwap>,
        val slotStates: Map<Int, SlotState>,
        val slotObservedAt: Map<Int, Instant>,
        val charging: Map<String, ReplayCharging>,
        val exchanges: List<ReplayExchange>,
        /** CALL 이 아닌데 짝을 찾지 못한 응답 — 있어서는 안 된다. */
        val orphanResponses: List<OcppEventRecord>,
    )

    // ------------------------------------------------------------------ 재구성

    fun replay(stationId: String, records: List<OcppEventRecord>): ReplayedStation {
        val ordered = records.sortedBy { it.seq }
        val (exchanges, orphans) = pairCalls(stationId, ordered)
        val (slotStates, slotObservedAt) = replaySlotStates(ordered)

        return ReplayedStation(
            stationId = stationId,
            records = ordered,
            swaps = replaySwaps(stationId, ordered),
            slotStates = slotStates,
            slotObservedAt = slotObservedAt,
            charging = replayCharging(stationId, ordered),
            exchanges = exchanges,
            orphanResponses = orphans,
        )
    }

    /**
     * 교환 트랜잭션 재구성 — `Authorize` 인가와 `BatterySwap` 사건만으로 계산한다.
     *
     * 인가는 `(stationId, idToken)` 기준으로 성립하고 (M5 `AuthorizationRegistry`), 상관키는
     * 첫 `BatterySwap` 이 실어 오는 `requestId` 에서 확정된다 — S01 의 requestId 는 스테이션이
     * 발번하기 때문이다 (PLAN §4.4).
     */
    private fun replaySwaps(stationId: String, records: List<OcppEventRecord>): Map<Int, ReplaySwap> {
        val grants = mutableSetOf<String>()
        val remoteGrants = LinkedHashMap<Int, RemoteGrant>()
        val swaps = LinkedHashMap<Int, ReplaySwap>()

        records.forEach { record ->
            if (typeOf(record) != MessageType.CALL) return@forEach
            val payload = callPayload(record)

            when {
                record.direction == MessageDirection.OUTBOUND && record.action == BatterySwapWire.REQUEST_BATTERY_SWAP -> {
                    val response = responseOf(records, record)
                    val accepted = response?.let { resultPayload(it).path("status").asText() } ==
                        BatterySwapWire.GENERIC_ACCEPTED
                    if (accepted) {
                        remoteGrants[payload.path("requestId").asInt()] = RemoteGrant(
                            idToken = idTokenOf(payload.path("idToken")),
                            at = response?.occurredAt ?: record.occurredAt,
                        )
                    }
                }

                record.direction != MessageDirection.INBOUND -> Unit

                record.action == BatterySwapWire.AUTHORIZE -> {
                    // 인가가 **났는지**는 우리가 낸 응답이 정한다. 요청만으로는 알 수 없다.
                    val accepted = responseOf(records, record)
                        ?.let { resultPayload(it).path("idTokenInfo").path("status").asText() } ==
                        BatterySwapWire.AUTHORIZATION_ACCEPTED
                    if (accepted) grants += tokenKey(payload.path("idToken"))
                }

                record.action == BatterySwapWire.BATTERY_SWAP -> {
                    val requestId = payload.path("requestId").asInt()
                    val batteries = batteriesOf(payload.path("batteryData"))
                    val existing = swaps[requestId]
                    val remoteGrant = remoteGrants[requestId]
                    val authorized = existing?.authorizedBeforeFirstEvent
                        ?: (
                            tokenKey(payload.path("idToken")) in grants ||
                                remoteGrant?.idToken == idTokenOf(payload.path("idToken"))
                            )

                    val base = existing ?: ReplaySwap(
                        stationId = stationId,
                        requestId = requestId,
                        idToken = idTokenOf(payload.path("idToken")),
                        authorizedBeforeFirstEvent = authorized,
                        batteriesIn = emptyList(),
                        batteriesOut = emptyList(),
                        outTimedOut = false,
                        duplicateEvents = 0,
                        firstSeq = record.seq,
                        lastSeq = record.seq,
                        authorizedAt = authorizedAt(records, record, payload.path("idToken")) ?: remoteGrant?.at,
                        inAt = null,
                        outAt = null,
                        timedOutAt = null,
                    )

                    swaps[requestId] = when (payload.path("eventType").asText()) {
                        BatterySwapWire.BATTERY_IN ->
                            if (base.batteriesIn.isEmpty()) {
                                base.copy(batteriesIn = batteries, lastSeq = record.seq, inAt = record.occurredAt)
                            } else {
                                // 같은 반쪽이 또 왔다 — 멱등 무시 대상이다 (PLAN §5.4 F4).
                                base.copy(duplicateEvents = base.duplicateEvents + 1, lastSeq = record.seq)
                            }

                        BatterySwapWire.BATTERY_OUT ->
                            if (base.batteriesOut.isEmpty()) {
                                base.copy(batteriesOut = batteries, lastSeq = record.seq, outAt = record.occurredAt)
                            } else {
                                base.copy(duplicateEvents = base.duplicateEvents + 1, lastSeq = record.seq)
                            }

                        BatterySwapWire.BATTERY_OUT_TIMEOUT ->
                            base.copy(outTimedOut = true, lastSeq = record.seq, timedOutAt = record.occurredAt)

                        else -> base.copy(lastSeq = record.seq)
                    }
                }
            }
        }

        // 인가 없이 온 교환 사건은 CSMS 가 열지 않는다 (PLAN §5.4 F5). 재구성도 그렇게 한다.
        return swaps.filterValues { it.authorizedBeforeFirstEvent }
    }

    private data class RemoteGrant(
        val idToken: IdToken,
        val at: Instant,
    )

    /**
     * 슬롯 점유 상태 재구성 — `NotifyEvent` 의 `Connector`/`AvailabilityState` 만 읽는다.
     *
     * **반전은 [AvailabilityState] 한 곳에서만 일어난다** (PLAN §4.2). 감사 코드가 `"Occupied"`
     * 를 직접 해석하면 CSMS 와 같은 실수를 독립적으로 반복할 수 있으므로, 프로덕션 코드가
     * 지나는 그 문을 그대로 지난다.
     */
    private fun replaySlotStates(records: List<OcppEventRecord>): Pair<Map<Int, SlotState>, Map<Int, Instant>> {
        val states = LinkedHashMap<Int, SlotState>()
        val observedAt = LinkedHashMap<Int, Instant>()

        records.forEach { record ->
            if (record.direction != MessageDirection.INBOUND) return@forEach
            if (record.action != BatterySwapWire.NOTIFY_EVENT || typeOf(record) != MessageType.CALL) return@forEach

            callPayload(record).path("eventData").forEach { event ->
                val component = event.path("component")
                if (component.path("name").asText() != BatterySwapWire.COMPONENT_CONNECTOR) return@forEach
                if (event.path("variable").path("name").asText() != BatterySwapWire.VARIABLE_AVAILABILITY_STATE) {
                    return@forEach
                }
                val evseId = component.path("evse").path("id").takeIf { it.isInt }?.asInt() ?: return@forEach
                val holdsBattery = AvailabilityState.holdsBattery(event.path("actualValue").asText())
                    ?: return@forEach
                states[evseId] = if (holdsBattery) SlotState.HOLDS_BATTERY else SlotState.EMPTY
                observedAt[evseId] = timestampOf(event, record.occurredAt)
            }
        }

        return states to observedAt
    }

    /** 충전 트랜잭션 재구성 — `TransactionEvent` 만으로. 교환을 참조하지 않는다 (PLAN §5.1). */
    private fun replayCharging(stationId: String, records: List<OcppEventRecord>): Map<String, ReplayCharging> {
        val transactions = LinkedHashMap<String, ReplayCharging>()

        records.forEach { record ->
            if (record.direction != MessageDirection.INBOUND) return@forEach
            if (record.action != BatterySwapWire.TRANSACTION_EVENT || typeOf(record) != MessageType.CALL) return@forEach

            val payload = callPayload(record)
            val transactionId = payload.path("transactionInfo").path("transactionId").asText().ifBlank { return@forEach }
            val eventType = payload.path("eventType").asText()
            val slotId = payload.path("evse").path("id").takeIf { it.isInt }?.asInt()
            val event = ReplayChargingEvent(
                eventType = eventType,
                triggerReason = payload.path("triggerReason").asText(),
                seqNo = payload.path("seqNo").asInt(),
                slotId = slotId,
                chargingState = payload.path("transactionInfo").path("chargingState").takeIf { it.isTextual }?.asText(),
                stoppedReason = payload.path("transactionInfo").path("stoppedReason").takeIf { it.isTextual }?.asText(),
                idToken = idTokenOfOrNull(payload.path("idToken")),
                at = timestampOf(payload, record.occurredAt),
                socPercent = socPercent(payload),
            )

            val existing = transactions[transactionId]
            transactions[transactionId] = ReplayCharging(
                stationId = stationId,
                transactionId = transactionId,
                slotId = existing?.slotId ?: slotId,
                events = existing?.events.orEmpty() + event,
                eventTypes = existing?.eventTypes.orEmpty() + event.eventType,
                isEnded = eventType == BatterySwapWire.TX_ENDED,
                startSeq = existing?.startSeq ?: record.seq.takeIf { eventType == BatterySwapWire.TX_STARTED },
                endSeq = if (eventType == BatterySwapWire.TX_ENDED) record.seq else existing?.endSeq,
                startedAt = existing?.startedAt ?: record.occurredAt.takeIf { eventType == BatterySwapWire.TX_STARTED },
                endedAt = if (eventType == BatterySwapWire.TX_ENDED) record.occurredAt else existing?.endedAt,
            )
        }

        return transactions
    }

    /**
     * CALL ↔ 응답 짝짓기 — **유실 메시지 0** 을 판정하는 근거다 (PLAN §5.3).
     *
     * 짝은 `messageId` 로 맺어지고 **방향이 반대**여야 한다. 우리가 받은 CALL 의 응답은 우리가
     * 보낸 것이고, 우리가 보낸 CALL 의 응답은 상대가 보낸 것이다. 응답이 없는 CALL 이 유실이고,
     * 짝 없는 응답도 마찬가지로 이상이다 — 우리가 보낸 적 없는 CALL 에 답이 왔다는 뜻이니.
     */
    private fun pairCalls(
        stationId: String,
        records: List<OcppEventRecord>,
    ): Pair<List<ReplayExchange>, List<OcppEventRecord>> {
        val calls = LinkedHashMap<String, OcppEventRecord>()
        val responses = LinkedHashMap<String, OcppEventRecord>()
        val orphans = ArrayList<OcppEventRecord>()

        records.forEach { record ->
            when (typeOf(record)) {
                MessageType.CALL -> calls[record.messageId] = record
                MessageType.CALL_RESULT, MessageType.CALL_ERROR, MessageType.CALL_RESULT_ERROR -> {
                    val call = calls[record.messageId]
                    if (call == null || call.direction == record.direction) orphans += record
                    else responses[record.messageId] = record
                }
                // SEND 는 응답을 기다리지 않는다 (Part 4 §4.2.4). 유실 판정 대상이 아니다.
                else -> Unit
            }
        }

        val exchanges = calls.values.map { call ->
            ReplayExchange(
                stationId = stationId,
                messageId = call.messageId,
                action = call.action,
                direction = call.direction,
                callSeq = call.seq,
                response = responses[call.messageId],
            )
        }
        return exchanges to orphans
    }

    // ------------------------------------------------------------------ 원문 읽기

    fun typeOf(record: OcppEventRecord): MessageType? =
        MessageType.ofNumber(MAPPER.readTree(record.payload).get(0).asInt())

    /** `[2,"<id>","<action>",{payload}]` */
    fun callPayload(record: OcppEventRecord): JsonNode = MAPPER.readTree(record.payload).get(3)

    /** `[3,"<id>",{payload}]` */
    fun resultPayload(record: OcppEventRecord): JsonNode = MAPPER.readTree(record.payload).get(2)

    private fun responseOf(records: List<OcppEventRecord>, call: OcppEventRecord): OcppEventRecord? =
        records.firstOrNull {
            it.messageId == call.messageId &&
                it.direction != call.direction &&
                typeOf(it) == MessageType.CALL_RESULT
        }

    private fun authorizedAt(records: List<OcppEventRecord>, call: OcppEventRecord, idToken: JsonNode): Instant? =
        records.asSequence()
            .filter { it.seq < call.seq }
            .filter { it.direction == MessageDirection.INBOUND && it.action == BatterySwapWire.AUTHORIZE }
            .filter { typeOf(it) == MessageType.CALL }
            .filter { tokenKey(callPayload(it).path("idToken")) == tokenKey(idToken) }
            .lastOrNull { authorizeAccepted(records, it) }
            ?.let { responseOf(records, it)?.occurredAt ?: it.occurredAt }

    private fun authorizeAccepted(records: List<OcppEventRecord>, call: OcppEventRecord): Boolean =
        responseOf(records, call)
            ?.let { resultPayload(it).path("idTokenInfo").path("status").asText() } ==
            BatterySwapWire.AUTHORIZATION_ACCEPTED

    private fun tokenKey(node: JsonNode): String =
        node.path("idToken").asText() + "|" + node.path("type").asText()

    private fun idTokenOf(node: JsonNode): IdToken =
        IdToken(node.path("idToken").asText(), node.path("type").asText())

    private fun idTokenOfOrNull(node: JsonNode): IdToken? {
        val value = node.path("idToken").takeIf { it.isTextual }?.asText()
        val type = node.path("type").takeIf { it.isTextual }?.asText()
        return if (!value.isNullOrBlank() && !type.isNullOrBlank()) IdToken(value, type) else null
    }

    private fun timestampOf(payload: JsonNode, fallback: Instant): Instant =
        payload.path("timestamp").takeIf { it.isTextual }?.asText()
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: fallback

    private fun socPercent(payload: JsonNode): Double? =
        payload.path("meterValue")
            .flatMap { it.path("sampledValue") }
            .lastOrNull { it.path("measurand").asText() == BatterySwapWire.MEASURAND_SOC }
            ?.path("value")
            ?.takeIf { it.isNumber }
            ?.asDouble()

    private fun batteriesOf(array: JsonNode): List<ReplayBattery> = array.map { item ->
        ReplayBattery(
            slotId = item.path("evseId").asInt(),
            serialNumber = item.path("serialNumber").asText(),
            soC = item.path("soC").asDouble(),
            soH = item.path("soH").asDouble(),
        )
    }
}
