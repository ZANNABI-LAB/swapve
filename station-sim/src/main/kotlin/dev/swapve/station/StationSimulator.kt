package dev.swapve.station

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.ocpp.rpc.MessageIds
import dev.swapve.ocpp.rpc.RpcErrorCode
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.schema.PayloadValidation
import dev.swapve.ocpp.session.InMemoryOcppEventLog
import dev.swapve.ocpp.session.InboundCallLedger
import dev.swapve.ocpp.session.InboundResponse
import dev.swapve.ocpp.session.OcppCall
import dev.swapve.ocpp.session.OcppResult
import dev.swapve.ocpp.session.OcppSession
import dev.swapve.ocpp.session.StationSerializer
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.swap.SlotState
import java.time.Clock

/**
 * 배터리 교환 스테이션 시뮬레이터 — IoT 쪽 역할 (PLAN §6).
 *
 * ### 이것은 시뮬레이터가 아니라 명세의 실행본이다
 *
 * 함수 하나하나가 Part 6 의 **스테이션 측 재사용 상태**에 대응한다 (PLAN §7.2). 시험 대상이
 * Charging Station 인 케이스가 곧 시뮬레이터의 명세이기 때문이다.
 *
 * | Part 6 재사용 상태 | 함수 |
 * |---|---|
 * | `BootedBatterySwapping` | [boot] |
 * | `AuthorizedBatterySwapping` (A: 로컬 인가 S01) | [authorize] |
 * | `EVConnectedPreSessionBatterySwapping` | [insertBatteries] |
 * | `EnergyTransferStartedBatterySwapping` | [reportChargingStarted] |
 * | `EVDisconnectedBatterySwapping` | [removeBatteries] |
 *
 * [runSwap] 이 이들을 `SwapOrder` 에 따라 엮는다. **두 순서 모두 같은 상태 집합을 지나되
 * 진입 순서만 다르다** — Part 6 의 진입 조건을 그대로 옮기면 PLAN §4.6 의 양방향이 저절로
 * 검증된다.
 *
 * ### 프로토콜을 다시 구현하지 않는다
 *
 * 프레이밍·스키마 검증·멱등·per-station 직렬화는 전부 `ocpp-core` 의 [OcppSession] 이 한다.
 * **CSMS 와 같은 모듈을 공유하는 것이 요점이다** (PLAN §6 원칙 3) — 인코딩이 어긋나면 양쪽
 * 중 한쪽이 아니라 시험 전체가 즉시 빨개진다.
 *
 * ### 도메인 어휘로 상태를 들고, 경계에서만 바꾼다
 *
 * 슬롯 상태는 [SlotState] 로 들고 있다가 나갈 때만 `Available`/`Occupied` 로 바꾼다
 * (PLAN §4.2, §6 원칙 4). 그 변환은 `ocpp-core` 의 `AvailabilityState` 한 군데에만 있다.
 *
 * ### 실제 시간을 쓰지 않는다
 *
 * 모든 시각은 [clock] 에서 오고, 모든 단계는 상대의 CALLRESULT 를 기다려 이어진다.
 * `sleep` 이 없으므로 시험이 결정적이다.
 */
