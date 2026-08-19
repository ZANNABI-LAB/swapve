package dev.swapve.console

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.ocpp.session.MessageDirection
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.station.FaultInjection
import dev.swapve.station.SimBattery
import dev.swapve.station.SimStep
import dev.swapve.station.SimulatedFault
import dev.swapve.station.SlotConfig
import dev.swapve.station.StationSimConfig
import dev.swapve.station.StationSimulator
import dev.swapve.station.SwapOrder
import dev.swapve.swap.IdToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 제어 실패를 HTTP 상태로 그대로 옮기는 오류.
 *
 * 상태 코드를 문자열 판별로 되짚지 않으려고 예외에 실어 둔다 — "없는 스테이션(404)"과
 * "이미 붙은 스테이션(409)"은 서버가 아니라 **여기서** 갈리는 구분이다.
 */
class ControlError(val status: Int, message: String) : RuntimeException(message)

/** 콘솔이 아는 스테이션 하나의 진행 상태. 화면의 배지가 이 값이다. */
enum class SwapProgress {

    /** 붙어서 부팅까지 끝났다. 교환은 아직 시작하지 않았다 */
    ATTACHED,

    /** 교환(또는 장애 시나리오) 시퀀스가 돌고 있다 */
    RUNNING,

    /** ★ CSMS 가 `RequestBatterySwap` 을 보내 주기를 기다린다 (S02). F1 이 여기 머문다 */
    AWAITING_REMOTE_START,

    /** 시퀀스가 끝까지 갔다 */
    COMPLETED,

    /** 스테이션이 개시를 거부했다 (F1 — 내줄 배터리가 없다) */
    REJECTED,

    /** 시퀀스가 예외로 끝났다. 사유는 `error` 에 있다 */
    FAILED,
}

/**
 * 화면의 장애 주입 버튼 여섯 개 (PLAN §5.4).
 *
 * ### 재현 수단이 두 갈래다 — 그게 이 열거형이 하는 일의 절반이다
 *
 * `FaultInjection` 의 KDoc 이 적어 둔 그대로다. **F1·F3 은 구성으로 재현된다** — 내줄
 * 배터리를 두지 않거나, CSMS 가 모르는 일련번호를 든 배터리를 넣으면 그만이다. 나머지는
 * 시퀀스 진입점(`reportBatteryOutTimeout` 등)과 주입 훅으로 재현된다.
 *
 * 그래서 콘솔은 **교환을 시작할 때마다 시뮬레이터를 새로 만든다** ([ControlledStation.start]).
 * 그러면 "구성으로 재현되는 시나리오"와 "시퀀스로 재현되는 시나리오"가 호출자에게 같은
 * 모양이 된다 — 버튼 하나가 곧 요청 하나다.
 *
 * @param title PLAN §5.4 표의 이름. 화면에 **그대로** 적힌다 — 버튼만 있고 뜻을 모르면
 *   데모가 되지 않는다.
 * @param expectation 걸었을 때 무엇이 일어나는가. 같은 표의 "정의된 처리"다.
 */
enum class FaultScenario(val title: String, val expectation: String) {

    F1(
        "배터리 부족",
        "스테이션이 Rejected + NoBatteryAvailable 로 답한다. CSMS 가 기록한다 " +
            "(개시가 CSMS 쪽이라 이 시나리오는 RequestBatterySwap 을 기다린다)",
    ),
    F2("수령 타임아웃", "BatteryOutTimeout 수신 → CSMS 가 OUT_TIMED_OUT 으로 영속 기록"),
    F3("미등록 배터리", "CSMS 가 BatterySwapResponse.customData 로 Rejected/BatteryUnknown"),
    F4("중복 BatteryIn", "같은 (stationId, requestId) 재수신 → 상태머신이 멱등 무시"),
    F5("순서 위반", "AUTHORIZED 없이 BatterySwap 도착 → 이상 이벤트 기록 (응답은 정상 회신)"),
    F6("재접속 중 재전송", "재연결 후 같은 messageId 로 CALL 재전송 → 멱등 처리, 장부 무결"),
}

/**
 * 화면에서 받은 스테이션 한 대의 구성.
 *
 * 기본값은 `StationSimCli` 와 **같은 모양**이다 — 앞의 `setSize` 개 슬롯은 비어 있고(투입
 * 대상), 그다음 `setSize` 개에는 내줄 배터리가 있다. 일련번호도 같은 규칙으로 짓는다:
 * 그래야 `application.yml` 의 `known-battery-serials` 에 이미 있는 값이 되어, CLI 로 돌리던
 * 것과 화면으로 돌리는 것이 **같은 결과**를 낸다.
 */
