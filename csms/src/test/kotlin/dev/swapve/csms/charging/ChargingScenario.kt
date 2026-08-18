package dev.swapve.csms.charging

import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.support.MutableClock
import dev.swapve.station.SimBattery
import dev.swapve.station.SlotConfig
import dev.swapve.station.StationSimConfig
import dev.swapve.station.StationSimulator
import dev.swapve.swap.IdToken
import java.time.Duration

/**
 * M9 시험이 쓰는 스테이션 구성 — **충전이 실제로 진행되는** 시나리오 (S04).
 *
 * ### 왜 [SwapScenario][dev.swapve.csms.e2e.SwapScenario] 를 그대로 쓰지 않나
 *
 * 그쪽은 교환 1건의 완주를 보이는 구성이라 시계가 **고정**돼 있고 디바이스 모델 값이
 * 기본값이다. 충전 진행은 (1) 시각이 흘러야 하고 (2) 상한(`MaxSoc`)이 손 닿는 곳에 있어야
 * 몇 걸음 만에 도달을 볼 수 있다. 그 둘만 다르다.
 *
 * ### 실제 시간을 기다리지 않는다
 *
 * 시뮬레이터에 [MutableClock] 을 끼운다 (M8 이 지표 시험에 쓴 것과 같은 자산). 시험이
 * `advance(...)` 로 시각을 밀면 "10분 뒤"가 만들어지고, `sleep` 은 어디에도 없다.
 * `ocpp-core` 가 벽시계를 조회하지 못하게 빌드가 막고 있어서 (`checkNoFrameworkImports`)
 * 이 방식이 성립한다.
 */
object ChargingScenario {

    /** `application.yml` 의 인가 목록에 있는 토큰. */
    val AUTHORIZED_TOKEN = IdToken("RFID-0001", "ISO14443")

    /** 디바이스 모델 `BatterySwapCtrlr.IdToken` — 충전 트랜잭션용 대체 토큰 (S04.FR.02/03). */
    const val CHARGING_TOKEN = "BSS-CENTRAL-0001"

    val INSERT_SLOTS = listOf(1, 2)
    val DISPENSE_SLOTS = listOf(3, 4)

    /** 이용자가 가져온 헌 배터리. SoC 가 낮아 충전 진행을 볼 수 있다. */
    val INCOMING = listOf(
        SimBattery("BAT-USED-1", soC = 23.0, soH = 85.0),
        SimBattery("BAT-USED-2", soC = 45.0, soH = 87.0),
    )

    /** 스테이션이 내줄 배터리 — 이미 충전이 끝난 것들이다. */
    val DISPENSED = mapOf(
        3 to SimBattery("BAT-FULL-3", soC = 80.0, soH = 95.0),
        4 to SimBattery("BAT-FULL-4", soC = 85.0, soH = 78.0),
    )

    /**
     * 재부팅 시험이 쓰는, **아직 충전 중인** 배터리들.
     *
     * [DISPENSED] 는 SoC 가 [MAX_SOC] 를 이미 넘겨 있어 충전이 진행되지 않는다 (넘겨 꽂힌
     * 배터리는 곧바로 멈춘다). 재부팅 전후로 충전이 이어지는 것을 보이려면 상한 아래에서
     * 시작해야 한다.
     */
    val PARTIALLY_CHARGED = mapOf(
        3 to SimBattery("BAT-HALF-3", soC = 20.0, soH = 95.0),
        4 to SimBattery("BAT-HALF-4", soC = 25.0, soH = 78.0),
    )

    /** 교환에 내줘도 되는 기준 (S04.FR.05). */
    const val TARGET_SOC = 40

    /**
     * 충전 상한 (S04.FR.06/10). **`TARGET_SOC` 이상이어야 한다.**
     *
     * 50 으로 낮게 잡은 것은 의도다 — SoC 23 에서 10 씩 올리면 세 걸음 만에 상한에 닿아,
     * 시험이 수십 번 반복하지 않고도 `SuspendedEVSE` 를 볼 수 있다.
     */
    const val MAX_SOC = 50

    /** 주기 보고 한 걸음의 크기(%). */
    const val SOC_STEP = 10.0

    /** 한 걸음 사이에 흐르는 시간. 실제로 기다리지 않는다 — 시계를 민다. */
    val SOC_STEP_INTERVAL: Duration = Duration.ofMinutes(10)

    fun config(
        port: Int,
        stationId: String,
        dispensed: Map<Int, SimBattery> = DISPENSED,
    ) = StationSimConfig(
        csmsUrl = "ws://localhost:$port/ocpp",
        stationId = stationId,
        slots = (1..4).map { slotId -> SlotConfig(slotId = slotId, battery = dispensed[slotId]) },
        idToken = AUTHORIZED_TOKEN,
        requestId = 9000,
        insertSlots = INSERT_SLOTS,
        dispenseSlots = DISPENSE_SLOTS,
        incomingBatteries = INCOMING,
        chargingIdToken = CHARGING_TOKEN,
        targetSoC = TARGET_SOC,
        maxSoc = MAX_SOC,
        batteryInTimeout = Duration.ofSeconds(30),
        batteryOutTimeout = Duration.ofSeconds(90),
    )

    /** 시각을 시험이 정하는 시뮬레이터. 돌려준 시계를 밀면 스테이션의 시각이 흐른다. */
    fun simulator(config: StationSimConfig): Pair<StationSimulator, MutableClock> {
        val clock = MutableClock(FixedClockConfig.FIXED_NOW)
        return StationSimulator(config, clock = clock) to clock
    }
}
