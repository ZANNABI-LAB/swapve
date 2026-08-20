package dev.swapve.csms.conformance

import dev.swapve.csms.conformance.ConformanceScenario.callPayload
import dev.swapve.csms.conformance.ConformanceScenario.resultPayload
import dev.swapve.csms.conformance.ConformanceScenario.stationReceived
import dev.swapve.csms.conformance.ConformanceScenario.stationSent
import dev.swapve.csms.support.BasicAuthStations
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.support.TestStations
import dev.swapve.csms.swap.OutTimedOutLedger
import dev.swapve.csms.swap.RemoteSwapStart
import dev.swapve.csms.swap.RemoteSwapStarter
import dev.swapve.csms.swap.SwapTransactionRegistry
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.swap.BatteryRejectionReason
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.station.FaultInjection
import dev.swapve.station.SimStep
import dev.swapve.station.SimulatedFault
import dev.swapve.station.StationSimulator
import dev.swapve.swap.AnomalyReason
import dev.swapve.swap.IgnoreReason
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapKey
import dev.swapve.swap.SwapRequestId
import dev.swapve.swap.SwapTransaction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ★★ **실패 시나리오 F1~F6 — 성공 기준 S3** (실패 시나리오).
 *
 * ### 무엇을 새로 증명하는가
 *
 * F2·F4·F5·F6 의 **판정 로직은 M3 상태머신과 M4 세션 계층에 이미 있고, 단위 시험도 있다.**
 * 여기서 다시 구현하지 않는다. M7 이 하는 일은 그것들이 **실제 WebSocket 연결 위에서
 * 끝까지 동작함을 증명**하는 것이다 — 프레이밍 · 스키마 검증 · 멱등 원장 · per-station
 * 직렬화 · 라우터 · 상태머신 전 경로를 지나서도 같은 결론이 나오는가.
 *
 * ### 공통 규칙 — 모든 위반은 CALLRESULT 로 정상 응답한다
 *
 * `BatterySwapResponse` 는 거부할 수 없다. 인가가 없어도(F5), 중복이어도(F4)
 * 응답은 정상 회신이고 사실만 기록에 남는다. 거부가 필요하면 §4.8 customData 를 쓴다(F3).
 */
