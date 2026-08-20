package dev.swapve.csms.e2e

import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.support.TestCredentials
import dev.swapve.station.SimBattery
import dev.swapve.station.SlotConfig
import dev.swapve.station.StationSimConfig
import dev.swapve.station.StationSimulator
import dev.swapve.station.SwapOrder
import dev.swapve.swap.IdToken
import java.time.Clock
import java.time.ZoneOffset

/**
 * 종단 시험이 쓰는 교환 시나리오 — **`TC_S_103_CSMS` 의 모양 그대로** (PLAN §7.1).
 *
 * - 배터리 **2개 세트**를 한 번에 넣고 2개를 한 번에 뺀다 (PLAN §10 결정 #6)
 * - **입고 슬롯(1·2)과 출고 슬롯(3·4)이 다르다** — 적합성 시퀀스의 A·B / C·D 에 해당한다
 * - 스테이션 시계도 고정한다. 실제 시간을 기다리는 곳이 어디에도 없다
 */
object SwapScenario {

    /** `application.yml` 의 인가 목록에 있는 토큰. 없는 토큰이면 S01 이 거부된다. */
    val AUTHORIZED_TOKEN = IdToken("RFID-0001", "ISO14443")

    /** 디바이스 모델 `BatterySwapCtrlr.IdToken` — 충전 트랜잭션용 대체 토큰 (S04.FR.02/03). */
    const val CHARGING_TOKEN = "BSS-CENTRAL-0001"

    val INSERT_SLOTS = listOf(1, 2)
    val DISPENSE_SLOTS = listOf(3, 4)

    /** 이용자가 가져온 헌 배터리 — 다 쓴 상태다. */
    val INCOMING = listOf(
        SimBattery("BAT-USED-1", soC = 23.0, soH = 85.0),
        SimBattery("BAT-USED-2", soC = 45.0, soH = 87.0),
    )

    /** 스테이션이 내줄 배터리 — 충전이 끝난 상태다. */
    val DISPENSED = mapOf(
        3 to SimBattery("BAT-FULL-3", soC = 80.0, soH = 95.0),
        4 to SimBattery("BAT-FULL-4", soC = 85.0, soH = 78.0),
    )

    /** 시뮬레이터도 고정 시계를 쓴다 — 양쪽 다 결정적이어야 한다. */
    fun clock(): Clock = Clock.fixed(FixedClockConfig.FIXED_NOW, ZoneOffset.UTC)

    fun config(
        port: Int,
        stationId: String,
        order: SwapOrder,
        requestId: Int,
        chargingIdToken: String? = CHARGING_TOKEN,
    ) = StationSimConfig(
        csmsUrl = "ws://localhost:$port/ocpp",
        stationId = stationId,
        password = TestCredentials.PASSWORD,
        slots = (1..4).map { slotId -> SlotConfig(slotId = slotId, battery = DISPENSED[slotId]) },
        idToken = AUTHORIZED_TOKEN,
        requestId = requestId,
        insertSlots = INSERT_SLOTS,
        dispenseSlots = DISPENSE_SLOTS,
        incomingBatteries = INCOMING,
        swapOrder = order,
        chargingIdToken = chargingIdToken,
    )

    /** 연결까지만 한 시뮬레이터. 시나리오 진행은 호출자가 정한다. */
    fun simulator(config: StationSimConfig) = StationSimulator(config, clock = clock())
}