data class StationSpec(
    val csmsUrl: String,
    val stationId: String,
    val slotCount: Int = 4,
    val setSize: Int = 2,
    val swapOrder: SwapOrder = SwapOrder.IN_OUT,
    val idToken: IdToken = IdToken("RFID-0001", "ISO14443"),
    val requestId: Int = 1001,
) {
    init {
        require(stationId.isNotBlank()) { "stationId 가 비어 있다" }
        require(csmsUrl.isNotBlank()) { "csmsUrl 이 비어 있다" }
        require(setSize >= 1) { "한 번에 오가는 배터리는 1개 이상이어야 한다: $setSize" }
        require(slotCount >= setSize * 2) {
            "슬롯이 부족하다: 투입 $setSize 개 + 반출 $setSize 개 = ${setSize * 2} 개가 필요한데 $slotCount 개다"
        }
    }

    /** 이 시나리오로 돌릴 시뮬레이터 구성. [fault] 가 구성을 바꾸는 종류면 여기서 갈린다. */
    fun config(requestId: Int, fault: FaultScenario?): StationSimConfig {
        // F1 — 내줄 배터리가 하나도 없는 스테이션. 재고 판정은 스테이션이 한다 (S02.FR.04).
        if (fault == FaultScenario.F1) {
            return StationSimConfig(
                csmsUrl = csmsUrl,
                stationId = stationId,
                slots = (1..slotCount).map { SlotConfig(slotId = it) },
                idToken = idToken,
                requestId = requestId,
                insertSlots = emptyList(),
                dispenseSlots = emptyList(),
                incomingBatteries = emptyList(),
                swapOrder = swapOrder,
            )
        }

        val insertSlots = (1..setSize).toList()
        val dispenseSlots = (setSize + 1..setSize * 2).toList()

        return StationSimConfig(
            csmsUrl = csmsUrl,
            stationId = stationId,
            slots = (1..slotCount).map { slotId ->
                SlotConfig(
                    slotId = slotId,
                    // 내주는 배터리는 잘 충전돼 있다 (S04.FR.05 TargetSoC 를 넘긴 상태).
                    battery = if (slotId in dispenseSlots) SimBattery("BAT-FULL-$slotId", soC = 95.0, soH = 98.0) else null,
                )
            },
            idToken = idToken,
            requestId = requestId,
            insertSlots = insertSlots,
            dispenseSlots = dispenseSlots,
            // 이용자가 가져온 헌 배터리들 — 다 쓴 상태다.
            // F3 은 그 일련번호만 등록 목록 밖의 값으로 바뀐다. 나머지는 그대로다.
            incomingBatteries = insertSlots.mapIndexed { index, slotId ->
                val serial = if (fault == FaultScenario.F3) "STOLEN-$slotId" else "BAT-USED-$slotId"
                SimBattery(serial, soC = 12.0 + index, soH = 90.0 - index)
            },
            swapOrder = swapOrder,
        )
    }
}

/** 슬롯 하나가 화면에 보이는 모습. 값을 모르면 `null` 이다 — 그럴듯한 기본값을 만들지 않는다. */
data class SlotSnapshot(
    val slotId: Int,
    val role: String,
    val battery: SimBattery?,
    val chargingTransactionId: String?,
)

/** 스테이션 한 대의 현재 모습. `GET /api/state` 가 이것을 그대로 싣는다. */
data class StationSnapshot(
    val stationId: String,
    val csmsUrl: String,
    val connectUrl: String,
    val connected: Boolean,
    val swapOrder: String,
    val idToken: IdToken,
    val progress: SwapProgress,
    val fault: FaultScenario?,
    val note: String?,
    val error: String?,
    val requestId: Int?,
    val messageCount: Int,
    val slots: List<SlotSnapshot>,
)

/**
 * 조종당하는 스테이션 한 대.
 *
 * ### 상태를 복제해 들고 있지 않는다
 *
 * 슬롯과 배터리는 [StationSimulator] 에게 매번 물어본다 ([snapshot]). 콘솔이 따로 사본을
 * 들면 언젠가 어긋나고, 그때 화면은 **없는 것을 보여주게** 된다. 콘솔이 스스로 아는 것은
 * 자기가 시킨 일의 진행 상태뿐이다.
 *
 * ### 교환을 시작할 때마다 시뮬레이터를 새로 만든다
 *
 * 두 가지 이유다. 하나는 [FaultScenario] 의 절반이 **구성**으로 재현되기 때문이고(F1·F3),
 * 다른 하나는 한 번 완주한 스테이션은 투입 슬롯이 차 있어서 다음 교환의 전제조건
 * (`insertSlots` 는 비어 있어야 한다)을 만족하지 못하기 때문이다. 매번 새로 만들면 두
 * 사정이 한 규칙으로 없어진다.
 */