@Tag("conformance")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
@ContextConfiguration(initializers = [BasicAuthStations::class])
class FailureScenarioTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var remoteSwapStarter: RemoteSwapStarter

    @Autowired
    private lateinit var swaps: SwapTransactionRegistry

    @Autowired
    private lateinit var outTimedOutLedger: OutTimedOutLedger

    @Autowired
    private lateinit var validator: OcppPayloadValidator

    /** F2 의 "재시작 후에도 남는가"를 확인할 때 저장소를 새로 열기 위해 쓴다. */
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    // ================================================================== F1

    /**
     * **F1 — 배터리 부족.** 스테이션이 `Rejected` + `NoBatteryAvailable`, CSMS 가 기록한다.
     *
     * 이것이 곧 `TC_S_102_CSMS` 다 (표). 전 시퀀스 단언은 그 시험에 있고,
     * 여기서는 **F1 이 실패 시나리오 목록의 한 항목으로서** 실제 연결 위에서 도는지 본다.
     */
    @Test
    fun `F1 — 배터리가 부족하면 스테이션이 거부하고 CSMS 가 기록한다`() {
        val stationId = TestStations.F1_NO_BATTERY
        val simulator = ConformanceScenario.simulator(
            ConformanceScenario.emptyStationConfig(port, stationId),
        )

        val outcome = simulator.use {
            runBlocking {
                it.connect()
                it.boot()
                remoteSwapStarter.start(StationId(stationId), ConformanceScenario.VALID_ID_TOKEN)
            }
        }

        val rejected = assertIs<RemoteSwapStart.Rejected>(outcome)
        assertEquals(BatteryRejectionReason.NO_BATTERY_AVAILABLE, rejected.rejection.reason)
        assertTrue(
            swaps.rejections().any { it.key.stationId.value == stationId },
            "거부가 기록되지 않았다",
        )
    }

    // ================================================================== F2

    /**
     * **F2 — 수령 타임아웃.** `BatteryOutTimeout` 수신 → `OUT_TIMED_OUT` + **영속 기록**.
     *
     * > **S03.FR.06** — *"Situation needs to be reported, because CSMS ends up with an
     * > **orphan BatteryIn for which a BatteryOut is missing**."*
     *
     * 두 종류의 타임아웃 중 CSMS 가 통보받는 쪽이다. 인가 후 배터리를 넣지 않은
     * 타임아웃은 스테이션이 자체 종료하고 CSMS 는 **아무것도 받지 못하므로** 여기서 시험할
     * 대상 자체가 없다.
     */
    @Test
    fun `F2 — BatteryOutTimeout 이 OUT_TIMED_OUT 으로 남고 장부 불균형이 영속된다`() {
        val stationId = TestStations.F2_OUT_TIMEOUT
        val requestId = runSwapUntilBatteryInThen(stationId) { it.reportBatteryOutTimeout() }
        val key = SwapKey(StationId(stationId), SwapRequestId(requestId))

        // 인메모리 상태 — 장부가 반쪽으로 끝났다.
        val state = assertIs<SwapTransaction.OutTimedOut>(
            swaps.find(StationId(stationId), requestId),
            "OUT_TIMED_OUT 이 아니다: ${swaps.find(StationId(stationId), requestId)}",
        )
        assertEquals(2, state.ledgerImbalance, "입고 2개에 대응하는 출고가 없다")

        // ★ 영속 — 불변식 가운데 이 한 줄에만 "영속 저장 필요"가 달려 있다.
        val persisted = assertNotNull(outTimedOutLedger.find(key), "장부가 영속되지 않았다")
        assertEquals(2, persisted.ledgerImbalance)
        assertEquals(ConformanceScenario.VALID_ID_TOKEN, persisted.idToken)

        // orphan 배터리의 serialNumber·soC·soH 가 양쪽 다 보존됐다 (과금 근거).
        val serials = persisted.orphanBatteriesIn.map { it.serialNumber }.sorted()
        assertEquals(listOf("1234", "5678"), serials)
        val bySerial = persisted.orphanBatteriesIn.associateBy { it.serialNumber }
        assertEquals(23.0, assertNotNull(bySerial["1234"]).soC)
        assertEquals(85.0, assertNotNull(bySerial["1234"]).soH)
    }

    /**
     * ★ **F2 의 나머지 절반 — 프로세스가 죽어도 남는다** (*"영속 저장 필요"*).
     *
     * 위 시험이 확인하는 것은 **"썼다"이지 "남는다"가 아니다.** 인메모리 DB 였다면 위 단언은
     * 그대로 통과하면서도 요구를 만족하지 못한다. 그래서 여기서는 두 가지를 따로 확인한다:
     *
     * 1. **디스크에 파일이 있다** — H2 가 실제로 파일 기반으로 돌고 있다.
     * 2. **애플리케이션의 커넥션 풀 밖에서도 읽힌다** — Spring 이 만든 `DataSource` 가 아니라
     *    같은 URL 로 **새로 연 독립 커넥션**으로 조회한다. 값이 돌아온다는 것은 그것이
     *    이 애플리케이션의 힙이나 세션 캐시가 아니라 **저장 매체에서 왔다**는 뜻이다.
     *
     * 진짜 프로세스 재시작을 시험 안에서 재현할 수는 없다. 대신 그것이 성립하기 위한 조건
     * (파일에 있고, 커넥션을 새로 열어도 읽힌다)을 직접 확인한다.
     */
    @Test
    fun `F2 — 장부 불균형이 애플리케이션 커넥션 밖에서도 읽힌다`() {
        val stationId = TestStations.F2_PERSISTENT
        val requestId = runSwapUntilBatteryInThen(stationId) { it.reportBatteryOutTimeout() }
        val key = SwapKey(StationId(stationId), SwapRequestId(requestId))

        // 1. 파일 기반인가. 인메모리였다면 여기서 즉시 빨개진다.
        val url = jdbcTemplate.execute(ConnectionCallback { it.metaData.url }).orEmpty()
        assertTrue(url.startsWith("jdbc:h2:file:"), "H2 가 파일 기반이 아니다: $url")
        assertTrue(
            Path.of(url.removePrefix("jdbc:h2:file:").substringBefore(';') + ".mv.db").exists(),
            "H2 데이터 파일이 디스크에 없다: $url",
        )

        // 2. 애플리케이션 커넥션 풀 밖에서 같은 파일을 새로 열어 읽는다.
        val independent = DriverManagerDataSource(url, "sa", "")
        val reopened = OutTimedOutLedger(JdbcTemplate(independent))

        val record = assertNotNull(reopened.find(key), "새 커넥션에서 장부가 보이지 않는다")
        assertEquals(2, record.ledgerImbalance)
        assertEquals(ConformanceScenario.VALID_ID_TOKEN, record.idToken)
        assertTrue(reopened.all().any { it.key == key })
    }

    // ================================================================== F3

    /**
     * **F3 — 미등록 배터리.** CSMS 가 `BatterySwapResponse.customData` 로
     * `Rejected` / `BatteryUnknown` 을 알린다 (확정 결정 결정 #7).
     *
     * ### 이 시험이 확인하는 두 가지
     *
     * 1. **거부가 실제로 나간다** — 응답의 `customData` 에 벤더 식별자·상태·사유가 실린다.
     * 2. ★ **그 응답이 공식 스키마를 통과한다.** OCA 가 정한 우회가 정말로 표준 스키마
     *    안에서 성립하는지는 주장이 아니라 실행되는 검사여야 한다. `CustomDataType` 에
     *    `additionalProperties: false` 가 없다는 사실에 기대는 구조라, 그 전제가 깨지면
     *    여기서 즉시 빨개진다.
     *
     * 그리고 **CALLERROR 로 답하지 않는다** — 응답의 성격은 여전히 수신 확인이다 (§4.3).
     */
    @Test
    fun `F3 — 미등록 배터리는 customData 로 거부되고 그 응답이 공식 스키마를 통과한다`() {
        val stationId = TestStations.F3_UNKNOWN_BATTERY
        val simulator = ConformanceScenario.simulator(
            ConformanceScenario.unknownBatteryConfig(port, stationId),
        )

        simulator.use {
            runBlocking {
                it.connect()
                it.boot()
                remoteSwapStarter.start(StationId(stationId), ConformanceScenario.VALID_ID_TOKEN)
                it.runRemoteSwap()
            }
        }

        val records = simulator.eventLog.of(stationId)
        val responses = records.stationReceived().filter { it.action == BatterySwapWire.BATTERY_SWAP }
        assertTrue(responses.isNotEmpty(), "BatterySwapResponse 가 없다")

        // application.yml 의 known-battery-serials 에 없는 배터리가 이 시나리오에 있다.
        val rejection = assertNotNull(
            responses.map { it.resultPayload() }.firstOrNull { !it.path("customData").isMissingNode },
            "미등록 배터리가 있는데 customData 거부가 붙지 않았다",
        )
        val customData = rejection.path("customData")
        assertEquals(BatterySwapWire.VENDOR_ID_BATTERY_SWAP_RESPONSE, customData.path("vendorId").asText())
        assertEquals(BatterySwapWire.GENERIC_REJECTED, customData.path("status").asText())
        assertEquals(
            BatteryRejectionReason.BATTERY_UNKNOWN.wireValue,
            customData.path("statusInfo").path("reasonCode").asText(),
        )

        // ★ customData 를 붙여도 공식 스키마를 통과한다.
        ConformanceScenario.assertAllSchemaValid(records, validator)
        // 거부하더라도 CALLERROR 가 아니다.
        ConformanceScenario.assertNoErrorFrames(records)
    }

    // ================================================================== F4

    /**
     * **F4 — 중복 `BatteryIn`.** 같은 `(stationId, requestId)` 재수신 → 멱등 무시.
     *
     * ### messageId 를 **새로** 발번해 보낸다
     *
     * 그래야 M4 의 멱등 원장을 지나 **M3 상태머신**까지 도달한다. 같은 messageId 였다면
     * 세션 계층이 먼저 잡아 버려서 상태머신의 중복 판정은 시험되지 않는다 — 그건 F6 이다.
     * 두 겹의 멱등이 각각 도는지를 갈라 보는 것이 이 시험과 F6 의 차이다.
     */
    @Test
    fun `F4 — 새 messageId 로 온 중복 BatteryIn 을 상태머신이 무시한다`() {
        val stationId = TestStations.F4_DUPLICATE_IN
        val requestId = runSwapUntilBatteryInThen(stationId) { it.resendLastBatterySwap(sameMessageId = false) }

        // 상태는 여전히 반쪽이다 — 두 번째 입고가 반영됐다면 배터리가 4개로 늘었을 것이다.
        val state = assertIs<SwapTransaction.HalfIn>(swaps.find(StationId(stationId), requestId))
        assertEquals(2, state.batteriesIn.size, "장부가 두 번 늘었다")

        // 무시된 사실이 기록으로 남는다 — 실패가 아니라 정상적으로 일어나는 일이다.
        assertTrue(
            swaps.ignoredEvents().any {
                it.key.stationId.value == stationId && it.reason == IgnoreReason.DUPLICATE_BATTERY_IN
            },
            "멱등 무시가 기록되지 않았다: ${swaps.ignoredEvents()}",
        )
    }

    // ================================================================== F5

    /**
     * **F5 — 순서 위반.** `AUTHORIZED` 없이 `BatterySwap` 이 도착 →
     * 이상 이벤트 기록, **응답은 정상 회신.**
     *
     * 인가를 건너뛰고 곧바로 배터리를 넣는다. `RequestBatterySwap` 도 `Authorize` 도 없으므로
     * CSMS 에는 이 교환의 인가 기록이 없다.
     */
    @Test
    fun `F5 — 인가 없이 도착한 BatterySwap 은 이상으로 기록되되 정상 회신된다`() {
        val stationId = TestStations.F5_NOT_AUTHORIZED
        val requestId = 50_501
        val simulator = ConformanceScenario.simulator(
            ConformanceScenario.config(port, stationId, requestId = requestId),
        )

        simulator.use {
            runBlocking {
                it.connect()
                it.boot()
                // 인가 없이 곧바로 투입한다. authorize() 도 remoteSwapStarter.start() 도 부르지 않는다.
                it.insertBatteries()
            }
        }

        // 상태는 전이하지 않았다 — Idle 에 머문다.
        val state = swaps.find(StationId(stationId), requestId)
        assertTrue(
            state == null || state is SwapTransaction.Idle,
            "인가 없는 교환이 전이했다: $state",
        )

        // 이상으로 기록됐다.
        assertTrue(
            swaps.anomalies().any {
                it.key.stationId.value == stationId && it.reason == AnomalyReason.NOT_AUTHORIZED
            },
            "이상 이벤트가 기록되지 않았다: ${swaps.anomalies()}",
        )

        // ★ 그럼에도 응답은 정상 회신됐다 — BatterySwap 은 거부할 수 없다.
        val records = simulator.eventLog.of(stationId)
        assertTrue(
            records.stationReceived().any { it.action == BatterySwapWire.BATTERY_SWAP },
            "BatterySwapResponse 가 회신되지 않았다",
        )
        ConformanceScenario.assertNoErrorFrames(records)
    }

    // ================================================================== F6

    /**
     * **F6 — 재접속 중 재전송.** WebSocket 재연결 후 CALL 재전송 → 멱등 처리, 장부 무결.
     *
     * ### 이것이 M4·M7 의 핵심 위험이다
     *
     * > *"재접속 시 스테이션이 CALL 을 재전송하면 중복 `BatterySwap` 이 발생하고, 멱등
     * > 처리가 없으면 장부가 깨진다."*
     *
     * 시나리오: 입고까지 끝낸 뒤 **출고 직전에 연결이 끊긴다**(장애 주입). 스테이션은
     * 응답을 못 받았다고 판단해 재접속 후 **같은 messageId 로** `BatteryIn` 을 재전송한다.
     * M4 의 [dev.swapve.ocpp.session.InboundCallLedger] 가 `(stationId, messageId)` 로
     * 이것을 잡아 저장된 응답을 그대로 회신하고 **상위 계층을 부르지 않는다.**
     *
     * 멱등 원장이 세션이 아니라 스테이션 수명을 따라간다는 M4 의 설계가 여기서 값을 한다.
     */
    @Test
    fun `F6 — 재접속 후 같은 messageId 로 재전송해도 장부가 두 번 늘지 않는다`() {
        val stationId = TestStations.F6_RECONNECT
        val simulator = ConformanceScenario.simulator(
            ConformanceScenario.config(port, stationId),
            faults = FaultInjection.failingAt(SimStep.BATTERY_OUT, "교환 도중 연결이 끊겼다"),
        )

        val requestId = simulator.use {
            runBlocking {
                it.connect()
                it.boot()
                val outcome = remoteSwapStarter.start(StationId(stationId), ConformanceScenario.VALID_ID_TOKEN)
                val accepted = assertIs<RemoteSwapStart.Accepted>(outcome)

                // 입고까지 진행하고 출고 직전에 끊긴다.
                assertFailsWith<SimulatedFault> { it.runRemoteSwap() }

                val batteryIn = assertNotNull(
                    it.eventLog.of(stationId).stationSent()
                        .lastOrNull { record -> record.action == BatterySwapWire.BATTERY_SWAP },
                    "BatteryIn 이 나가지 않았다",
                )
                assertEquals(1, it.repliesTo(batteryIn.messageId), "첫 응답이 오지 않았다")

                it.disconnect()
                it.reconnect()
                // 스테이션이 응답을 못 받았다고 보고 같은 프레임을 그대로 다시 보낸다.
                it.resendLastBatterySwap(sameMessageId = true)

                // ★ 재전송이 **실제로 CSMS 에 닿아 다시 응답받았다.** 이 단언이 없으면
                //   재전송이 소리 없이 사라져도 아래 "장부가 안 늘었다"가 통과한다.
                assertEquals(
                    2,
                    it.repliesTo(batteryIn.messageId),
                    "재전송에 대한 응답이 오지 않았다 — 멱등 원장이 저장된 응답을 다시 내지 않았다",
                )

                accepted.key.requestId.value
            }
        }

        // 장부는 그대로다. 배터리가 4개로 늘지 않았다.
        val state = assertIs<SwapTransaction.HalfIn>(swaps.find(StationId(stationId), requestId))
        assertEquals(2, state.batteriesIn.size, "재전송으로 장부가 두 번 늘었다")

        // 상위 계층이 다시 불리지 않았으므로 상태머신의 중복 판정도 돌지 않았다 —
        // M4 가 그 앞에서 잡았다는 뜻이다 (F4 와 갈리는 지점).
        assertTrue(
            swaps.ignoredEvents().none {
                it.key.stationId.value == stationId && it.reason == IgnoreReason.DUPLICATE_BATTERY_IN
            },
            "재전송이 상태머신까지 도달했다 — 멱등 원장이 잡지 못했다",
        )
    }

    // ------------------------------------------------------------------ 공통

    /**
     * 원격 개시 → 입고까지 진행한 뒤 [then] 을 실행한다.
     *
     * 출고는 하지 않는다 — F2·F4 둘 다 **교환이 반쪽인 상태**에서 무슨 일이 벌어지는지를
     * 묻기 때문이다.
     *
     * @return 이 교환의 상관 번호.
     */
    private fun runSwapUntilBatteryInThen(
        stationId: String,
        then: suspend (StationSimulator) -> Unit,
    ): Int {
        val simulator = ConformanceScenario.simulator(ConformanceScenario.config(port, stationId))
        return simulator.use {
            runBlocking {
                it.connect()
                it.boot()
                val outcome = remoteSwapStarter.start(StationId(stationId), ConformanceScenario.VALID_ID_TOKEN)
                val accepted = assertIs<RemoteSwapStart.Accepted>(outcome, "개시가 거부됐다: $outcome")

                it.awaitRemoteStart()
                it.insertBatteries()
                then(it)

                // 어느 시나리오에서도 오류 프레임은 오가지 않는다.
                ConformanceScenario.assertNoErrorFrames(it.eventLog.of(stationId))
                ConformanceScenario.assertAllSchemaValid(it.eventLog.of(stationId), validator)

                accepted.key.requestId.value
            }
        }
    }
}