class StationSimulator(
    val config: StationSimConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val faults: FaultInjection = FaultInjection.None,
    /** 오간 메시지 원문이 전부 남는다 (PLAN §11.1). 시험이 이 로그를 읽어 계약을 확인한다. */
    val eventLog: InMemoryOcppEventLog = InMemoryOcppEventLog(),
    private val validator: OcppPayloadValidator = OcppPayloadValidator(),
) : AutoCloseable {

    /** 슬롯 하나의 현재 상태. 배터리 유무가 곧 [SlotState] 다. */
    private class SimSlot(val config: SlotConfig, var battery: SimBattery?) {
        /** 이 슬롯에서 도는 충전 트랜잭션. 배터리가 없으면 `null`. */
        var transactionId: String? = null

        /** `TransactionEvent.seqNo` — 트랜잭션 안에서 0 부터 증가한다. */
        var txSeqNo: Int = 0

        val state: SlotState get() = if (battery == null) SlotState.EMPTY else SlotState.HOLDS_BATTERY
    }

    private val slots: Map<Int, SimSlot> =
        config.slots.associate { it.slotId to SimSlot(it, it.battery) }

    private var transport: WebSocketTransport? = null

    private var eventIdSeq = 0
    private var notifySeqNo = 0

    /**
     * 세션. 멱등 원장과 직렬화기는 이 스테이션 전용이라 새로 만든다 — CSMS 쪽처럼 여러
     * 스테이션이 공유하는 자리가 아니다.
     */
    private val session = OcppSession(
        stationId = config.stationId,
        transmit = { text -> connectedTransport().send(text) },
        onCall = { _, call -> handleInboundCall(call) },
        eventSink = eventLog,
        ledger = InboundCallLedger(),
        serializer = StationSerializer(),
        clock = clock,
        validator = validator,
    )

    // ------------------------------------------------------------------ 연결

    /** CSMS 에 붙는다. `{csmsUrl}/{stationId}` 로 간다 (Part 4 §3.1.1). */
    suspend fun connect() {
        check(transport == null) { "이미 연결돼 있다: ${config.stationId}" }
        transport = WebSocketTransport.connect(config.connectUrl) { text -> session.receive(text) }
    }

    val isConnected: Boolean get() = transport?.isOpen == true

    /** 협상된 서브프로토콜. `ocpp2.1` 이어야 한다 (Part 4 §3.1.2). */
    val subprotocol: String get() = connectedTransport().subprotocol

    override fun close() {
        session.close()
        transport?.close()
        transport = null
    }

    // ------------------------------------------------------------------ BootedBatterySwapping

    /**
     * 부팅 시퀀스 (Part 6 `BootedBatterySwapping`).
     *
     * 1. `BootNotificationRequest` → 2. CSMS 가 `status = Accepted`
     * 3. **모든 커넥터의 현재 상태를 알린다** → 4. CSMS 응답
     * 5. `SecurityEventNotificationRequest` → 6. CSMS 응답
     *
     * 마지막에 배터리가 든 슬롯마다 충전 트랜잭션을 새로 연다. Part 6 의 이 상태에는 없지만
     * **S04.FR.11 이 요구한다** — *"스테이션 재부팅 시 배터리가 있는 모든 EVSE 에 대해
     * 트랜잭션을 새로 시작"*. 이게 없으면 나중에 그 배터리를 내줄 때 종료할 트랜잭션이
     * 없어서 장부가 허공에서 끝난다.
     */
    suspend fun boot() {
        faults.before(SimStep.BOOT, context())
        val response = call(BatterySwapWire.BOOT_NOTIFICATION, SimPayloads.bootNotification(config))
        val status = response.path("status").asText()
        check(status == BatterySwapWire.REGISTRATION_ACCEPTED) {
            "부팅이 거부됐다: status=$status"
        }

        orderedSlots().forEach { reportSlotStatus(it) }

        faults.before(SimStep.SECURITY_EVENT, context())
        call(
            BatterySwapWire.SECURITY_EVENT_NOTIFICATION,
            SimPayloads.securityEvent(BatterySwapWire.SECURITY_EVENT_STARTUP, clock.instant()),
        )

        orderedSlots().filter { it.battery != null }.forEach { startChargingTransaction(it) }
    }

    // ------------------------------------------------------------------ AuthorizedBatterySwapping

    /**
     * S01 로컬 인가 (Part 6 `AuthorizedBatterySwapping` 방식 A, PLAN §4.4).
     *
     * **requestId 는 여기서 스테이션이 정한다.** CSMS 는 `AuthorizeRequest` 만으로는 그 값을
     * 알 수 없고, 이어지는 `BatterySwapRequest` 가 실어 올 때 비로소 교환을 연다 — CSMS 쪽
     * `AuthorizationRegistry` KDoc 이 그 결론을 적어 두었다.
     *
     * 방식 B(`RequestBatterySwap` 으로 CSMS 가 개시하는 S02)는 M8 이다.
     */
    suspend fun authorize() {
        faults.before(SimStep.AUTHORIZE, context())
        val response = call(
            BatterySwapWire.AUTHORIZE,
            SimPayloads.authorize(config.idToken.idToken, config.idToken.type),
        )
        val status = response.path("idTokenInfo").path("status").asText()
        check(status == BatterySwapWire.AUTHORIZATION_ACCEPTED) {
            "인가가 거부됐다: status=$status"
        }
    }

    // ------------------------------------------------------------------ EVConnectedPreSession

    /**
     * 배터리 투입 (Part 6 `EVConnectedPreSessionBatterySwapping`).
     *
     * 1. 슬롯 상태 변경 알림(`Occupied`) → 2. 응답
     * 3. `TransactionEventRequest(Started)` → 4. 응답
     * **(1~4 를 투입된 배터리마다 반복한다)**
     * 5. `BatterySwapRequest(BatteryIn)` → 6. `BatterySwapResponse`
     *
     * 마지막 `BatterySwap` 하나가 **투입된 배터리 전부**를 싣는다 — 배터리는 세트 단위다
     * (PLAN §10 결정 #6).
     */
    suspend fun insertBatteries() {
        val inserted = config.insertSlots.zip(config.incomingBatteries)

        inserted.forEach { (slotId, battery) ->
            val slot = slotOf(slotId)
            check(slot.battery == null) { "이미 배터리가 든 슬롯에 투입하려 한다: $slotId" }
            slot.battery = battery
            reportSlotStatus(slot)
            startChargingTransaction(slot)
        }

        faults.before(SimStep.BATTERY_IN, context(requestId = config.requestId))
        call(
            BatterySwapWire.BATTERY_SWAP,
            SimPayloads.batterySwap(
                eventType = BatterySwapWire.BATTERY_IN,
                requestId = config.requestId,
                idToken = config.idToken.idToken,
                idTokenType = config.idToken.type,
                batteries = inserted,
            ),
        )
    }

    // ------------------------------------------------------------------ EnergyTransferStarted

    /**
     * 충전 시작 보고 (Part 6 `EnergyTransferStartedBatterySwapping`).
     *
     * `eventType=Updated`, `triggerReason=ChargingStateChanged`,
     * `transactionInfo.chargingState=Charging`. 투입된 배터리마다 반복한다.
     */
    suspend fun reportChargingStarted() {
        config.insertSlots.map(::slotOf).forEach { slot ->
            faults.before(SimStep.TRANSACTION_EVENT, context(slotId = slot.config.slotId))
            call(
                BatterySwapWire.TRANSACTION_EVENT,
                SimPayloads.transactionEvent(
                    eventType = BatterySwapWire.TX_UPDATED,
                    triggerReason = BatterySwapWire.TRIGGER_REASON_CHARGING_STATE_CHANGED,
                    seqNo = nextTxSeqNo(slot),
                    transactionId = requireTransaction(slot),
                    slotId = slot.config.slotId,
                    connectorId = slot.config.connectorId,
                    at = clock.instant(),
                    chargingState = BatterySwapWire.CHARGING_STATE_CHARGING,
                    idToken = config.chargingIdToken,
                ),
            )
        }
    }

    // ------------------------------------------------------------------ EVDisconnected

    /**
     * 배터리 반출 (Part 6 `EVDisconnectedBatterySwapping`).
     *
     * 1. 슬롯 상태 변경 알림(`Available`) → 2. 응답
     * 3. `TransactionEventRequest(Ended)` → 4. 응답
     * **(반출된 배터리마다 반복)**
     * 5. `BatterySwapRequest(BatteryOut)` → 6. `BatterySwapResponse`
     *
     * **requestId 는 입고와 같은 값이다** (S02.FR.02 — 승계된다).
     */
    suspend fun removeBatteries() {
        val removed = config.dispenseSlots.map { slotId ->
            val slot = slotOf(slotId)
            val battery = checkNotNull(slot.battery) { "빈 슬롯에서 배터리를 꺼내려 한다: $slotId" }
            slotId to battery
        }

        removed.forEach { (slotId, _) ->
            val slot = slotOf(slotId)
            slot.battery = null
            reportSlotStatus(slot)
            endChargingTransaction(slot)
        }

        faults.before(SimStep.BATTERY_OUT, context(requestId = config.requestId))
        call(
            BatterySwapWire.BATTERY_SWAP,
            SimPayloads.batterySwap(
                eventType = BatterySwapWire.BATTERY_OUT,
                requestId = config.requestId,
                idToken = config.idToken.idToken,
                idTokenType = config.idToken.type,
                batteries = removed,
            ),
        )
    }

    // ------------------------------------------------------------------ 교환 1건

    /**
     * 교환 1건을 완주한다.
     *
     * **진입 순서가 `SwapOrder` 로 갈린다** (Part 6 재사용 상태의 진입 조건 그대로):
     *
     * - [SwapOrder.IN_OUT] — 인가 → 투입 → 충전 시작 → 반출
     *   (`EVConnectedPreSession` 의 선행 상태가 `Authorized`, `EVDisconnected` 의 선행 상태가
     *   `EnergyTransferStarted`)
     * - [SwapOrder.OUT_IN] — 인가 → 반출 → 투입 → 충전 시작
     *   (`EVDisconnected` 의 선행 상태가 `Authorized`, `EVConnectedPreSession` 의 선행 상태가
     *   `EVDisconnected`)
     *
     * 두 순서 모두 같은 상태 집합을 지난다. CSMS 의 상태머신은 어느 쪽인지 알 필요가 없고,
     * 알아서도 안 된다 (PLAN §4.6 — 순서 불가지론).
     */
    suspend fun runSwap() {
        authorize()
        when (config.swapOrder) {
            SwapOrder.IN_OUT -> {
                insertBatteries()
                reportChargingStarted()
                removeBatteries()
            }

            SwapOrder.OUT_IN -> {
                removeBatteries()
                insertBatteries()
                reportChargingStarted()
            }
        }
    }

    /** 부팅부터 교환 1건 완주까지. 실행 진입점이 쓰는 경로다. */
    suspend fun bootAndSwap() {
        boot()
        runSwap()
    }

    // ------------------------------------------------------------------ 관측

    /** 슬롯의 현재 상태 — **도메인 어휘로** 돌려준다. 프로토콜 어휘는 경계 밖으로 나가지 않는다. */
    fun slotState(slotId: Int): SlotState = slotOf(slotId).state

    /** 슬롯에 든 배터리. 없으면 `null`. */
    fun batteryAt(slotId: Int): SimBattery? = slotOf(slotId).battery

    /** 슬롯에서 도는 충전 트랜잭션 식별자. 교환 트랜잭션과 무관하다 (PLAN §5.1). */
    fun chargingTransactionAt(slotId: Int): String? = slotOf(slotId).transactionId

    // ------------------------------------------------------------------ 내부

    /**
     * 슬롯 상태를 알린다 (S03.FR.02/04).
     *
     * 배터리가 있으면 `Occupied` 가 나간다 — 직관과 반대다 (PLAN §4.2). 그 변환은
     * [SimPayloads] 를 거쳐 `AvailabilityState` 한 곳에서만 일어난다.
     */
    private suspend fun reportSlotStatus(slot: SimSlot) {
        faults.before(SimStep.SLOT_STATUS, context(slotId = slot.config.slotId))
        call(
            BatterySwapWire.NOTIFY_EVENT,
            SimPayloads.slotStatus(
                eventId = ++eventIdSeq,
                seqNo = notifySeqNo++,
                slotId = slot.config.slotId,
                connectorId = slot.config.connectorId,
                holdsBattery = slot.state == SlotState.HOLDS_BATTERY,
                at = clock.instant(),
            ),
        )
    }

    /**
     * 충전 트랜잭션을 연다 (S04, `TxStartPoint = EVConnected`).
     *
     * `triggerReason = CablePluggedIn`, `evse` 와 `evse.connectorId` 를 싣는다 — Part 6 의
     * Tool validation 이 그 셋을 확인한다.
     */
    private suspend fun startChargingTransaction(slot: SimSlot) {
        val transactionId = MessageIds.next()
        slot.transactionId = transactionId
        slot.txSeqNo = 0

        faults.before(SimStep.TRANSACTION_EVENT, context(slotId = slot.config.slotId))
        call(
            BatterySwapWire.TRANSACTION_EVENT,
            SimPayloads.transactionEvent(
                eventType = BatterySwapWire.TX_STARTED,
                triggerReason = BatterySwapWire.TRIGGER_REASON_CABLE_PLUGGED_IN,
                seqNo = nextTxSeqNo(slot),
                transactionId = transactionId,
                slotId = slot.config.slotId,
                connectorId = slot.config.connectorId,
                at = clock.instant(),
                chargingState = BatterySwapWire.CHARGING_STATE_EV_CONNECTED,
                idToken = config.chargingIdToken,
            ),
        )
    }

    /**
     * 충전 트랜잭션을 닫는다 (S04, `TxStopPoint = EVConnected`).
     *
     * `triggerReason = EVCommunicationLost`, `stoppedReason = EVDisconnected` (PLAN §4.10).
     */
    private suspend fun endChargingTransaction(slot: SimSlot) {
        val transactionId = requireTransaction(slot)

        faults.before(SimStep.TRANSACTION_EVENT, context(slotId = slot.config.slotId))
        call(
            BatterySwapWire.TRANSACTION_EVENT,
            SimPayloads.transactionEvent(
                eventType = BatterySwapWire.TX_ENDED,
                triggerReason = BatterySwapWire.TRIGGER_REASON_EV_COMMUNICATION_LOST,
                seqNo = nextTxSeqNo(slot),
                transactionId = transactionId,
                slotId = slot.config.slotId,
                connectorId = slot.config.connectorId,
                at = clock.instant(),
                stoppedReason = BatterySwapWire.STOPPED_REASON_EV_DISCONNECTED,
                idToken = config.chargingIdToken,
            ),
        )
        slot.transactionId = null
    }

    /**
     * CALL 을 보내고 CALLRESULT 페이로드를 받는다.
     *
     * 보내기 전에 **우리가 만든 페이로드를 공식 `<Action>Request` 스키마로 자기 검증**한다.
     * 받는 쪽도 검증하지만, 여기서 걸리면 원인이 시뮬레이터라는 사실이 그 자리에서 드러난다.
     *
     * 응답이 CALLRESULT 가 아니면 예외로 끝낸다. 시뮬레이터는 스테이션 역할이라 "거부당했다"를
     * 삼키고 계속 갈 이유가 없다 — 시나리오가 거기서 실패했다는 사실이 그대로 보여야 한다.
     */
    private suspend fun call(action: String, payload: ObjectNode): ObjectNode {
        val validation = validator.validateCall(action, payload)
        if (validation is PayloadValidation.Invalid) {
            error("시뮬레이터가 만든 ${action}Request 가 공식 스키마를 통과하지 못했다: ${validation.errorDescription}")
        }

        return when (val result = session.call(OcppCall(action, payload))) {
            is OcppResult.Accepted -> result.payload
            is OcppResult.Rejected -> error("$action 이 거부됐다: ${result.errorCode} ${result.errorDescription}")
            is OcppResult.InvalidResponse -> error("$action 응답이 스키마를 통과하지 못했다: ${result.errorDescription}")
            is OcppResult.TimedOut -> error("$action 응답이 오지 않았다: messageId=${result.messageId}")
            is OcppResult.NotConnected -> error("$action 을 보낼 연결이 없다: station=${result.stationId}")
        }
    }

    /**
     * CSMS 가 보낸 CALL 처리.
     *
     * M6 에서 CSMS 가 개시하는 메시지는 없다. S02 `RequestBatterySwap` 은 **M8** 이므로
     * 여기에 미리 만들지 않는다 — 지금 답할 수 있는 것은 "구현하지 않았다" 뿐이고,
     * 그것이 Part 4 §4.3 의 [RpcErrorCode.NotImplemented] 다.
     */
    private fun handleInboundCall(call: OcppCall): InboundResponse =
        InboundResponse.Fail(RpcErrorCode.NotImplemented, "station-sim 이 구현하지 않은 action: ${call.action}")

    private fun orderedSlots(): List<SimSlot> = slots.values.sortedBy { it.config.slotId }

    private fun slotOf(slotId: Int): SimSlot =
        slots[slotId] ?: error("없는 슬롯: $slotId (스테이션 ${config.stationId})")

    private fun requireTransaction(slot: SimSlot): String =
        checkNotNull(slot.transactionId) { "충전 트랜잭션이 없는 슬롯: ${slot.config.slotId}" }

    private fun nextTxSeqNo(slot: SimSlot): Int = slot.txSeqNo++

    private fun connectedTransport(): WebSocketTransport =
        transport ?: error("연결되지 않았다: ${config.stationId}")

    private fun context(slotId: Int? = null, requestId: Int? = null) =
        FaultContext(config.stationId, slotId, requestId)
}
