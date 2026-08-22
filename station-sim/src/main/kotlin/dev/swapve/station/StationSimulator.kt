package dev.swapve.station

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.ocpp.rpc.MessageIds
import dev.swapve.ocpp.rpc.RpcErrorCode
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.schema.PayloadValidation
import dev.swapve.ocpp.session.InMemoryOcppEventLog
import dev.swapve.ocpp.session.InboundCallLedger
import dev.swapve.ocpp.session.InboundResponse
import dev.swapve.ocpp.session.MessageDirection
import dev.swapve.ocpp.session.OcppCall
import dev.swapve.ocpp.session.OcppEventRecord
import dev.swapve.ocpp.session.OcppResult
import dev.swapve.ocpp.session.OcppSession
import dev.swapve.ocpp.session.StationSerializer
import dev.swapve.ocpp.session.TransmitOutcome
import dev.swapve.ocpp.swap.BatteryRejectionReason
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.ocpp.swap.DeviceModelVariables
import dev.swapve.ocpp.swap.VariableReading
import dev.swapve.ocpp.swap.VariableRef
import dev.swapve.ocpp.swap.VariableStatus
import dev.swapve.ocpp.swap.VariableWrite
import dev.swapve.swap.SlotState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 배터리 교환 스테이션 시뮬레이터 — IoT 쪽 역할.
 *
 * ### 이것은 시뮬레이터가 아니라 명세의 실행본이다
 *
 * 함수 하나하나가 Part 6 의 **스테이션 측 재사용 상태**에 대응한다. 시험 대상이
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
 * M9 가 S04 를 완성하며 셋이 더 붙었다:
 *
 * | 요구 | 함수 |
 * |---|---|
 * | **S04.FR.04** SoC 주기 보고 · `MaxSoc` 도달 시 `SuspendedEVSE` | [advanceCharging] · [chargeUntilMaxSoc] |
 * | **S04.FR.11** 재부팅 시 배터리 있는 EVSE 마다 트랜잭션 재개시 | [reboot] |
 * | **S04.FR.05/06/10/12** 디바이스 모델 조회·설정 | [SimDeviceModel] (`GetVariables`/`SetVariables` 응답) |
 *
 * [runSwap] 이 이들을 `SwapOrder` 에 따라 엮는다. **두 순서 모두 같은 상태 집합을 지나되
 * 진입 순서만 다르다** — Part 6 의 진입 조건을 그대로 옮기면 양방향 교환 순서가 저절로
 * 검증된다.
 *
 * ### 프로토콜을 다시 구현하지 않는다
 *
 * 프레이밍·스키마 검증·멱등·per-station 직렬화는 전부 `ocpp-core` 의 [OcppSession] 이 한다.
 * **CSMS 와 같은 모듈을 공유하는 것이 요점이다** (원칙 3) — 인코딩이 어긋나면 양쪽
 * 중 한쪽이 아니라 시험 전체가 즉시 빨개진다.
 *
 * ### 도메인 어휘로 상태를 들고, 경계에서만 바꾼다
 *
 * 슬롯 상태는 [SlotState] 로 들고 있다가 나갈 때만 `Available`/`Occupied` 로 바꾼다
 * (§6 원칙 4). 그 변환은 `ocpp-core` 의 `AvailabilityState` 한 군데에만 있다.
 *
 * ### 실제 시간을 쓰지 않는다
 *
 * 모든 시각은 [clock] 에서 오고, 모든 단계는 상대의 CALLRESULT 를 기다려 이어진다.
 * `sleep` 이 없으므로 시험이 결정적이다.
 *
 * ### 전송은 주입점이다
 *
 * 연결을 여는 일만 [openTransport] 에 맡긴다. JDK 에는 서버 쪽 WebSocket 이 없어 가짜
 * CSMS 를 세울 수 없으므로, 이 이음새가 없으면 시뮬레이터는 **실제 CSMS 를 통해서만**
 * 시험된다 — 그때 빨개진 시험은 어느 쪽이 틀렸는지 말해 주지 않는다. 이유는
 * [StationTransport] 에 적혀 있다.
 */
