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
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
 * 화면의 장애 주입 버튼 여섯 개.
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
 * @param title 실패 시나리오 표의 이름. 화면에 **그대로** 적힌다 — 버튼만 있고 뜻을 모르면
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
 * 콘솔이 스테이션에 직접 걸 수 있는 조작들.
 *
 * ### 목록의 출처는 `docs/VIRTUAL-STATION.md` §2 다
 *
 * 그 문서가 `StationSimulator` 의 호출을 **Acts(사실을 바꾸고 알린다) / Reports(말할 뿐
 * 아무것도 바꾸지 않는다) / Scripts(앞의 둘을 묶은 것) / Observations(읽기만 한다)** 로
 * 가른다. 콘솔이 손잡이로 내주는 것은 앞의 둘이다 — 각본은 이미 `POST .../swap` 이 덮고,
 * 관측은 `GET /api/state` 가 덮는다.
 *
 * ### 빠진 것과 그 이유
 *
 * - **Scripts** (`runSwap` `bootAndSwap` `runRemoteSwap` `chargeUntilMaxSoc`) — `POST
 *   /api/stations/{id}/swap` 이 이미 하는 일이다. 같은 일에 두 번째 입구를 내면 어느 쪽이
 *   진짜인지가 다시 흐려진다.
 * - **Observations** (`config` `eventLog` `slotState` `batteryAt` …) — `GET /api/state` 의
 *   스냅샷이 그대로 싣는다. 읽는 일에 조작을 쓸 이유가 없다.
 * - `awaitRemoteStart()` · `reportFullInventory()` — 둘 다 **상대가 먼저 움직여 주기를
 *   기다린다**. CSMS 가 `RequestBatterySwap` 이나 `GetBaseReport` 를 보내지 않으면 영영
 *   돌아오지 않고, 그동안 HTTP 요청이 매달린 채로 상한([ControlledStation.OP_TIMEOUT_MS])만
 *   태운다. 기다림이 필요한 시나리오는 F1 이고, 그건 각본 쪽이 이미 다룬다.
 * - ★ `close()` — **조작이 아니라 수명 관리다.** `AutoCloseable.close()` 이고, 뜻은
 *   "스테이션 전원을 내린다"가 아니라 "이 객체를 그만 쓴다"이다. 닫힌 시뮬레이터는 다시
 *   붙지 않으므로(`StationSimulator` 의 use-after-close 검사) 손잡이로 내주면 **되돌릴 수
 *   없는 것이 되돌릴 수 있는 것들 옆에 놓인다.** 스테이션을 끝내는 입구는
 *   `DELETE /api/stations/{id}` 이고, 사람이 "전원을 내린다"로 원하는 것은 대개 `disconnect`
 *   다 — 그쪽은 세션·슬롯을 남기므로 되돌아온다.
 *
 * @param wireValue 요청 본문의 `op` 에 적는 값. 시뮬레이터 함수 이름과 **같다** — 문서의
 *   표에서 이름을 옮겨 적으면 그대로 통해야 한다.
 */
enum class StationOp(val wireValue: String) {

    // Acts — 사실을 바꾸고 그 결과를 전선에 알린다.

    CONNECT("connect"),
    DISCONNECT("disconnect"),
    RECONNECT("reconnect"),
    BOOT("boot"),
    REBOOT("reboot"),
    INSERT_BATTERIES("insertBatteries"),
    REMOVE_BATTERIES("removeBatteries"),

    /** `slotId` 와 `byPercent` 를 받는다. 둘 다 없으면 400 이다. */
    ADVANCE_CHARGING("advanceCharging"),

    // Reports — 말할 뿐, 스테이션 안의 사실은 그대로다.

    AUTHORIZE("authorize"),
    REPORT_CHARGING_STARTED("reportChargingStarted"),
    REPORT_BATTERY_OUT_TIMEOUT("reportBatteryOutTimeout"),

    /** `sameMessageId` 를 받는다. `true` 는 F6(멱등 원장), `false` 는 F4(중복 BatteryIn)다. */
    RESEND_LAST_BATTERY_SWAP("resendLastBatterySwap"),
}

/**
 * 조작에 딸린 인자들. 쓰이는 것은 두 조작뿐이라 전부 선택값이다.
 *
 * 기본값을 두지 않는다. `sameMessageId` 에 기본을 두면 F6 을 부르려던 요청이 조용히 F4 가
 * 되고, 그 차이는 응답에 드러나지 않는다 — 모르는 채로 다른 시험을 하게 되는 편보다
 * 400 으로 되묻는 편이 낫다.
 */
