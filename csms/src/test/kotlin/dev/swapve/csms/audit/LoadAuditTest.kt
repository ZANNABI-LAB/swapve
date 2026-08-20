package dev.swapve.csms.audit

import dev.swapve.csms.event.JdbcOcppEventLog
import dev.swapve.csms.support.BasicAuthStations
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.swap.ChargingTransactionRegistry
import dev.swapve.csms.swap.SlotStateRegistry
import dev.swapve.csms.swap.SwapTransactionRegistry
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.session.SessionRegistry
import dev.swapve.station.StationSimulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ★★ **성공 기준 S4 — 부하 뒤의 불변식 감사** (게이트 L3 게이트 `./gradlew auditTest`).
 *
 * > *"스테이션 20대 동시 접속 후 불변식 감사 전항목 통과 — 배터리 수량 보존, 슬롯 이중 예약 0,
 * > 유실 메시지 0"*
 *
 * ### 무엇이 실제로 동시에 도는가
 *
 * 스테이션 20 대가 **실제 WebSocket 으로** 동시에 붙는다. 인프로세스지만 소켓은 진짜고,
 * 프레임은 M1 코덱 → M2 스키마 검증 → M4 세션(멱등·per-station 직렬화) → 라우터 → M3
 * 상태머신 전 경로를 그대로 지난다. 20 대가 정말로 **함께 붙어 있는 상태**였다는 것은
 * 추정이 아니라 [SessionRegistry] 관측으로 확인한다.
 *
 * 라운드마다 20 대가 각자 교환 1 건을 완주하고, [LoadScenario.ROUNDS] 회 반복한다.
 * 순서는 **In-Out 과 Out-In 이 섞인다**.
 *
 * ### 몇 분씩 걸리지 않는다
 *
 * 시계는 CSMS 와 시뮬레이터 양쪽 다 고정이고 `sleep` 이 없다. 실시간이 필요한 것은 소켓
 * 왕복뿐이라 부하 전체가 초 단위로 끝난다. "오래 걸려야 부하 시험"이 아니다 — 필요한 것은
 * **동시성**이지 벽시계 시간이 아니다.
 *
 * ### `known-battery-serials` 를 비우는 이유
 *
 * 운영 설정에는 시드 재고 8 개가 들어 있고, 목록이 비어 있지 않으면 CSMS 는 목록 밖 배터리를
 * `customData` 로 거부한다 (F3). 부하는 스테이션·라운드마다 **다른 일련번호**를
 * 써야 배터리 수량 보존을 의미 있게 셀 수 있으므로, 여기서는 배터리 식별을 끈다. F3 자체는
 * 적합성 게이트(`FailureScenarioTest`)가 이미 시험한다 — 두 관심사를 한 시험에 섞지 않는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["csms.known-battery-serials="],
)
@Import(FixedClockConfig::class)
@ContextConfiguration(initializers = [BasicAuthStations::class])
@Tag("audit")
class LoadAuditTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var swaps: SwapTransactionRegistry

    @Autowired
    private lateinit var slotStates: SlotStateRegistry

    @Autowired
    private lateinit var chargingTransactions: ChargingTransactionRegistry

    @Autowired
    private lateinit var eventLog: JdbcOcppEventLog

    @Autowired
    private lateinit var sessions: SessionRegistry

    @Autowired
    private lateinit var validator: OcppPayloadValidator

    private lateinit var runStationIds: List<String>

    @Test
    fun `스테이션 20대가 동시에 교환을 완주하고 불변식 감사를 전항목 통과한다`() {
        val elapsedMillis = runLoad()

        val replayed = runStationIds.map { stationId ->
            EventLogReplay.replay(stationId, eventLog.of(stationId))
        }

        val report = InvariantAudit(replayed, swaps, slotStates, chargingTransactions, validator).run()
        println()
        println(report.render())
        println(" 부하 소요: ${elapsedMillis}ms (스테이션 ${LoadScenario.STATION_COUNT}대 동시 × ${LoadScenario.ROUNDS}라운드)")
        println()

        // 감사 이전에 모집단부터 확인한다. 교환이 덜 돌았는데 전항목 통과하는 것은 통과가 아니다.
        val completed = replayed.sumOf { station -> station.swaps.values.count { it.isCompleted } }
        assertEquals(
            LoadScenario.EXPECTED_SWAPS,
            completed,
            "완주한 교환 수가 다르다 — 부하가 계획대로 돌지 않았다",
        )

        assertTrue(
            report.failures.isEmpty(),
            "불변식 감사가 실패했다:\n" + report.failures.joinToString("\n") { item ->
                "  ✗ ${item.name} (${item.basis}) — 위반 ${item.violations.size}건, 검사 ${item.checked}${item.unit}\n" +
                    item.violations.joinToString("\n") { "      $it" }
            },
        )
    }

    /**
     * 부하 본체 — 라운드마다 20 대를 **동시에** 붙이고, 붙은 사실을 확인한 뒤 교환을 시작한다.
     *
     * 접속과 교환을 나눈 이유는 하나다. 각자 붙자마자 진행하게 두면 **"20 대가 동시에 붙어
     * 있었다"가 우연**이 된다 — 빠른 스테이션이 이미 끝난 뒤에 느린 스테이션이 붙어도 시험은
     * 초록이다. 접속을 모두 마친 다음 교환을 함께 시작하면 그 겹침이 보장되고,
     * [SessionRegistry] 관측이 그것을 사실로 남긴다.
     *
     * @return 부하에 걸린 밀리초. 보고용이지 단언 대상이 아니다.
     */
    private fun runLoad(): Long {
        val runId = LoadScenario.RUN_ID
        runStationIds = LoadScenario.RUN_STATION_IDS
        val startedAt = System.nanoTime()

        runBlocking {
            repeat(LoadScenario.ROUNDS) { round ->
                val simulators = (1..LoadScenario.STATION_COUNT).map { index ->
                    LoadScenario.simulator(port, index, round, runId)
                }

                try {
                    connectAll(simulators)

                    val connected = awaitConnected(LoadScenario.STATION_COUNT)
                    assertEquals(
                        LoadScenario.STATION_COUNT,
                        connected,
                        "라운드 $round: 20대가 동시에 붙어 있지 않다",
                    )

                    coroutineScope {
                        simulators.map { simulator ->
                            async(Dispatchers.IO) { simulator.bootAndSwap() }
                        }.awaitAll()
                    }
                } finally {
                    simulators.forEach { it.close() }
                }
            }
        }

        return (System.nanoTime() - startedAt) / 1_000_000
    }

    private suspend fun connectAll(simulators: List<StationSimulator>) = coroutineScope {
        simulators.map { simulator -> async(Dispatchers.IO) { simulator.connect() } }.awaitAll()
    }

    /**
     * 20 대의 접속이 [SessionRegistry] 에 **나타날 때까지** 기다린 뒤, 관측한 수를 돌려준다.
     *
     * 기다리는 이유는 하나다. 시뮬레이터의 `connect()` 는 **101 응답을 받은 시점**에 반환되는데,
     * 서버 쪽 `afterConnectionEstablished` 의 등록은 그보다 뒤에 다른 스레드에서 일어난다.
     * 그 틈 때문에 20 대가 정말로 붙어 있는데도 18 대로 세이는 일이 있었다 — 기다리지 않으면
     * 시험이 **기계의 코어 수에 따라 흔들린다**(2 코어 CI 러너에서 특히).
     *
     * 대기가 보장을 약화시키지는 않는다. 여기서 기다리는 것은 **관측의 지연**이지 접속 자체가
     * 아니고, 교환은 여전히 20 대가 모두 붙은 것을 확인한 다음에 시작한다. 초과분(20 대보다
     * 많이 잡히는 경우)은 대기 대상이 아니라 그대로 호출부의 단언에 걸린다.
     */
    private suspend fun awaitConnected(expected: Int): Int {
        val deadlineNanos = System.nanoTime() + OBSERVE_TIMEOUT_MILLIS * 1_000_000
        var connected = countConnected()
        while (connected < expected && System.nanoTime() < deadlineNanos) {
            delay(OBSERVE_POLL_MILLIS)
            connected = countConnected()
        }
        return connected
    }

    private fun countConnected(): Int =
        sessions.connectedStationIds.count { it in runStationIds }

    private companion object {
        /** 접속 관측을 기다리는 상한. 넘기면 그냥 관측한 수로 단언에 들어가 실패로 남는다. */
        const val OBSERVE_TIMEOUT_MILLIS = 5_000L

        /** 관측 간격. 붙는 즉시 진행하려는 값이지 대기 시간을 늘리려는 값이 아니다. */
        const val OBSERVE_POLL_MILLIS = 10L
    }
}