class ControlledStation(val spec: StationSpec) : AutoCloseable {

    /**
     * 이 스테이션 전용 작업 스레드.
     *
     * [StationSimulator] 는 스레드 안전을 약속하지 않는다. 제어 조작을 한 스레드에 몰아
     * 직렬화하고, HTTP 스레드는 요청을 접수만 하고 돌아간다 — 교환은 몇 초씩 걸리는데
     * 그동안 응답을 붙들고 있으면 화면이 멈춘다.
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sim-console-${spec.stationId}").apply { isDaemon = true }
    }

    /** 교환마다 새 상관 번호를 쓴다. 같은 값을 다시 쓰면 CSMS 의 이전 교환과 부딪힌다. */
    private val requestIds = AtomicInteger(spec.requestId)

    private val lock = Any()

    @Volatile
    private var simulator: StationSimulator? = null

    @Volatile
    private var detached = false

    @Volatile
    var progress: SwapProgress = SwapProgress.ATTACHED
        private set

    @Volatile
    var fault: FaultScenario? = null
        private set

    /** 시나리오가 남긴 관측 결과 — 거부 사유 같은 것. 없으면 `null` 이다. */
    @Volatile
    var note: String? = null
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var activeRequestId: Int? = null
        private set

    // ------------------------------------------------------------------ 제어

    /** 붙인다 — 연결하고 부팅까지. 슬롯이 화면에 보이려면 여기까지 와야 한다. */
    fun attach() {
        val simulator = rebuild(fault = null)
        runBlocking {
            simulator.connect()
            simulator.boot()
        }
    }

    /**
     * 교환을 시작한다. [fault] 가 `null` 이면 정상 경로다.
     *
     * 접수만 하고 곧바로 돌아간다 — 실제 시퀀스는 작업 스레드에서 돈다. 호출자는
     * `GET /api/state` 로 진행을 본다.
     */
    fun start(fault: FaultScenario?) {
        synchronized(lock) {
            if (progress == SwapProgress.RUNNING || progress == SwapProgress.AWAITING_REMOTE_START) {
                throw ControlError(409, "이미 교환이 진행 중이다: ${spec.stationId} ($progress)")
            }
            this.fault = fault
            note = null
            error = null
            // CSMS 의 개시를 기다리는 시나리오라도 여기서 AWAITING_REMOTE_START 로 두지 않는다.
            // 아직 붙지도 부팅하지도 않았으므로 **기다리는 중이 아니다** — 그 상태를 보고
            // 개시를 걸면 CSMS 는 모르는 스테이션을 향해 요청하게 된다.
            progress = SwapProgress.RUNNING
        }

        worker.execute {
            try {
                val simulator = rebuild(fault)
                runBlocking {
                    simulator.connect()
                    simulator.boot()
                    runScenario(simulator, fault)
                }
                // 거부로 끝난 시나리오(F1)를 완주로 덮어쓰지 않는다.
                if (progress != SwapProgress.REJECTED) progress = SwapProgress.COMPLETED
            } catch (failure: Throwable) {
                if (detached) return@execute
                error = failure.message ?: failure.toString()
                progress = SwapProgress.FAILED
            }
        }
    }

    override fun close() {
        detached = true
        worker.shutdownNow()
        synchronized(lock) {
            simulator?.close()
            simulator = null
        }
    }

    // ------------------------------------------------------------------ 관측

    fun snapshot(): StationSnapshot {
        val simulator = this.simulator
        val config = simulator?.config
        return StationSnapshot(
            stationId = spec.stationId,
            csmsUrl = spec.csmsUrl,
            connectUrl = config?.connectUrl ?: "${spec.csmsUrl.trimEnd('/')}/${spec.stationId}",
            connected = simulator?.isConnected == true,
            swapOrder = spec.swapOrder.wireValue,
            idToken = spec.idToken,
            progress = progress,
            fault = fault,
            note = note,
            error = error,
            requestId = activeRequestId,
            // 지금 붙어 있는 세션에서 오간 수다. 시뮬레이터를 새로 만들면 0 부터 다시 센다.
            messageCount = simulator?.eventLog?.size() ?: 0,
            slots = config?.slots.orEmpty().map { slot ->
                SlotSnapshot(
                    slotId = slot.slotId,
                    role = when (slot.slotId) {
                        in config?.insertSlots.orEmpty() -> ROLE_INSERT
                        in config?.dispenseSlots.orEmpty() -> ROLE_DISPENSE
                        else -> ROLE_IDLE
                    },
                    // 시뮬레이터가 지금 들고 있는 값이다. 구성의 초기값이 아니다.
                    battery = simulator?.batteryAt(slot.slotId),
                    chargingTransactionId = simulator?.chargingTransactionAt(slot.slotId),
                )
            },
        )
    }