data class StationOpParams(
    val slotId: Int? = null,
    val byPercent: Double? = null,
    val sameMessageId: Boolean? = null,
)

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

/**
 * 슬롯 하나가 화면에 보이는 모습. 값을 모르면 `null` 이다 — 그럴듯한 기본값을 만들지 않는다.
 *
 * @param chargingSuspended 충전이 상한에 닿아 멈췄는가. [chargingTransactionId] 와 **함께**
 *   봐야 뜻이 선다 — 상한 도달은 트랜잭션을 닫지 않으므로 (`Updated(EnergyLimitReached,
 *   SuspendedEVSE)`), 멈춘 것은 트랜잭션이 아니라 에너지 흐름이다. 트랜잭션 번호만 보면
 *   둘을 가릴 수 없다.
 */
data class SlotSnapshot(
    val slotId: Int,
    val role: String,
    val battery: SimBattery?,
    val chargingTransactionId: String?,
    val chargingSuspended: Boolean,
)

/**
 * 오간 프레임 한 건의 머리말.
 *
 * ### 페이로드를 싣지 않는다
 *
 * `OcppEventRecord.payload` 는 프레임 **한 줄 전체**라 수 KB 에 이른다. 그것을 스냅샷에
 * 실으면 상태 조회 한 번이 로그 덤프가 되고, 화면은 그것을 매초 다시 받는다. 여기 있는
 * 것은 "무엇이 언제 어느 방향으로 지나갔는가"뿐이다 — 내용을 봐야 한다면 그건 CSMS 의
 * 이벤트 로그 API 가 할 일이다.
 */
data class StationEvent(
    val seq: Long,
    val direction: String,
    val action: String?,
    val messageId: String,
    val occurredAt: Instant,
)

/**
 * 스테이션 한 대의 현재 모습. `GET /api/state` 가 이것을 그대로 싣는다.
 *
 * @param subprotocol 협상된 서브프로토콜 (`ocpp2.1`). 붙어 있지 않으면 `null` 이다.
 * @param events 최근 프레임의 꼬리 — **최신이 앞**이다. [messageCount] 가 전체 수고
 *   이쪽은 그중 마지막 [StationSnapshot.EVENT_TAIL] 건이다.
 * @param lastTransmitFailure ★ 마지막으로 시도한 전송이 나가지 못했다면 그 사유
 *   ([StationSimulator.lastTransmitFailure] 그대로). [events] 에서 파생되지 않는다 — 세션이
 *   보내기 **전에** 적으므로 나간 프레임과 나가지 못한 프레임의 기록이 같기 때문이다.
 *   [connected] 로도 갈리지 않는다: 그쪽은 소켓이 있는가를 답하지 방금 보낸 것이 나갔는가를
 *   답하지 않아, 상대가 먼저 닫은 창에서는 참인 채로 전송만 실패한다. **읽기 전용이다** —
 *   이 값으로 조작을 거절하는 순간 순서 게이트가 되고, 그 선은 저쪽 KDoc 이 그어 두었다.
 */
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
    val subprotocol: String?,
    val events: List<StationEvent>,
    val lastTransmitFailure: String?,
) {
    companion object {
        /**
         * 되싣는 프레임의 수.
         *
         * 교환 한 건의 **마지막 단계**(반출·트랜잭션 종료·상태 통보)가 요청/응답 쌍으로
         * 모두 들어오는 최소치다. 더 줄이면 무엇으로 끝났는지가 잘리고, 더 늘리면 부팅
         * 시퀀스 전체가 매 조회마다 따라온다.
         */
        const val EVENT_TAIL = 20
    }
}

