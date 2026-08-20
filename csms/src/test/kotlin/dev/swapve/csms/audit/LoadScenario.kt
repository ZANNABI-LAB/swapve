package dev.swapve.csms.audit

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
 * ★ **성공 기준 S4 의 부하 형상** (게이트 L3).
 *
 * > *"스테이션 20대 동시 접속 후 불변식 감사 전항목 통과"*
 *
 * ### 규모를 어떻게 정했나
 *
 * - **스테이션 20대는 고정**이다. 성공 기준이 그 숫자를 적었고, 그것이 이 시험의 요점이다.
 * - 라운드는 [ROUNDS] 회. 스테이션마다 교환을 여러 건 완주시켜야 "한 번 성공"과 "반복해도
 *   장부가 안 깨진다"가 구분된다. 20 × [ROUNDS] 건이 감사 대상 모집단이다.
 * - **In-Out 과 Out-In 을 섞는다**. 짝수 번째 스테이션이 역순이다.
 *
 * ### 라운드마다 슬롯이 다르다
 *
 * [StationSimConfig] 는 `insertSlots`/`dispenseSlots`/`requestId` 를 설정으로 고정한다 —
 * 시뮬레이터 하나가 교환을 두 번 돌 수 없다는 뜻이다. 그래서 라운드마다 **슬롯 번호를 옮겨
 * 가며 새 연결**로 붙는다. 슬롯이 겹치지 않으므로 라운드 간 상태가 서로를 덮지 않고, 감사는
 * 스테이션 하나의 슬롯 12 개에 대한 이력을 전부 볼 수 있다.
 *
 * ### ★ requestId 는 스테이션이 달라도 같은 값을 쓴다
 *
 * 일부러 그렇게 한다. `requestId` 는 **스테이션 범위에서만 유일**하고 교환의 상관키는
 * `(stationId, requestId)` 복합키다. 20 대가 같은 `requestId` 로 동시에 교환을
 * 열어도 서로 다른 교환 20 건이어야 하며, 그것이 감사 항목 4 가 확인하는 것이다. 스테이션마다
 * 다른 번호를 주면 이 불변식은 시험되지 않은 채 통과한다.
 *
 * 시계는 CSMS 와 시뮬레이터 양쪽 다 고정이다 — 부하 시험이라고 해서 `sleep` 을 넣지 않는다.
 * 실시간이 필요한 곳은 실제 소켓 왕복뿐이다.
 */
object LoadScenario {

    /** ★ 성공 기준 S4 가 적은 숫자. 줄이지 않는다. */
    const val STATION_COUNT = 20

    /** 스테이션마다 완주시킬 교환 건수. */
    const val ROUNDS = 3

    /** 감사 대상 교환 건수 — 20 × 3. */
    const val EXPECTED_SWAPS = STATION_COUNT * ROUNDS

    /** 감사 게이트 실행마다 고유하지만, Spring context 기동 전에는 확정되는 실행 id. */
    val RUN_ID = "CS-LOAD-${System.nanoTime()}"

    val RUN_STATION_IDS: List<String> = stationIds(RUN_ID)

    /** `application.yml` 의 인가 목록에 있는 토큰. 없는 토큰이면 S01 이 거부된다. */
    val AUTHORIZED_TOKEN = IdToken("RFID-0001", "ISO14443")

    /** 디바이스 모델 `BatterySwapCtrlr.IdToken` — 충전 트랜잭션용 대체 토큰 (S04.FR.02/03). */
    const val CHARGING_TOKEN = "BSS-CENTRAL-0001"

    /** 라운드 하나가 쓰는 슬롯 수 — 입고 2 + 출고 2 (배터리는 세트 단위다). */
    const val SLOTS_PER_ROUND = 4

    fun stationId(index: Int): String = "CS-LOAD-%02d".format(index)

    fun stationId(runId: String, index: Int): String = "$runId-%02d".format(index)

    val stationIds: List<String> = (1..STATION_COUNT).map(::stationId)

    fun stationIds(runId: String): List<String> = (1..STATION_COUNT).map { stationId(runId, it) }

    /** **순서를 섞는다**. 홀수는 통상 순서, 짝수는 역순이다. */
    fun order(index: Int): SwapOrder = if (index % 2 == 0) SwapOrder.OUT_IN else SwapOrder.IN_OUT

    fun insertSlots(round: Int): List<Int> = listOf(round * SLOTS_PER_ROUND + 1, round * SLOTS_PER_ROUND + 2)

    fun dispenseSlots(round: Int): List<Int> = listOf(round * SLOTS_PER_ROUND + 3, round * SLOTS_PER_ROUND + 4)

    /** 라운드마다 하나. **스테이션이 달라도 같은 값이다** — 위 KDoc 참조. */
    fun requestId(round: Int): Int = 9000 + round

    /** 이용자가 가져온 헌 배터리 — 스테이션·라운드마다 다른 일련번호다. */
    fun incoming(index: Int, round: Int): List<SimBattery> = listOf(
        SimBattery("BAT-IN-%02d-%d-A".format(index, round), soC = 20.0 + round, soH = 85.0),
        SimBattery("BAT-IN-%02d-%d-B".format(index, round), soC = 30.0 + round, soH = 87.0),
    )

    /** 스테이션이 내줄 배터리 — 충전이 끝난 상태로 출고 슬롯에 꽂혀 있다. */
    fun dispensed(index: Int, round: Int): Map<Int, SimBattery> {
        val slots = dispenseSlots(round)
        return mapOf(
            slots[0] to SimBattery("BAT-OUT-%02d-%d-C".format(index, round), soC = 95.0, soH = 95.0),
            slots[1] to SimBattery("BAT-OUT-%02d-%d-D".format(index, round), soC = 92.0, soH = 91.0),
        )
    }

    /** 시뮬레이터도 고정 시계를 쓴다 — 양쪽 다 결정적이어야 한다. */
    fun clock(): Clock = Clock.fixed(FixedClockConfig.FIXED_NOW, ZoneOffset.UTC)

    /**
     * 스테이션 [index] 가 라운드 [round] 에 쓰는 구성.
     *
     * 충전 트랜잭션의 토큰을 절반씩 갈라 둔다 — Part 6 은 `Central`(설정된 토큰)과
     * `NoAuthorization`(빈 문자열) 둘 다 허용하고, 부하 중에도 두 형태가 함께 오간다.
     */
    fun config(port: Int, index: Int, round: Int, runId: String? = null): StationSimConfig {
        val dispensed = dispensed(index, round)
        val slots = (insertSlots(round) + dispenseSlots(round)).map { slotId ->
            SlotConfig(slotId = slotId, battery = dispensed[slotId])
        }
        return StationSimConfig(
            csmsUrl = "ws://localhost:$port/ocpp",
            stationId = runId?.let { stationId(it, index) } ?: stationId(index),
            password = TestCredentials.PASSWORD,
            slots = slots,
            idToken = AUTHORIZED_TOKEN,
            requestId = requestId(round),
            insertSlots = insertSlots(round),
            dispenseSlots = dispenseSlots(round),
            incomingBatteries = incoming(index, round),
            swapOrder = order(index),
            chargingIdToken = if (index % 3 == 0) null else CHARGING_TOKEN,
        )
    }

    fun simulator(port: Int, index: Int, round: Int, runId: String? = null): StationSimulator =
        StationSimulator(config(port, index, round, runId), clock = clock())
}