    // ------------------------------------------------------------------ 내부

    private fun rebuild(fault: FaultScenario?): StationSimulator = synchronized(lock) {
        check(!detached) { "이미 내려간 스테이션이다: ${spec.stationId}" }
        simulator?.close()

        val requestId = requestIds.getAndIncrement()
        val simulator = StationSimulator(
            config = spec.config(requestId, fault),
            faults = faultInjection(fault),
        )
        this.simulator = simulator
        // F1 은 상관 번호를 CSMS 가 발번한다 (S02.FR.02). 우리가 정한 값은 쓰이지 않으므로
        // 아는 척하지 않는다.
        activeRequestId = if (fault == FaultScenario.F1) null else requestId
        simulator
    }

    /**
     * 시퀀스 한가운데서 끊는 훅이 필요한 시나리오는 F6 하나뿐이다.
     *
     * 나머지는 구성이나 진입점으로 재현되므로 훅을 걸지 않는다 — 없는 쓰임을 위해 훅을
     * 더 만들지 않는다 (PLAN §11.0).
     */
    private fun faultInjection(fault: FaultScenario?): FaultInjection = when (fault) {
        FaultScenario.F6 -> FaultInjection.failingAt(SimStep.BATTERY_OUT, "교환 도중 연결이 끊겼다")
        else -> FaultInjection.None
    }

    private suspend fun runScenario(simulator: StationSimulator, fault: FaultScenario?) {
        when (fault) {
            // 정상 경로. F3 도 같은 시퀀스다 — 다른 것은 배터리 일련번호뿐이다.
            null -> simulator.runSwap()

            FaultScenario.F3 -> {
                simulator.runSwap()
                note = rejectionNote(simulator)
            }

            // 개시 주체가 CSMS 다. 우리는 답만 한다.
            FaultScenario.F1 -> awaitRemoteOutcome(simulator)

            // 배터리를 내줬는데 이용자가 꺼내가지 않았다 (S03.FR.06). 교환은 반쪽으로 남는다.
            FaultScenario.F2 -> {
                simulator.authorize()
                simulator.insertBatteries()
                simulator.reportBatteryOutTimeout()
            }

            // 새 messageId 로 보내야 멱등 원장을 지나 상태머신까지 닿는다 (F6 과 갈리는 지점).
            FaultScenario.F4 -> {
                simulator.authorize()
                simulator.insertBatteries()
                simulator.resendLastBatterySwap(sameMessageId = false)
            }

            // 인가를 건너뛴다. authorize() 를 부르지 않는 것이 이 시나리오의 전부다.
            FaultScenario.F5 -> simulator.insertBatteries()

            // 출고 직전에 끊기고, 재접속해 **같은 프레임**을 다시 보낸다.
            FaultScenario.F6 -> {
                var fired = false
                try {
                    simulator.runSwap()
                } catch (interrupted: SimulatedFault) {
                    fired = true
                    note = interrupted.message
                }
                // 끊기지 않았다면 이어지는 재전송은 F6 이 아니라 그냥 중복 전송이다.
                check(fired) { "장애가 주입되지 않았다 — 교환이 그대로 완주했다" }

                simulator.disconnect()
                simulator.reconnect()
                simulator.resendLastBatterySwap(sameMessageId = true)
            }
        }
    }