/**
 * 조종당하는 스테이션 한 대.
 *
 * ### 상태를 복제해 들고 있지 않는다
 *
 * 슬롯과 배터리는 [StationSimulator] 에게 매번 물어본다 ([snapshot]). 콘솔이 따로 사본을
 * 들면 언젠가 어긋나고, 그때 화면은 **없는 것을 보여주게** 된다. 콘솔이 스스로 아는 것은
 * 자기가 시킨 일의 진행 상태뿐이다.
 *
 * ### 각본은 시뮬레이터를 새로 만들고, 수동 조작은 만들지 않는다
 *
 * 갈래가 둘이고, 수명 규칙도 둘이다.
 *
 * **각본([start])은 시뮬레이터를 새로 만든다.** 두 가지 이유다. 하나는 [FaultScenario] 의
 * 절반이 **구성**으로 재현되기 때문이고(F1·F3 — 내줄 배터리를 두지 않거나 일련번호를 등록
 * 목록 밖의 값으로 짓는다), 다른 하나는 한 번 완주한 스테이션은 투입 슬롯이 차 있어서
 * 다음 교환의 전제조건(`insertSlots` 는 비어 있어야 한다)을 만족하지 못하기 때문이다.
 * 매번 새로 만들면 두 사정이 한 규칙으로 없어진다.
 *
 * **수동 조작([operate])은 만들지 않는다.** 사람이 버튼을 누르는 것은 "이 스테이션을 이렇게
 * 움직여 보겠다"는 뜻이고, 그 결과는 **누른 그 스테이션에 쌓여야** 한다. 조작마다 다시
 * 지으면 앞선 조작의 결과가 매번 지워져 `authorize()` → `insertBatteries()` 같은 두 걸음이
 * 성립하지 않는다 — 그건 조종이 아니라 각본 하나짜리 재생이다. 그래서 [operate] 는
 * [attach] 가 만든 그 인스턴스에 계속 작용한다.
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

            // 접수도 가드와 **같은 락 안**에서 한다. 밖으로 내면 가드를 통과한 수동 조작이
            // 이 각본의 rebuild() 뒤로 밀려, 이미 닫힌 시뮬레이터를 붙들고 돌게 된다.
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
    }

    /**
     * 조작 하나를 걸고, 그 **뒤의 모습**을 돌려준다.
     *
     * ### 순서를 검사하지 않는다
     *
     * `authorize()` 없이 `insertBatteries()` 를 부르는 것이 통해야 한다. 그게 **F5** 이고,
     * `docs/VIRTUAL-STATION.md` §3 이 적어 둔 그대로다 — `StationSimulator` 의 모든 검사는
     * 스테이션 안의 **물리적 사실**(슬롯이 비었는가, 연결이 있는가)을 묻지 우리가 부른
     * 순서를 묻지 않는다. 콘솔이 그 위에 순서 게이트를 새로 만들면 릴레이가 붙어버린 실제
     * 스테이션이 내는 프레임을 CSMS 에 **영영 보여줄 수 없게** 된다.
     *
     * ### 실패를 사실별로 가른다
     *
     * - **409** — 각본이 도는 중이다 ([onWorker] 의 가드). 스테이션이 거절한 것이 아니라
     *   콘솔이 받지 않은 것이다.
     * - **422** — 시뮬레이터가 거절했다. `IllegalStateException`·`IllegalArgumentException`
     *   의 메시지를 **손대지 않고** 그대로 싣는다. "빈 슬롯은 충전되지 않는다: 1" 이 이미
     *   무엇이 왜 안 되는지를 말하고 있는데, 콘솔이 그것을 자기 말로 바꾸면 사실 하나에
     *   문장이 둘이 된다.
     * - **502** — 전송이나 CSMS 쪽이 답하지 않았다 (`IOException`). 요청은 성립했는데
     *   상대가 없는 것이라, 우리 잘못(4xx)으로 적으면 거짓이다.
     * - **400 / 504** 는 각각 인자 해석([StationOpParams])과 상한([OP_TIMEOUT_MS])의 몫이다.
     */
    fun operate(op: StationOp, params: StationOpParams): StationSnapshot {
        try {
            onWorker { simulator -> perform(simulator, op, params) }
        } catch (rejected: IllegalStateException) {
            throw ControlError(422, rejected.message ?: "스테이션이 조작을 받아들이지 않았다: ${op.wireValue}")
        } catch (rejected: IllegalArgumentException) {
            throw ControlError(422, rejected.message ?: "스테이션이 조작을 받아들이지 않았다: ${op.wireValue}")
        } catch (unreachable: IOException) {
            throw ControlError(
                502,
                "전송이 조작을 실어 나르지 못했다 (${spec.csmsUrl}): ${unreachable.message ?: unreachable.toString()}",
            )
        }
        // 조작의 결과는 새 상태 변수가 아니라 슬롯과 이벤트 꼬리에서 파생돼 보인다.
        return snapshot()
    }

    /**
     * 붙어 있는 그 시뮬레이터 위에서 조작 한 건을 돌리고, 끝날 때까지 기다린다.
     *
     * ### [start] 와 세 군데가 다르다
     *
     * - **다시 짓지 않는다.** [rebuild] 를 부르지 않으므로 [attach] 가 만든 인스턴스에
     *   조작이 쌓인다. 클래스 KDoc 의 "수동 조작은 만들지 않는다"가 여기 한 줄이다.
     * - **기다린다.** 각본은 몇 초씩 걸려 202 로 접수만 하지만, 조작 한 건은 프레임 몇 개다.
     *   기다려서 **조작 뒤의 스냅샷**을 그대로 돌려주는 편이 호출자에게 정직하다 — 폴링해야
     *   무엇이 바뀌었는지 알 수 있는 API 는 조종 손잡이가 아니다.
     * - **진행 상태를 건드리지 않는다.** [SwapProgress] 는 각본의 진행이지 조작의 진행이
     *   아니다. 조작의 결과는 새 상태 변수가 아니라 슬롯과 이벤트 꼬리에서 파생돼 보인다.
     *
     * ### 상한을 넘겨도 취소하지 않는다
     *
     * [OP_TIMEOUT_MS] 를 넘기면 504 로 "아직 돌고 있다"를 그대로 답하고 **작업을 끊지
     * 않는다**. `Future.cancel(true)` 은 작업 스레드를 인터럽트하는데, 그 스레드는 프레임을
     * 절반쯤 내보낸 채일 수 있다. 그러면 시뮬레이터의 슬롯·트랜잭션·`seqNo` 는 어느 쪽도
     * 아닌 상태로 남고, 이후의 모든 관측이 거짓이 된다. 응답을 포기하는 것과 스테이션을
     * 망가뜨리는 것은 다른 일이다.
     */
    private fun <T> onWorker(block: suspend (StationSimulator) -> T): T {
        val pending = synchronized(lock) {
            if (progress == SwapProgress.RUNNING || progress == SwapProgress.AWAITING_REMOTE_START) {
                throw ControlError(409, "교환이 진행 중이라 조작을 받지 않는다: ${spec.stationId} ($progress)")
            }
            if (detached) throw ControlError(404, "이미 내려간 스테이션이다: ${spec.stationId}")
            // 락 안에서 집은 그 인스턴스를 작업 스레드까지 들고 간다. 밖에서 다시 읽으면
            // 그 사이의 rebuild() 가 바꿔치기한 다른 시뮬레이터를 잡게 된다.
            val simulator = this.simulator
                ?: throw ControlError(409, "아직 붙지 않은 스테이션이다: ${spec.stationId}")
            worker.submit(Callable<T> { runBlocking { block(simulator) } })
        }

        return try {
            pending.get(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            throw ControlError(504, "조작이 ${OP_TIMEOUT_MS}ms 안에 끝나지 않았다 — 아직 돌고 있다: ${spec.stationId}")
        } catch (failure: ExecutionException) {
            // 껍데기를 벗겨 원인을 그대로 올린다. 상태 코드는 원인의 종류가 정한다.
            throw failure.cause ?: failure
        }
    }

    /**
     * 내린다.
     *
     * **표시와 종료가 락 안에 함께 있어야 한다.** `detached` 를 밖에서 세우면, 락 안에서
     * 그 표시를 확인하고 [worker] 에 넣으려는 요청이 그 사이를 지나 **이미 닫힌 실행기**에
     * 던지게 된다 — `RejectedExecutionException` 이 404 대신 500 으로 나가고, [start] 쪽에서는
     * `progress` 가 `RUNNING` 에 박혀 이후 조작이 영영 409 가 된다.
     *
     * `shutdownNow()` 는 인터럽트만 걸고 곧바로 돌아오므로 락을 오래 쥐지 않는다.
     */
    override fun close() {
        synchronized(lock) {
            detached = true
            worker.shutdownNow()
            simulator?.close()
            simulator = null
        }
    }

    // ------------------------------------------------------------------ 관측

    /**
     * 지금 이 순간의 모습. 전부 시뮬레이터에게 물어 만든다.
     *
     * 두 자리가 시뮬레이터의 값을 그대로 옮기지 못한다.
     *
     * - `subprotocol` 은 붙어 있지 않으면 `null` 이다. 아는 척하는 것보다 모른다고 답하는
     *   편이 맞다. **여기서 갈라 주지 않는다** — 한때 `isConnected` 로 먼저 걸렀는데, 그
     *   둘이 전송을 각각 읽으므로 사이에 끊기면 관측이 예외로 끝났다. 스냅샷은 HTTP
     *   스레드에서 뜨고 조작은 작업 스레드에서 도니 실제로 벌어지는 순서다. 판정을
     *   `StationSimulator` 안의 한 번 읽기로 옮겼다.
     * - `chargingSuspended` 는 시뮬레이터가 없으면 `false` 다. 급전 자체가 없는 것이지
     *   멈춰 있는 것이 아니다.
     *
     * `events` 는 새 상태 없이 이벤트 로그에서만 파생한다 — 콘솔이 사본을 들면 언젠가
     * 어긋난다.
     */
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
                    chargingSuspended = simulator?.isChargingSuspended(slot.slotId) == true,
                )
            },
            subprotocol = simulator?.subprotocol,
            events = simulator?.eventLog?.of(spec.stationId).orEmpty()
                .takeLast(StationSnapshot.EVENT_TAIL)
                .asReversed()
                .map {
                    StationEvent(
                        seq = it.seq,
                        direction = it.direction.name,
                        action = it.action,
                        messageId = it.messageId,
                        occurredAt = it.occurredAt,
                    )
                },
            // 시뮬레이터가 들고 있는 그 값이다. 콘솔이 조작 결과로 따로 세우지 않는다 —
            // 실패는 수동 조작만이 아니라 인바운드 응답에서도 나므로 콘솔은 다 보지 못한다.
            lastTransmitFailure = simulator?.lastTransmitFailure,
        )
    }

    // ------------------------------------------------------------------ 내부

    /**
     * `when` 하나가 전부다 — 조작 이름과 시뮬레이터 함수 사이에 아무것도 끼우지 않는다.
     *
     * 여기 조건문이 늘어나기 시작하면 콘솔이 스테이션의 두 번째 상태머신이 되고, 그때부터
     * 화면이 보여주는 것은 실제 스테이션이 아니라 콘솔이 믿는 스테이션이다.
     */
    private suspend fun perform(simulator: StationSimulator, op: StationOp, params: StationOpParams) {
        when (op) {
            StationOp.CONNECT -> simulator.connect()
            StationOp.DISCONNECT -> simulator.disconnect()
            StationOp.RECONNECT -> simulator.reconnect()
            StationOp.BOOT -> simulator.boot()
            StationOp.REBOOT -> simulator.reboot()
            StationOp.INSERT_BATTERIES -> simulator.insertBatteries()
            StationOp.REMOVE_BATTERIES -> simulator.removeBatteries()
            StationOp.ADVANCE_CHARGING -> simulator.advanceCharging(
                slotId = required(params.slotId, "slotId", op),
                byPercent = required(params.byPercent, "byPercent", op),
            )

            StationOp.AUTHORIZE -> simulator.authorize()
            StationOp.REPORT_CHARGING_STARTED -> simulator.reportChargingStarted()
            StationOp.REPORT_BATTERY_OUT_TIMEOUT -> simulator.reportBatteryOutTimeout()
            StationOp.RESEND_LAST_BATTERY_SWAP ->
                simulator.resendLastBatterySwap(required(params.sameMessageId, "sameMessageId", op))
        }
    }

    /** 없으면 400 이다 — 아직 아무것도 시키지 않았으므로 스테이션이 거절한 것(422)이 아니다. */
    private fun <T : Any> required(value: T?, name: String, op: StationOp): T =
        value ?: throw ControlError(400, "${op.wireValue} 에는 $name 이 필요하다")

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
     * 더 만들지 않는다.
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
     * 내보낸 `RequestBatterySwapResponse` 를 이벤트 로그에서 직접 읽는다 (
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

    /** F3 — 응답에 붙어 온 customData 거부. 없으면 `null` 이다. */
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

        /**
         * 조작 한 건을 기다려 주는 상한.
         *
         * 조작 하나는 프레임 몇 개라 로컬에서 수십 ms 다. 10초를 넘겼다면 느린 것이 아니라
         * 상대가 답하지 않는 것이고, 그때는 기다리는 대신 그 사실을 답해야 한다.
         */
        const val OP_TIMEOUT_MS = 10_000L

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
