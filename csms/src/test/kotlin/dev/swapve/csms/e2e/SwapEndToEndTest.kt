package dev.swapve.csms.e2e

import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.swap.ChargingTransactionRegistry
import dev.swapve.csms.swap.SlotStateRegistry
import dev.swapve.csms.swap.SwapTransactionRegistry
import dev.swapve.ocpp.session.InMemoryOcppEventLog
import dev.swapve.ocpp.session.MessageDirection
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.station.StationSimulator
import dev.swapve.station.SwapOrder
import dev.swapve.swap.SlotId
import dev.swapve.swap.SlotState
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapTransaction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ★ **성공 기준 S1** (PLAN §2) — 시뮬레이터와 CSMS 가 S03 교환 1건을 완주한다.
 *
 * 실제 WebSocket 으로 붙는다. 인프로세스지만 소켓은 진짜이고, 프레임은 M1 코덱 → M2 스키마
 * 검증 → M4 세션(멱등·직렬화) → 라우터 → M3 상태머신 전 경로를 그대로 지난다.
 *
 * **In-Out 과 Out-In 두 순서 모두 완주해야 한다** (PLAN §4.6). 두 순서는 같은 상태 집합을
 * 지나되 진입 순서만 다르고, 상태머신은 어느 쪽인지 모른 채 같은 `Completed` 에 도달한다.
 *
 * 시계는 양쪽 다 고정돼 있고 `sleep` 이 없다 — 시험은 결정적이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
class SwapEndToEndTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var swaps: SwapTransactionRegistry

    @Autowired
    private lateinit var slotStates: SlotStateRegistry

    @Autowired
    private lateinit var chargingTransactions: ChargingTransactionRegistry

    @Autowired
    private lateinit var eventLog: InMemoryOcppEventLog

    // ------------------------------------------------------------------ S1

    @ParameterizedTest(name = "{0} 순서로 교환 1건을 완주한다")
    @EnumSource(SwapOrder::class)
    fun `교환 1건을 완주한다`(order: SwapOrder) {
        val stationId = "CS-COMPLETE-${order.name}"
        val requestId = 4001 + order.ordinal
        runScenario(order, stationId, requestId)

        val station = StationId(stationId)
        val transaction = assertNotNull(swaps.find(station, requestId), "교환이 열리지 않았다")
        val completed = assertIs<SwapTransaction.Completed>(transaction, "교환이 완료되지 않았다: $transaction")

        // 들어온 수 = 나간 수 (PLAN §5.3 COMPLETED 불변식).
        assertEquals(SwapScenario.INCOMING.size, completed.batteriesIn.size)
        assertEquals(completed.batteriesIn.size, completed.batteriesOut.size)

        // 인가 시각은 `AuthorizeResponse(Accepted)` 를 낸 시각이지 배터리 투입 시각이 아니다 (PLAN §11.2).
        assertEquals(FixedClockConfig.FIXED_NOW, completed.authorizedAt)
        assertEquals(SwapScenario.AUTHORIZED_TOKEN, completed.idToken)
    }

    @ParameterizedTest(name = "{0} 순서에서 양쪽 배터리의 serialNumber·soC·soH 가 남는다")
    @EnumSource(SwapOrder::class)
    fun `양쪽 배터리 정보가 보존된다`(order: SwapOrder) {
        val stationId = "CS-BATTERY-${order.name}"
        val requestId = 4011 + order.ordinal
        runScenario(order, stationId, requestId)

        val completed = assertIs<SwapTransaction.Completed>(swaps.find(StationId(stationId), requestId))

        // 들어온 배터리 — 이용자가 가져온 헌 배터리 (PLAN §11.2: 과금은 양쪽 SoC 차이로 계산된다).
        val incoming = completed.batteriesIn.sortedBy { it.slotId.value }
        assertEquals(SwapScenario.INSERT_SLOTS, incoming.map { it.slotId.value })
        assertEquals(SwapScenario.INCOMING.map { it.serialNumber }, incoming.map { it.serialNumber })
        assertEquals(SwapScenario.INCOMING.map { it.soC }, incoming.map { it.soC })
        assertEquals(SwapScenario.INCOMING.map { it.soH }, incoming.map { it.soH })

        // 나간 배터리 — 스테이션이 내준 충전된 배터리. **입고 슬롯과 다른 슬롯이다** (PLAN §7.1).
        val outgoing = completed.batteriesOut.sortedBy { it.slotId.value }
        assertEquals(SwapScenario.DISPENSE_SLOTS, outgoing.map { it.slotId.value })
        outgoing.forEach { battery ->
            val expected = assertNotNull(SwapScenario.DISPENSED[battery.slotId.value])
            assertEquals(expected.serialNumber, battery.serialNumber)
            assertEquals(expected.soC, battery.soC)
            assertEquals(expected.soH, battery.soH)
        }
    }

    @ParameterizedTest(name = "{0} 순서에서 슬롯 상태가 도메인 어휘로 남는다")
    @EnumSource(SwapOrder::class)
    fun `슬롯 점유 상태가 반전 없이 반영된다`(order: SwapOrder) {
        val stationId = "CS-SLOT-${order.name}"
        val requestId = 4021 + order.ordinal
        runScenario(order, stationId, requestId)
        val station = StationId(stationId)

        // 배터리를 **넣은** 슬롯은 이제 배터리를 갖고 있다.
        SwapScenario.INSERT_SLOTS.forEach { slotId ->
            assertEquals(SlotState.HOLDS_BATTERY, slotStates.stateOf(station, SlotId(slotId)), "슬롯 $slotId")
        }
        // 배터리를 **뺀** 슬롯은 비었다.
        SwapScenario.DISPENSE_SLOTS.forEach { slotId ->
            assertEquals(SlotState.EMPTY, slotStates.stateOf(station, SlotId(slotId)), "슬롯 $slotId")
        }
    }

    // ------------------------------------------------------------------ 두 생명주기의 분리 (PLAN §5.1)

    @Test
    fun `교환 트랜잭션과 충전 트랜잭션이 별개로 기록된다`() {
        val stationId = "CS-E2E-SPLIT"
        val requestId = 4101
        runScenario(SwapOrder.IN_OUT, stationId, requestId)
        val station = StationId(stationId)

        // 교환은 하나. 상관키는 (stationId, requestId) 다.
        assertEquals(1, swaps.of(station).size)

        // 충전은 슬롯 4개짜리 — 부팅 시 배터리가 있던 2개(S04.FR.11) + 새로 투입된 2개.
        val charging = chargingTransactions.of(station)
        assertEquals(4, charging.size, "충전 트랜잭션 수")

        // 내준 배터리의 충전은 **끝났고**, 새로 들어온 배터리의 충전은 **계속 돈다.**
        // 교환이 이미 Completed 인데도 그렇다 — 그게 두 생명주기가 독립이라는 뜻이다
        // (PLAN §5.1: *"들어온 배터리의 충전은 교환이 끝난 뒤에도 며칠 계속된다"*).
        assertIs<SwapTransaction.Completed>(swaps.find(station, requestId))
        assertEquals(2, charging.count { it.isEnded })
        assertEquals(2, charging.count { !it.isEnded })

        // 키가 서로 다르다. 교환은 requestId 로, 충전은 transactionId 로 찾는다.
        charging.forEach { transaction ->
            assertNotNull(chargingTransactions.find(station, transaction.transactionId))
            assertTrue(
                swaps.of(station).keys.none { it.requestId.value.toString() == transaction.transactionId },
                "교환 상관키와 충전 트랜잭션 식별자가 같은 공간을 쓴다",
            )
        }
    }

    // ------------------------------------------------------------------ 이벤트 로그 (PLAN §11.1)

    @Test
    fun `이벤트 로그에 교환 전체가 원문으로 남는다`() {
        val stationId = "CS-E2E-LOG"
        val requestId = 4102
        runScenario(SwapOrder.IN_OUT, stationId, requestId)

        val records = eventLog.of(stationId)
        assertTrue(records.isNotEmpty(), "이벤트 로그가 비어 있다")

        // 순번은 스테이션 안에서 1 부터 이어진다.
        assertEquals((1L..records.size).toList(), records.map { it.seq })

        val swapCalls = records.filter {
            it.direction == MessageDirection.INBOUND && it.action == BatterySwapWire.BATTERY_SWAP
        }
        assertEquals(2, swapCalls.size, "BatteryIn 과 BatteryOut 이 원문으로 남아야 한다")
        assertTrue(swapCalls.any { BatterySwapWire.BATTERY_IN in it.payload })
        assertTrue(swapCalls.any { BatterySwapWire.BATTERY_OUT in it.payload })
        // 원문이다 — 프레임 전체가 그대로 남는다 (`[2,"<id>","BatterySwap",{...}]`).
        swapCalls.forEach { assertContains(it.payload, "\"requestId\":$requestId") }

        // 교환에 필요한 나머지 메시지도 전부 기록됐다.
        val inboundActions = records.filter { it.direction == MessageDirection.INBOUND }.map { it.action }
        listOf(
            BatterySwapWire.BOOT_NOTIFICATION,
            BatterySwapWire.NOTIFY_EVENT,
            BatterySwapWire.SECURITY_EVENT_NOTIFICATION,
            BatterySwapWire.AUTHORIZE,
            BatterySwapWire.TRANSACTION_EVENT,
        ).forEach { assertContains(inboundActions, it) }

        // 우리가 낸 응답도 남는다. 방향이 둘 다 있어야 상태를 로그만으로 재구성할 수 있다.
        assertTrue(records.any { it.direction == MessageDirection.OUTBOUND })
    }

    // ------------------------------------------------------------------ 공통

    /**
     * 붙어서 부팅하고 교환 1건을 완주한다.
     *
     * 충전 트랜잭션의 토큰을 순서에 따라 갈라 둔다 — Part 6 은 `Central`(설정된 토큰)과
     * `NoAuthorization`(빈 문자열) 둘 다 허용하므로, 두 형태가 실제로 오가는 것을 이 시험이
     * 함께 확인한다.
     */
    private fun runScenario(order: SwapOrder, stationId: String, requestId: Int): StationSimulator {
        val config = SwapScenario.config(
            port = port,
            stationId = stationId,
            order = order,
            requestId = requestId,
            chargingIdToken = if (order == SwapOrder.OUT_IN) null else SwapScenario.CHARGING_TOKEN,
        )
        val simulator = SwapScenario.simulator(config)
        simulator.use {
            runBlocking {
                it.connect()
                it.bootAndSwap()
            }
        }
        return simulator
    }
}