class StationSimulator(
    val config: StationSimConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val faults: FaultInjection = FaultInjection.None,
    /** 오간 메시지 원문이 전부 남는다. 시험이 이 로그를 읽어 계약을 확인한다. */
    val eventLog: InMemoryOcppEventLog = InMemoryOcppEventLog(),
    private val validator: OcppPayloadValidator = OcppPayloadValidator(),
    /** 연결을 여는 방법. 기본값이 실제 경로고, 시험만 이것을 갈아 끼운다. */
    private val openTransport: StationTransportFactory = StationTransportFactory.WebSocket,
) : AutoCloseable {

    /** 슬롯 하나의 현재 상태. 배터리 유무가 곧 [SlotState] 다. */
    private class SimSlot(val config: SlotConfig, var battery: SimBattery?) {
        /** 이 슬롯에서 도는 충전 트랜잭션. 배터리가 없으면 `null`. */
        var transactionId: String? = null

        /** `TransactionEvent.seqNo` — 트랜잭션 안에서 0 부터 증가한다. */
        var txSeqNo: Int = 0

        /**
         * 충전 상한에 닿아 급전이 멈췄다 (`SuspendedEVSE`, S04.FR.06).
         *
         * **트랜잭션은 살아 있다.** 멈춘 것은 에너지 흐름이지 트랜잭션이 아니다 —
         * 배터리를 꺼내갈 때 비로소 `Ended` 가 나간다 (`TxStopPoint = EVConnected`).
         */
        var chargingSuspended: Boolean = false

        val state: SlotState get() = if (battery == null) SlotState.EMPTY else SlotState.HOLDS_BATTERY
    }

    private val slots: Map<Int, SimSlot> =
        config.slots.associate { it.slotId to SimSlot(it, it.battery) }

    /**
     * 이 스테이션의 디바이스 모델.
     *
     * `BatteryCartridge.SoC`/`SoH` 를 여기 저장하지 않고 [slots] 에서 파생하도록 넘긴다 —
     * 배터리가 바뀌면 답도 따라 바뀌어야 하기 때문이다 (S04.FR.12).
     */
    private val deviceModel: SimDeviceModel = SimDeviceModel.of(config) { evseId -> slots[evseId]?.battery }

    /** 충전 상한 `BatterySwapCtrlr.MaxSoc` 의 현재 값. `SetVariables` 로 바뀌면 즉시 반영된다. */
    private val maxSoc: Double
        get() = (deviceModel.intOf(DeviceModelVariables.maxSoc()) ?: SimDeviceModel.DEFAULT_MAX_SOC).toDouble()

    private var transport: StationTransport? = null

    /**
     * [close] 가 돌았는가.
     *
     * **순서 게이트가 아니라 객체 수명이다.** 이 시뮬레이터의 다른 검사들은 전부 스테이션
     * 안의 물리적 사실을 묻고(슬롯이 비었는가, 연결이 있는가) 호출 순서는 묻지 않는다 —
     * `authorize()` 없이 `insertBatteries()` 가 통해야 F5 가 성립하기 때문이다. 이 플래그는
     * 그 축이 아니다. `AutoCloseable` 을 닫은 뒤 다시 쓰는 것을 막을 뿐이고, 같은 종류의
     * 검사가 `ControlledStation` 에도 이미 있다(`check(!detached)`).
     *
     * 막지 않으면 `close()` 뒤 `reconnect()` 가 성공해 **붙었지만 완전히 벙어리인**
     * 스테이션이 된다. 세션이 닫혀 아무것도 보내지 못하는데 `isConnected` 는 참이다.
     */
    private var closed = false

    private var eventIdSeq = 0
    private var notifySeqNo = 0

    /**
     * 이 교환을 묶는 상관 번호.
     *
     * S01 이면 스테이션이 정한 [StationSimConfig.requestId] 그대로다. S02 면 CSMS 가 보낸
     * `RequestBatterySwapRequest.requestId` 로 **덮어쓴다** — 이어지는 `BatterySwapRequest` 는
     * **같은 값을 써야 한다(SHALL)** (S02.FR.02).
     */
    private var activeRequestId: Int = config.requestId

    /** 원격 개시를 기다리는 시험이 깨어날 자리 (S02). 여러 번 열릴 수 있어 매번 새로 만든다. */
    private var remoteStart = CompletableDeferred<Int>()

    /**
     * `GetBaseReport` 를 기다리는 [reportFullInventory] 가 깨어날 자리 (B03).
     *
     * [remoteStart] 와 같은 장치다 — 인바운드 CALL 을 처리하는 자리에서 새 CALL 을 보낼 수
     * 없기 때문이다 (Part 4 §4.1.1).
     */
    private val baseReport = CompletableDeferred<Int>()

    /**
     * 세션. 멱등 원장과 직렬화기는 이 스테이션 전용이라 새로 만든다 — CSMS 쪽처럼 여러
     * 스테이션이 공유하는 자리가 아니다.
     */
    private val session = OcppSession(
        stationId = config.stationId,
        transmit = { text -> transmitOrGone(text) },
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
        check(!closed) { "이미 닫힌 시뮬레이터다: ${config.stationId}" }
        check(transport == null) { "이미 연결돼 있다: ${config.stationId}" }
        transport = openTransport.open(
            url = config.connectUrl,
            authorization = config.basicAuthorization,
        ) { text -> session.receive(text) }
    }

    val isConnected: Boolean get() = transport?.isOpen == true

    /** 협상된 서브프로토콜. `ocpp2.1` 이어야 한다 (Part 4 §3.1.2). */
    val subprotocol: String get() = connectedTransport().subprotocol

    override fun close() {
        closed = true
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
     *
     * ### 단, [SlotConfig.chargingAlreadyStarted] 인 슬롯은 예외다
     *
     * 그 슬롯의 충전은 **이 시나리오 이전에** 시작됐다. 트랜잭션 식별자만 이어받고 `Started`
     * 는 보내지 않으므로, CSMS 는 나중에 **시작을 본 적 없는 트랜잭션의 종료**를 받게 된다.
     * `TC_S_103_CSMS` 의 tx `…-1`/`…-2` 가 정확히 그것이다 (읽을 것 5).
     */
    suspend fun boot() = bootSequence(afterReboot = false)

    /**
     * ★ **S04.FR.11 — 재부팅**.
     *
     * > *"스테이션 재부팅 시, 배터리가 들어 있는 모든 EVSE 에 대해 트랜잭션을 새로 시작한다."*
     *
     * ### 진행 중이던 트랜잭션은 **종료 통보 없이 사라진다**
     *
     * 그게 요점이다. 전원이 나간 스테이션은 `TransactionEvent(Ended)` 를 보낼 기회가 없다.
     * 다시 올라온 뒤에는 **자기가 아는 사실만** 말한다 — "이 슬롯에 배터리가 있고, 지금부터
     * 충전을 시작한다". CSMS 는 `Started` 가 무더기로 오는 것을 정상으로 받아야 하고,
     * 끝나지 않은 옛 트랜잭션을 오류로 취급하면 안 된다.
     *
     * 그래서 여기서 [SimSlot.transactionId] 를 **비운 뒤** 다시 연다. 옛 식별자를 이어 쓰면
     * 같은 트랜잭션이 두 번 시작한 것이 되어 장부가 거짓이 된다.
     *
     * 연결을 끊고 다시 붙는 데는 F6 이 만든 [disconnect]/[reconnect] 를 그대로 쓴다.
     * 부팅 사유는 `LocalReset`, 보안 이벤트는 `ResetOrReboot` 다 — 전원 인가와 재시작은
     * 다른 사건이고, 표준이 그 둘을 구분할 값을 준다.
     */
    suspend fun reboot() {
        disconnect()

        // 재부팅으로 잃어버리는 것들. 슬롯의 배터리는 물리적으로 그대로 남는다.
        slots.values.forEach { slot ->
            slot.transactionId = null
            slot.txSeqNo = 0
            slot.chargingSuspended = false
        }

        reconnect()
        bootSequence(afterReboot = true)
    }

    /**
     * 부팅 시퀀스 본체.
     *
     * @param afterReboot 재부팅으로 올라오는 길인가 (S04.FR.11). `true` 면 [SlotConfig.chargingAlreadyStarted]
     *   를 **무시한다** — 그 플래그는 *"이 시나리오 이전에 시작됐다"* 는 초기 조건일 뿐이고,
     *   재부팅을 지난 뒤에는 어떤 트랜잭션도 이어지지 않기 때문이다. 식별자도 새로 발번한다.
     */
    private suspend fun bootSequence(afterReboot: Boolean) {
        faults.before(SimStep.BOOT, context())
        val bootReason =
            if (afterReboot) BatterySwapWire.BOOT_REASON_LOCAL_RESET else config.bootReason
        val response = call(
            BatterySwapWire.BOOT_NOTIFICATION,
            SimPayloads.bootNotification(config, bootReason),
        )
        val status = response.path("status").asText()
        check(status == BatterySwapWire.REGISTRATION_ACCEPTED) {
            "부팅이 거부됐다: status=$status"
        }

        orderedSlots().forEach { reportSlotStatus(it) }

        faults.before(SimStep.SECURITY_EVENT, context())
        val securityEvent = if (afterReboot) {
            BatterySwapWire.SECURITY_EVENT_RESET_OR_REBOOT
        } else {
            BatterySwapWire.SECURITY_EVENT_STARTUP
        }
        call(
            BatterySwapWire.SECURITY_EVENT_NOTIFICATION,
            SimPayloads.securityEvent(securityEvent, clock.instant()),
        )

        orderedSlots().filter { it.battery != null }.forEach { slot ->
            if (!afterReboot && slot.config.chargingAlreadyStarted) {
                // 이 트랜잭션의 시작은 CSMS 가 본 적이 없다. 식별자만 이어받는다.
                slot.transactionId = slot.config.chargingTransactionId
                slot.txSeqNo = 0
            } else {
                // 재부팅이면 **새 트랜잭션**이다. 고정 식별자를 이어 쓰지 않는다.
                startChargingTransaction(slot, transactionId = if (afterReboot) MessageIds.newId() else null)
            }
        }
    }

    // ------------------------------------------------------------------ AuthorizedBatterySwapping

    /**
     * S01 로컬 인가 (Part 6 `AuthorizedBatterySwapping` 방식 A).
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
     * (결정 #6).
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

        faults.before(SimStep.BATTERY_IN, context(requestId = activeRequestId))
        call(
            BatterySwapWire.BATTERY_SWAP,
            SimPayloads.batterySwap(
                eventType = BatterySwapWire.BATTERY_IN,
                requestId = activeRequestId,
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

    // ------------------------------------------------------------------ 충전 진행 (S04.FR.04)

    /**
     * ★ **SoC 주기 보고 한 번** (S04.FR.04).
     *
     * 슬롯의 배터리 SoC 를 [byPercent] 만큼 올리고 `TransactionEvent(Updated)` 에 measurand
     * `SoC` 를 실어 보낸다. 상한은 `BatterySwapCtrlr.MaxSoc` 이다 (S04.FR.06).
     *
     * ### `chargingState` 를 싣지 않는다
     *
     * 스키마가 그 필드를 두고 *"is required when state **has changed**"* 라고 적는다. 주기
     * 보고는 상태가 바뀌어서 보내는 것이 아니다 — 여전히 `Charging` 이다. 매번 실어 보내면
     * "상태가 바뀌었다"는 신호를 매번 거짓으로 내는 셈이다. 그래서 `triggerReason` 도
     * `ChargingStateChanged` 가 아니라 `MeterValuePeriodic` 이다.
     *
     * ### 상한에 닿으면 `SuspendedEVSE` 로 가되 **트랜잭션은 살아 있다**
     *
     * 그때만 상태가 실제로 바뀌므로 `chargingState = SuspendedEVSE` 를 실은 사건이 한 번 더
     * 나간다 (`triggerReason = EnergyLimitReached`). **여기서 트랜잭션을 닫지 않는다** —
     * 배터리는 아직 슬롯에 꽂혀 있고, 종료는 누군가 꺼내갈 때다 (`TxStopPoint = EVConnected`,
     * S04.FR.09). 이미 멈춘 슬롯을 다시 부르면 아무것도 보내지 않는다.
     *
     * ### 시각은 주입된 시계에서 온다
     *
     * `sleep` 도 벽시계 조회도 없다. 시험은 [clock] 을 밀어서 "10분 뒤"를 만든다 (M4 방식).
     *
     * @return 보고한 SoC. 상한에 닿았으면 상한값이다.
     */
    suspend fun advanceCharging(slotId: Int, byPercent: Double): Double {
        require(byPercent > 0) { "충전은 앞으로만 간다: $byPercent" }
        val slot = slotOf(slotId)
        val battery = checkNotNull(slot.battery) { "빈 슬롯은 충전되지 않는다: $slotId" }
        if (slot.chargingSuspended) return battery.soC

        val limit = maxSoc
        // 이미 상한을 넘겨 꽂힌 배터리도 있다 (다 충전된 배터리를 다시 넣은 경우). 그때
        // 상한으로 **끌어내리지 않는다** — 충전은 SoC 를 낮추지 않고, 관측값을 고치는 것은
        // 거짓이다. 급전할 것이 없으니 곧바로 멈춘다.
        if (battery.soC >= limit) {
            suspendCharging(slot)
            return battery.soC
        }

        val next = minOf(battery.soC + byPercent, limit)
        // 배터리 자체의 SoC 를 올린다. `BatteryCartridge.SoC` 조회도, 나중에 이 배터리가
        // 반출될 때의 `batteryData.soC` 도 같은 값을 봐야 한다 — 사실이 두 벌이면 하나는 거짓이다.
        slot.battery = battery.copy(soC = next)

        faults.before(SimStep.TRANSACTION_EVENT, context(slotId = slotId))
        call(
            BatterySwapWire.TRANSACTION_EVENT,
            SimPayloads.transactionEvent(
                eventType = BatterySwapWire.TX_UPDATED,
                triggerReason = BatterySwapWire.TRIGGER_REASON_METER_VALUE_PERIODIC,
                seqNo = nextTxSeqNo(slot),
                transactionId = requireTransaction(slot),
                slotId = slotId,
                connectorId = slot.config.connectorId,
                at = clock.instant(),
                idToken = config.chargingIdToken,
                socPercent = next,
            ),
        )

        if (next >= limit) suspendCharging(slot)
        return next
    }

    /**
     * 상한에 닿을 때까지 [stepPercent] 씩 충전한다.
     *
     * 걸음마다 [advanceCharging] 이 사건 하나를 보내므로, CSMS 쪽에는 SoC 가 **올라간 자취**가
     * 남는다. 마지막에 `SuspendedEVSE` 가 한 번 나가고 트랜잭션은 열린 채로 남는다.
     *
     * @return 보고한 SoC 들, 순서대로.
     */
    suspend fun chargeUntilMaxSoc(slotId: Int, stepPercent: Double): List<Double> {
        val readings = mutableListOf<Double>()
        while (!slotOf(slotId).chargingSuspended) {
            readings += advanceCharging(slotId, stepPercent)
        }
        return readings
    }

    /** 이 슬롯의 충전이 상한에 닿아 멈췄는가 (`SuspendedEVSE`). 트랜잭션과는 무관하다. */
    fun isChargingSuspended(slotId: Int): Boolean = slotOf(slotId).chargingSuspended

    /**
     * 상한 도달 — `Updated(EnergyLimitReached, SuspendedEVSE)`.
     *
     * **트랜잭션은 유지된다** (표의 4행). 닫으면 나중에 반출될 때 종료할 대상이
     * 없어 장부가 허공에서 끝난다.
     */
    private suspend fun suspendCharging(slot: SimSlot) {
        slot.chargingSuspended = true

        faults.before(SimStep.TRANSACTION_EVENT, context(slotId = slot.config.slotId))
        call(
            BatterySwapWire.TRANSACTION_EVENT,
            SimPayloads.transactionEvent(
                eventType = BatterySwapWire.TX_UPDATED,
                triggerReason = BatterySwapWire.TRIGGER_REASON_ENERGY_LIMIT_REACHED,
                seqNo = nextTxSeqNo(slot),
                transactionId = requireTransaction(slot),
                slotId = slot.config.slotId,
                connectorId = slot.config.connectorId,
                at = clock.instant(),
                chargingState = BatterySwapWire.CHARGING_STATE_SUSPENDED_EVSE,
                idToken = config.chargingIdToken,
                socPercent = slot.battery?.soC,
            ),
        )
    }

    // ------------------------------------------------------------------ EVDisconnected

    /**
     * 배터리 반출 (Part 6 `EVDisconnectedBatterySwapping`).
     *
     * 1. `TransactionEventRequest(Ended)` → 2. 응답
     * 3. 슬롯 상태 변경 알림(`Available`) → 4. 응답
     * **(반출된 배터리마다 반복)**
     * 5. `BatterySwapRequest(BatteryOut)` → 6. `BatterySwapResponse`
     *
     * ### 투입과 순서가 뒤집혀 있다 — 그게 스펙이다
     *
     * 투입은 *슬롯 알림 → 트랜잭션 시작* 이고 (`TC_S_103_CSMS` step 3→5), 반출은
     * *트랜잭션 종료 → 슬롯 알림* 이다 (step 13→15). 대칭처럼 보이려고 맞추면 스펙과
     * 달라진다. 배터리가 있어야 충전이 시작되고, 충전이 끝나야 배터리가 빠진다 — 물리적
     * 인과와도 맞는다.
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
            endChargingTransaction(slot)
            slot.battery = null
            reportSlotStatus(slot)
        }

        faults.before(SimStep.BATTERY_OUT, context(requestId = activeRequestId))
        call(
            BatterySwapWire.BATTERY_SWAP,
            SimPayloads.batterySwap(
                eventType = BatterySwapWire.BATTERY_OUT,
                requestId = activeRequestId,
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
     * 알아서도 안 된다 (순서 불가지론).
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

    // ------------------------------------------------------------------ S02 원격 개시

    /**
     * CSMS 가 보낸 `RequestBatterySwapRequest` 를 받아들일 때까지 기다리고, 그 상관 번호를 돌려준다.
     *
     * `Rejected` 로 답한 요청은 여기를 깨우지 않는다 — 교환이 열리지 않았기 때문이다
     * (`TC_S_102_CSMS`).
     */
    suspend fun awaitRemoteStart(): Int = remoteStart.await()

    /**
     * 원격 개시로 시작하는 교환 1건 (`TC_S_103_CSMS` step 2 이후).
     *
     * S01 의 [runSwap] 과 달리 **[authorize] 를 부르지 않는다.** 인가는 CSMS 가 이미 했고,
     * 스테이션은 그 결과를 `Accepted` 로 답한 것으로 끝났다 (S02).
     */
    suspend fun runRemoteSwap() {
        awaitRemoteStart()
        when (config.swapOrder) {
            SwapOrder.IN_OUT -> {
                insertBatteries()
                removeBatteries()
            }

            SwapOrder.OUT_IN -> {
                removeBatteries()
                insertBatteries()
            }
        }
    }

    // ------------------------------------------------------------------ 실패 시나리오

    /**
     * F2 — 제공된 배터리를 이용자가 **꺼내가지 않았다** (S03.FR.06).
     *
     * `BatterySwapRequest(BatteryOutTimeout)` 하나를 보낸다. 슬롯 상태도 충전 트랜잭션도
     * 건드리지 않는다 — **배터리는 아직 스테이션 안에 있다.**
     *
     * > *"Situation needs to be reported, because CSMS ends up with an **orphan BatteryIn for
     * > which a BatteryOut is missing**."*
     *
     * `batteryData` 를 싣지 않는다. 스키마상 `1..*` 라 **한 개는 있어야 하므로**, 꺼내가지
     * 않은 그 배터리를 그대로 싣는다 — 어느 배터리가 orphan 인지가 CSMS 에 남아야 보상이 된다.
     */
    suspend fun reportBatteryOutTimeout() {
        val pending = config.dispenseSlots.mapNotNull { slotId ->
            slotOf(slotId).battery?.let { slotId to it }
        }
        check(pending.isNotEmpty()) { "꺼내가지 않은 배터리가 없다: ${config.dispenseSlots}" }

        faults.before(SimStep.BATTERY_OUT_TIMEOUT, context(requestId = activeRequestId))
        call(
            BatterySwapWire.BATTERY_SWAP,
            SimPayloads.batterySwap(
                eventType = BatterySwapWire.BATTERY_OUT_TIMEOUT,
                requestId = activeRequestId,
                idToken = config.idToken.idToken,
                idTokenType = config.idToken.type,
                batteries = pending,
            ),
        )
    }

    /**
     * F4·F6 — 마지막으로 보낸 `BatterySwapRequest` 를 **다시 보낸다.**
     *
     * ### 두 시나리오는 같은 행위가 아니다. messageId 하나가 가른다
     *
     * - `sameMessageId = true` → **F6 재접속 재전송.** 스테이션이 응답을 못 받았다고 판단해
     *   같은 프레임을 그대로 다시 보내는 상황이다. M4 의 [InboundCallLedger] 가
     *   `(stationId, messageId)` 로 이것을 잡아 **저장된 응답을 그대로 회신**하고 상위 계층을
     *   부르지 않는다. 장부는 늘지 않는다.
     * - `sameMessageId = false` → **F4 중복 `BatteryIn`.** 새 messageId 라 멱등 원장은
     *   그냥 통과시킨다. 여기서는 **M3 상태머신**이 같은 `(stationId, requestId)` 의 두 번째
     *   입고를 `DUPLICATE_BATTERY_IN` 으로 무시한다. 두 겹의 멱등이 각각 도는지를 갈라 본다.
     *
     * 같은 messageId 로 보낼 때는 [OcppSession] 을 지나지 않고 전송으로 직행한다. 세션은
     * 발신 messageId 를 언제나 새로 발번하므로(Part 4 §4.1.4) 그 경로로는 재전송을 만들 수
     * 없고, 만들 수 있게 고치면 "우리가 보내는 모든 CALL 은 유일하다"는 M4 의 계약이 흐려진다.
     * 재전송은 **스테이션이 하는 일**이므로 스테이션 쪽 전송에 붙이는 것이 맞다.
     */
    suspend fun resendLastBatterySwap(sameMessageId: Boolean) {
        val record = lastOutboundBatterySwap()

        if (!sameMessageId) {
            val payload = MAPPER.readTree(record.payload).get(PAYLOAD_INDEX) as ObjectNode
            call(BatterySwapWire.BATTERY_SWAP, payload)
            return
        }

        // 세션을 지나지 않으므로 응답을 기다려 줄 pending 도 없다. 그렇다고 보내고 바로
        // 돌아가면 호출자는 **응답이 왔는지 모르는 채로** 다음 단언을 하게 된다 — 재전송이
        // CSMS 에 닿지 않아도 통과하는 시험이 된다. 그래서 회신이 이벤트 로그에 나타날
        // 때까지 기다린다.
        val before = repliesTo(record.messageId)
        connectedTransport().send(record.payload)
        withTimeout(RESEND_REPLY_TIMEOUT) {
            while (repliesTo(record.messageId) <= before) delay(RESEND_POLL_INTERVAL)
        }
    }

    /** 이 messageId 로 우리에게 되돌아온 프레임 수. 재전송이 실제로 응답됐는지 세는 용도다. */
    fun repliesTo(messageId: String): Int =
        eventLog.of(config.stationId)
            .count { it.direction == MessageDirection.INBOUND && it.messageId == messageId }

    /**
     * F6 준비 — 연결만 끊는다. 세션·슬롯·교환 상태는 그대로 둔다.
     *
     * [close] 와 다르다. `close()` 는 세션까지 닫아 이 시뮬레이터를 끝내지만, 재접속
     * 시나리오는 **같은 스테이션이 다시 붙는 것**이라 슬롯과 상관 번호가 이어져야 한다.
     */
    fun disconnect() {
        transport?.close()
        transport = null
    }

    /** 끊긴 뒤 다시 붙는다. 멱등 원장은 CSMS 쪽에 남아 있으므로 재전송이 그대로 잡힌다. */
    suspend fun reconnect() {
        check(!closed) { "이미 닫힌 시뮬레이터다: ${config.stationId}" }
        check(transport == null) { "아직 끊기지 않았다: ${config.stationId}" }
        transport = openTransport.open(
            url = config.connectUrl,
            authorization = config.basicAuthorization,
        ) { text -> session.receive(text) }
    }

    /** 우리가 마지막으로 내보낸 `BatterySwap` CALL 의 기록. 원문이 그대로 남아 있다. */
    private fun lastOutboundBatterySwap(): OcppEventRecord =
        eventLog.of(config.stationId)
            .lastOrNull {
                it.direction == MessageDirection.OUTBOUND && it.action == BatterySwapWire.BATTERY_SWAP
            }
            ?: error("보낸 BatterySwap 이 없다: ${config.stationId}")

    // ------------------------------------------------------------------ 관측

    /** 슬롯의 현재 상태 — **도메인 어휘로** 돌려준다. 프로토콜 어휘는 경계 밖으로 나가지 않는다. */
    fun slotState(slotId: Int): SlotState = slotOf(slotId).state

    /** 슬롯에 든 배터리. 없으면 `null`. */
    fun batteryAt(slotId: Int): SimBattery? = slotOf(slotId).battery

    /** 슬롯에서 도는 충전 트랜잭션 식별자. 교환 트랜잭션과 무관하다. */
    fun chargingTransactionAt(slotId: Int): String? = slotOf(slotId).transactionId

    // ------------------------------------------------------------------ 내부

    /**
     * 슬롯 상태를 알린다 (S03.FR.02/04).
     *
     * 배터리가 있으면 `Occupied` 가 나간다 — 직관과 반대다. 그 변환은
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
    private suspend fun startChargingTransaction(slot: SimSlot, transactionId: String? = null) {
        // 식별자가 고정된 슬롯이면 그 값을 쓴다 — 적합성 시험이 스펙 원문의 tx 를 그대로
        // 쓰기 위한 자리다. 아니면 발번한다 (로컬 카운터 금지).
        val newTransactionId = transactionId ?: slot.config.chargingTransactionId ?: MessageIds.newId()
        slot.transactionId = newTransactionId
        slot.txSeqNo = 0
        slot.chargingSuspended = false

        faults.before(SimStep.TRANSACTION_EVENT, context(slotId = slot.config.slotId))
        call(
            BatterySwapWire.TRANSACTION_EVENT,
            SimPayloads.transactionEvent(
                eventType = BatterySwapWire.TX_STARTED,
                triggerReason = BatterySwapWire.TRIGGER_REASON_CABLE_PLUGGED_IN,
                seqNo = nextTxSeqNo(slot),
                transactionId = newTransactionId,
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
     * ### ★ 세 값을 각각 싣는다 (M7 에서 스펙 원문과 대조해 정정했다)
     *
     * - `triggerReason = EnergyLimitReached` — 왜 끝났나
     * - `stoppedReason = EVDisconnected` — 무엇이 끊겼나
     * - `chargingState = Idle` — 끝난 뒤의 상태
     *
     * Part 6 `TC_S_103_CSMS` step 13/17 원문이 셋을 **각각** 요구한다. M6 은 `triggerReason`
     * 자리에 `EVCommunicationLost` 를 적었는데 그건 스펙 근거 없는 추정이었고, `chargingState`
     * 는 아예 빠져 있었다. 셋 다 enum 으로는 유효한 값이라 **스키마 검증으로는 잡히지 않는다.**
     */
    private suspend fun endChargingTransaction(slot: SimSlot) {
        val transactionId = requireTransaction(slot)

        faults.before(SimStep.TRANSACTION_EVENT, context(slotId = slot.config.slotId))
        call(
            BatterySwapWire.TRANSACTION_EVENT,
            SimPayloads.transactionEvent(
                eventType = BatterySwapWire.TX_ENDED,
                triggerReason = BatterySwapWire.TRIGGER_REASON_ENERGY_LIMIT_REACHED,
                seqNo = nextTxSeqNo(slot),
                transactionId = transactionId,
                slotId = slot.config.slotId,
                connectorId = slot.config.connectorId,
                at = clock.instant(),
                chargingState = BatterySwapWire.CHARGING_STATE_IDLE,
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
     * M7 부터 [BatterySwapWire.REQUEST_BATTERY_SWAP] 하나를 답한다 (S02). 나머지는 여전히
     * Part 4 §4.3 의 [RpcErrorCode.NotImplemented] 다 — 답할 수 있는 것이 그것뿐일 때
     * 있는 척하지 않는다.
     */
    private suspend fun handleInboundCall(call: OcppCall): InboundResponse = when (call.action) {
        BatterySwapWire.REQUEST_BATTERY_SWAP -> requestBatterySwap(call.payload)
        BatterySwapWire.GET_VARIABLES -> getVariables(call.payload)
        BatterySwapWire.SET_VARIABLES -> setVariables(call.payload)
        BatterySwapWire.GET_BASE_REPORT -> getBaseReport(call.payload)
        else -> InboundResponse.Fail(
            RpcErrorCode.NotImplemented,
            "station-sim 이 구현하지 않은 action: ${call.action}",
        )
    }

    /**
     * `GetVariables` — 디바이스 모델을 읽어 답한다 (S04.FR.12).
     *
     * 항목마다 **독립적으로** 판정한다. 하나가 `UnknownVariable` 이어도 나머지는 정상
     * 응답한다 — 스키마의 `getVariableResult` 가 요청 항목과 1:1 인 배열이고, 전체를 실패로
     * 만들 자리가 없다.
     */
    private fun getVariables(payload: ObjectNode): InboundResponse {
        val readings = payload.path("getVariableData").map { item ->
            val ref = VariableRef.read(item)
                // 스키마가 component.name / variable.name 을 필수로 두므로 여기 올 수 없다.
                // 그래도 터뜨리지 않고 "모르는 컴포넌트"로 답한다 — 응답 배열의 길이는 맞아야 한다.
                ?: return@map VariableReading(
                    UNREADABLE_REF,
                    VariableStatus.UNKNOWN_COMPONENT,
                    reasonCode = SimDeviceModel.REASON_UNKNOWN_COMPONENT,
                )
            deviceModel.read(ref)
        }
        return InboundResponse.Respond(SimPayloads.getVariablesResponse(readings))
    }

    /**
     * `SetVariables` — 값을 바꾸고 그 결과를 답한다 (S04.FR.06/10).
     *
     * ### ★ 거부는 여기서 난다
     *
     * `MaxSoc ≥ TargetSoC` 를 어기는 설정은 `attributeStatus = Rejected` 로 돌아간다.
     * **판정 주체가 스테이션인 근거**는 `ocpp-core` 의 `DeviceModelVariables` KDoc 에
     * 적어 두었다 — 표준이 판정의 자리를 `SetVariableResultType.attributeStatus` 하나로
     * 정했고, 디바이스 모델의 소유자가 스테이션이기 때문이다.
     */
    private fun setVariables(payload: ObjectNode): InboundResponse {
        val results = payload.path("setVariableData").map { item ->
            val ref = VariableRef.read(item)
                ?: return@map VariableWrite(
                    UNREADABLE_REF,
                    VariableStatus.UNKNOWN_COMPONENT,
                    reasonCode = SimDeviceModel.REASON_UNKNOWN_COMPONENT,
                )
            deviceModel.write(ref, item.path("attributeValue").asText())
        }
        return InboundResponse.Respond(SimPayloads.setVariablesResponse(results))
    }

    /**
     * ★ **`GetBaseReport`** — 청을 받아들이고, 보고 자체는 [reportFullInventory] 가 나른다
     * (B03, `TC_S_104_CS`).
     *
     * ### 여기서 `NotifyReport` 를 보내지 않는다
     *
     * [requestBatterySwap] 과 **정확히 같은 이유**다: 아직 응답을 내지 않은 채 새 CALL 을
     * 보내면 연결당 in-flight CALL 하나 규칙(Part 4 §4.1.1)과 어긋난다. 그래서 상관 번호만
     * [baseReport] 에 담아 두고, 그것을 기다리던 쪽이 이어받는다.
     *
     * ### `FullInventory` 만 받아들인다
     *
     * `ConfigurationInventory` / `SummaryInventory` 는 "설정 가능한 것만" · "요약만" 이라는
     * **다른 목록**이다. 같은 전체 목록을 세 이름으로 답하면 그건 세 종류를 지원하는 것이
     * 아니라 두 개를 잘못 답하는 것이라, 지원하지 않는다고 말한다.
     */
    private fun getBaseReport(payload: ObjectNode): InboundResponse {
        val reportBase = payload.path("reportBase").asText()
        if (!reportBase.equals(BatterySwapWire.REPORT_BASE_FULL_INVENTORY, ignoreCase = true)) {
            return InboundResponse.Respond(
                SimPayloads.getBaseReportResponse(
                    accepted = false,
                    reasonCode = REASON_UNSUPPORTED_REPORT_BASE,
                    additionalInfo = "이 스테이션이 만들 수 있는 보고는 FullInventory 뿐이다: $reportBase",
                ),
            )
        }

        if (!baseReport.isCompleted) baseReport.complete(payload.path("requestId").asInt())
        return InboundResponse.Respond(SimPayloads.getBaseReportResponse(accepted = true))
    }

    /**
     * ★ **디바이스 모델 전체를 `NotifyReport` 여러 건으로 나눠 보고한다** (B03, `TC_S_104_CS`).
     *
     * [getBaseReport] 가 청을 받아들일 때까지 기다렸다가, 그 `requestId` 를 **그대로 되돌리며**
     * 목록을 [REPORT_PAGE_SIZE] 씩 잘라 보낸다. `seqNo` 는 0 부터 오르고 마지막 조각만
     * `tbc` 가 없다.
     *
     * @return 보고에 실은 상관 번호. 청한 쪽의 것이다.
     */
    suspend fun reportFullInventory(): Int {
        val requestId = baseReport.await()
        val pages = deviceModel.fullInventory().chunked(REPORT_PAGE_SIZE)
        check(pages.isNotEmpty()) { "보고할 변수가 하나도 없다 — 디바이스 모델이 비어 있을 수 없다" }

        pages.forEachIndexed { index, page ->
            call(
                BatterySwapWire.NOTIFY_REPORT,
                SimPayloads.notifyReport(
                    requestId = requestId,
                    seqNo = index,
                    tbc = index != pages.lastIndex,
                    at = clock.instant(),
                    readings = page,
                ),
            )
        }
        return requestId
    }

    /**
     * S02 원격 개시 요청을 판정한다 (Part 6 `AuthorizedBatterySwapping` 방식 B).
     *
     * ### ★ 재고 판정은 여기서 한다
     *
     * **S02.FR.04**: 배터리가 부족하면 **스테이션이** `Rejected` +
     * `statusInfo.reasonCode = NoBatteryAvailable` 로 답한다. CSMS 는 재고를 모른다.
     * 그것이 `TC_S_102_CSMS` 가 시험하는 전부이며, 이 함수가 시험 대상 CSMS 를 향해
     * **시험계 역할**로 그 답을 낸다.
     *
     * ### 여기서 교환 시퀀스를 진행하지 않는다
     *
     * 이 함수는 응답 하나만 정하고 돌아간다. 이어지는 슬롯 알림·`TransactionEvent`·
     * `BatterySwap` 을 여기서 보내면, **응답을 아직 내지 않은 채 새 CALL 을 보내는 것**이라
     * 연결당 in-flight CALL 하나 규칙(Part 4 §4.1.1)과 어긋난다. 대신 [remoteStart] 를
     * 완료시켜 [awaitRemoteStart] 를 기다리던 쪽이 이어받게 한다.
     */
    private suspend fun requestBatterySwap(payload: ObjectNode): InboundResponse {
        val requestId = payload.path("requestId").asInt()
        faults.before(SimStep.REQUEST_BATTERY_SWAP, context(requestId = requestId))

        val available = config.dispenseSlots.count { slotOf(it).battery != null }
        if (available == 0 || available < config.dispenseSlots.size) {
            // 내줄 배터리가 없다. 상관 번호를 승계하지 않는다 — 열리지 않은 교환이다.
            return InboundResponse.Respond(
                SimPayloads.requestBatterySwapResponse(
                    accepted = false,
                    reason = BatteryRejectionReason.NO_BATTERY_AVAILABLE,
                    additionalInfo = "교환에 내줄 배터리가 없다 (가용 $available 개)",
                ),
            )
        }

        // S02.FR.02 — 이어지는 BatterySwapRequest 는 **같은 requestId 를 써야 한다(SHALL)**.
        activeRequestId = requestId
        if (!remoteStart.isCompleted) remoteStart.complete(requestId)

        return InboundResponse.Respond(SimPayloads.requestBatterySwapResponse(accepted = true))
    }

    private fun orderedSlots(): List<SimSlot> = slots.values.sortedBy { it.config.slotId }

    private fun slotOf(slotId: Int): SimSlot =
        slots[slotId] ?: error("없는 슬롯: $slotId (스테이션 ${config.stationId})")

    private fun requireTransaction(slot: SimSlot): String =
        checkNotNull(slot.transactionId) { "충전 트랜잭션이 없는 슬롯: ${slot.config.slotId}" }

    private fun nextTxSeqNo(slot: SimSlot): Int = slot.txSeqNo++

    /**
     * 전송이 있으면 보내고, 없거나 실패하면 [TransmitOutcome.Gone] 이다.
     *
     * **던지지 않는다.** 끊긴 채로 보내려는 것은 이 시뮬레이터에서 정상 사건이다 — F6 은
     * 일부러 끊고, 사람은 콘솔에서 아무 때나 조작을 누른다. 예외로 알리면 `OcppSession.call`
     * 이 "모든 결과를 값으로 돌려준다"는 약속을 지킬 수 없다.
     */
    private suspend fun transmitOrGone(text: String): TransmitOutcome {
        val transport = this.transport ?: return TransmitOutcome.Gone("연결되지 않았다: ${config.stationId}")
        return try {
            transport.send(text)
            TransmitOutcome.Delivered
        } catch (failure: Exception) {
            TransmitOutcome.Gone("전송이 실패했다: ${config.stationId} — ${failure.message ?: failure.toString()}")
        }
    }

    private fun connectedTransport(): StationTransport =
        transport ?: error("연결되지 않았다: ${config.stationId}")

    private fun context(slotId: Int? = null, requestId: Int? = null) =
        FaultContext(config.stationId, slotId, requestId)

    private companion object {
        /**
         * 스키마를 통과했는데도 `component`/`variable` 을 읽지 못했을 때 답에 실을 자리 표시.
         *
         * 실제로는 발생하지 않는다 — 두 필드 다 스키마 필수다. 응답 배열의 항목 수가 요청과
         * 어긋나지 않게 하려고만 존재한다.
         */
        val UNREADABLE_REF = VariableRef(component = "Unknown", variable = "Unknown")

        /**
         * ★ **`NotifyReport` 한 건에 싣는 변수의 최대 개수** (B03).
         *
         * 실제 스테이션이 나누는 이유는 프레임 크기 한계지만, 여기서 작은 값을 쓰는 이유는
         * 다르다: **분할이 언제나 실제로 일어나게 하려는 것**이다. 목록이 다 들어가는 크기를
         * 쓰면 `tbc`·`seqNo` 경로가 시험에서 한 번도 실행되지 않고, 그러면 "구현했다"가
         * 확인되지 않은 주장으로 남는다 (`TC_S_104_CS`).
         */
        const val REPORT_PAGE_SIZE = 3

        /** `statusInfo.reasonCode` 는 20자 제한이다 (공식 스키마). */
        const val REASON_UNSUPPORTED_REPORT_BASE = "UnsupportedBase"

        /** 재전송할 프레임에서 페이로드를 꺼낼 때만 쓴다. `[2,"id","Action",{payload}]`. */
        val MAPPER = ObjectMapper()
        const val PAYLOAD_INDEX = 3

        /** 재전송에 대한 회신을 기다리는 한도. 넘으면 재전송이 닿지 않은 것이다. */
        val RESEND_REPLY_TIMEOUT = 10.seconds
        val RESEND_POLL_INTERVAL = 5.milliseconds
    }
}