    /**
     * F1 — CSMS 의 개시 요청을 기다렸다가 우리가 낸 답을 관측한다.
     *
     * `awaitRemoteStart()` 를 기다리지 않는다. 그 자리는 **받아들인 경우에만** 깨어나는데
     * (거부는 교환을 열지 않는다), F1 이 보고 싶은 것이 바로 그 거부다. 그래서 우리가
     * 내보낸 `RequestBatterySwapResponse` 를 이벤트 로그에서 직접 읽는다 (PLAN §11.1 —
     * 파생 상태는 로그에서 재구성한다).
     */
    private suspend fun awaitRemoteOutcome(simulator: StationSimulator) {
        // 붙고 부팅까지 끝난 지금부터가 실제로 기다리는 구간이다.
        progress = SwapProgress.AWAITING_REMOTE_START
        repeat(REMOTE_START_POLLS) {
            if (detached) return

            val decision = simulator.eventLog.of(spec.stationId).lastOrNull {
                it.direction == MessageDirection.OUTBOUND && it.action == BatterySwapWire.REQUEST_BATTERY_SWAP
            }
            if (decision != null) {
                val payload = MAPPER.readTree(decision.payload).get(RESULT_PAYLOAD_INDEX)
                if (payload.path("status").asText() == BatterySwapWire.GENERIC_REJECTED) {
                    note = rejectionText(payload.path("statusInfo"))
                    progress = SwapProgress.REJECTED
                    return
                }
                // 받아들였다면 이어지는 교환은 CSMS 가 발번한 상관 번호를 승계한다 (S02.FR.02).
                activeRequestId = simulator.awaitRemoteStart()
                simulator.runRemoteSwap()
                return
            }
            delay(REMOTE_START_POLL_INTERVAL_MS)
        }
        throw IllegalStateException(
            "CSMS 가 RequestBatterySwap 을 보내지 않았다 — POST /api/swaps 로 개시해야 한다",
        )
    }

    /** F3 — 응답에 붙어 온 customData 거부 (PLAN §4.8). 없으면 `null` 이다. */
    private fun rejectionNote(simulator: StationSimulator): String? =
        simulator.eventLog.of(spec.stationId)
            .filter { it.direction == MessageDirection.INBOUND && it.action == BatterySwapWire.BATTERY_SWAP }
            .map { MAPPER.readTree(it.payload).get(RESULT_PAYLOAD_INDEX).path("customData") }
            .firstOrNull { !it.isMissingNode && it.path("status").asText() == BatterySwapWire.GENERIC_REJECTED }
            ?.let { rejectionText(it.path("statusInfo")) }

    private fun rejectionText(statusInfo: JsonNode): String {
        val reason = statusInfo.path("reasonCode").asText("")
        val info = statusInfo.path("additionalInfo").asText("")
        return listOf(reason, info).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { "Rejected" }
    }

    private companion object {
        const val ROLE_INSERT = "INSERT"
        const val ROLE_DISPENSE = "DISPENSE"
        const val ROLE_IDLE = "IDLE"

        /** CALLRESULT 프레임 `[3,"<id>",{payload}]` 에서 페이로드의 자리. */
        const val RESULT_PAYLOAD_INDEX = 2

        /** 사람이 curl 을 치는 데 걸리는 시간을 감안한다. 2분이면 데모에 넉넉하다. */
        const val REMOTE_START_POLLS = 1200
        const val REMOTE_START_POLL_INTERVAL_MS = 100L

        val MAPPER = ObjectMapper()
    }
}

/**
 * 붙어 있는 스테이션들.
 *
 * `stationId` 하나에 시뮬레이터 하나다 — 같은 식별자로 두 번 붙는 것은 CSMS 쪽에서도
 * 성립하지 않는 상황이라 여기서 막는다 (409).
 */
class ControlledStations(val defaultCsmsUrl: String) : AutoCloseable {

    private val lock = Any()
    private val stations = LinkedHashMap<String, ControlledStation>()

    fun attach(spec: StationSpec): ControlledStation {
        // 연결하기 **전에** 자리를 잡는다. 그래야 같은 식별자가 동시에 두 번 들어와도
        // 둘 다 붙어 버리는 일이 없다.
        val station = synchronized(lock) {
            if (stations.containsKey(spec.stationId)) {
                throw ControlError(409, "이미 붙어 있는 스테이션이다: ${spec.stationId}")
            }
            ControlledStation(spec).also { stations[spec.stationId] = it }
        }

        try {
            station.attach()
        } catch (failure: Exception) {
            synchronized(lock) { stations.remove(spec.stationId) }
            station.close()
            throw ControlError(502, "CSMS 에 붙지 못했다 (${spec.csmsUrl}): ${failure.message ?: failure.toString()}")
        }
        return station
    }

    fun detach(stationId: String) {
        val station = synchronized(lock) { stations.remove(stationId) } ?: throw notFound(stationId)
        station.close()
    }

    fun find(stationId: String): ControlledStation =
        synchronized(lock) { stations[stationId] } ?: throw notFound(stationId)

    fun snapshots(): List<StationSnapshot> = synchronized(lock) { stations.values.toList() }.map { it.snapshot() }

    override fun close() {
        synchronized(lock) { stations.values.toList().also { stations.clear() } }.forEach { it.close() }
    }

    private fun notFound(stationId: String) = ControlError(404, "붙어 있지 않은 스테이션이다: $stationId")
}
