package dev.swapve.csms.conformance

import dev.swapve.csms.conformance.ConformanceScenario.callPayload
import dev.swapve.csms.conformance.ConformanceScenario.stationReceived
import dev.swapve.csms.conformance.ConformanceScenario.stationSent
import dev.swapve.csms.devicemodel.DeviceModelClient
import dev.swapve.csms.devicemodel.DeviceModelQuery
import dev.swapve.csms.devicemodel.DeviceModelReport
import dev.swapve.csms.devicemodel.DeviceModelReportRegistry
import dev.swapve.csms.devicemodel.DeviceModelReportRequest
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.ocpp.swap.DeviceModelVariables
import dev.swapve.ocpp.swap.VariableRef
import dev.swapve.station.StationSimulator
import dev.swapve.swap.StationId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ★★ **`TC_S_104_CS` — 디바이스 모델 전체 재고 보고** (Part 6, UC S04)
 *
 * ### ★ 시험 대상이 `TC_S_102/103_CSMS` 와 **반대**다
 *
 * 여기서 시험받는 것(System under test)은 **Charging Station** 이다. 그래서 역할이 뒤집힌다:
 * **`station-sim` 이 시험 대상을 연기하고, 우리 CSMS 가 시험계(Test System)** 로서 청하고
 * 받아 본다 (PLAN §7.2 — *"시험 대상이 CS 인 케이스가 곧 시뮬레이터의 명세"*).
 *
 * 옆의 두 파일과 정반대이므로 단언을 읽을 때 주의해야 한다. `TcS103CsmsTest` 에서
 * "시뮬레이터가 보낸 것"은 *시험계의 대사*였지만, 여기서는 **시험 대상의 답**이다.
 *
 * ```
 * 1. 시험계(CSMS) → GetBaseReportRequest(requestId, reportBase = FullInventory)
 * 2. 시험 대상(CS) → GetBaseReportResponse(status = Accepted)
 * 3. 시험 대상(CS) → NotifyReportRequest(requestId, seqNo = 0, tbc = true, reportData[…])
 * 4. 시험계(CSMS) → NotifyReportResponse
 *    … seqNo 1, 2 … 같은 쌍이 반복 …
 * n. 시험 대상(CS) → NotifyReportRequest(requestId, seqNo = N, tbc 없음)   ← 마지막
 * ```
 *
 * ### 이 케이스가 잡아내는 것
 *
 * 1. **분할이 실제로 일어나는가.** 한 건에 전부 실어 보내면 `seqNo`·`tbc` 경로가 한 번도
 *    실행되지 않은 채 "구현했다"가 된다. 그래서 **여러 건**임을 먼저 단언한다.
 * 2. **`requestId` 를 되돌리는가.** 청한 쪽이 자기 요청과 짝지을 유일한 수단이다.
 * 3. **재조립한 값이 스테이션의 실제 값과 같은가.** 조각을 잇는 과정에서 순서가 섞이거나
 *    조각이 빠져도 목록은 그럴듯해 보인다 — 값을 대조해야 드러난다. 그 대조를
 *    `GetVariables` 로 한다: **두 경로의 답이 같아야** 보고를 믿을 수 있다.
 * 4. **`BatterySwapCtrlr.SwapOrder` 가 실려 있는가.** 부록 CSV 에 없고 Part 2 본문에만 있는
 *    변수라 빠뜨리기 쉽다 (PLAN §4.9 주의 3, `DeviceModelVariables` KDoc).
 */
@Tag("conformance")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
class TcS104CsTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var deviceModel: DeviceModelClient

    @Autowired
    private lateinit var reports: DeviceModelReportRegistry

    @Autowired
    private lateinit var validator: OcppPayloadValidator

    /** 이 요청의 상관 번호. 되돌아오는지가 단언 대상이라 눈에 띄는 값을 쓴다. */
    private val requestId = 10_401

    // ------------------------------------------------------------------ 전 시퀀스

    @Test
    fun `TC_S_104_CS — GetBaseReport(FullInventory) 하나에 NotifyReport 가 나뉘어 온다`() {
        withStation("CS-TC-S-104") { simulator, stationId ->
            val report = requestFullInventory(simulator, stationId)

            // step 3~n — 한 건으로 끝내지 않았다. 분할이 이 케이스의 본체다.
            val notifications = simulator.notifyReports()
            assertTrue(
                notifications.size >= 2,
                "NotifyReport 가 ${notifications.size} 건뿐이다 — 분할되지 않았다",
            )
            assertEquals(notifications.size, report.parts, "받은 조각 수와 재조립한 조각 수가 다르다")

            // 유실도 중복도 없이 마지막까지 왔다.
            assertTrue(report.isIntact, "보고가 온전하지 않다: missing=${report.missingSeqNos}")
        }
    }

    @Test
    fun `seqNo 는 0 부터 연속이고 마지막 것만 tbc 가 거짓이다`() {
        withStation("CS-TC-S-104-SEQ") { simulator, stationId ->
            requestFullInventory(simulator, stationId)
            val notifications = simulator.notifyReports()

            notifications.forEachIndexed { index, payload ->
                assertEquals(index, payload.path("seqNo").asInt(), "seqNo 가 0 부터 연속이 아니다")

                val last = index == notifications.lastIndex
                assertEquals(
                    !last,
                    payload.path("tbc").asBoolean(false),
                    "${index}번째 조각의 tbc 가 틀렸다 (마지막인가: $last)",
                )
                if (last) {
                    // 마지막 조각은 `tbc: false` 를 적는 대신 **아예 싣지 않는다** —
                    // 스키마의 기본값이 거짓이라 없는 것이 곧 "여기서 끝"이다.
                    assertTrue(!payload.has("tbc"), "마지막 조각이 tbc 를 실었다: $payload")
                }
            }
        }
    }

    @Test
    fun `requestId 를 그대로 되돌린다`() {
        withStation("CS-TC-S-104-REQID") { simulator, stationId ->
            requestFullInventory(simulator, stationId)

            simulator.notifyReports().forEach { payload ->
                assertEquals(requestId, payload.path("requestId").asInt(), "청한 상관 번호가 아니다")
            }
            // 다른 번호로 청한 보고를 잘못 집어 오지 않는다.
            assertTrue(reports.find(StationId(simulator.config.stationId), requestId + 1) == null)
        }
    }

    // ------------------------------------------------------------------ 보고의 내용

    /**
     * **S04 의 변수들과 `SwapOrder` 가 전부 실려 있다.**
     *
     * `Timeout` 을 `In`/`Out` **인스턴스로** 확인하는 것이 요점이다 — 변수 두 개로
     * 모델링했다면 이 단언이 빈다 (PLAN §4.9 주의 1).
     */
    @Test
    fun `보고에 BatterySwapCtrlr 의 변수들과 BatteryCartridge 의 SoC 가 들어 있다`() {
        withStation("CS-TC-S-104-VARS") { simulator, stationId ->
            val report = requestFullInventory(simulator, stationId)

            listOf(
                DeviceModelVariables.targetSoC(),
                DeviceModelVariables.maxSoc(),
                DeviceModelVariables.timeoutIn(),
                DeviceModelVariables.timeoutOut(),
                // 부록 CSV 에 없고 Part 2 본문에만 있는 변수다 (S03.FR.07, PLAN §4.9 주의 3).
                DeviceModelVariables.swapOrder(),
                // 배터리가 든 슬롯의 카트리지 (S04.FR.12).
                DeviceModelVariables.batterySoC(ConformanceScenario.EVSE_C),
            ).forEach { ref ->
                assertTrue(report.contains(ref), "$ref 가 보고에 없다: ${report.variables.map { it.ref }}")
            }

            // 빈 슬롯의 카트리지는 없다 — 없는 배터리를 지어내지 않는다.
            assertTrue(
                !report.contains(DeviceModelVariables.batterySoC(ConformanceScenario.EVSE_A)),
                "배터리가 없는 슬롯의 카트리지가 보고에 있다",
            )
        }
    }

    /**
     * ★ **재조립한 값이 스테이션의 실제 값과 같다.**
     *
     * 대조 상대를 `GetVariables` 의 답으로 삼는다. 시뮬레이터의 내부 상태를 들여다보면
     * "우리 코드가 우리 코드와 같다"를 확인할 뿐이지만, **전선을 두 번 지난 두 경로**를
     * 맞춰 보면 조각을 잇는 과정에서 값이 섞였는지가 드러난다.
     */
    @Test
    fun `재조립 결과가 GetVariables 로 물은 실제 값과 일치한다`() {
        withStation("CS-TC-S-104-VALUES") { simulator, stationId ->
            val report = requestFullInventory(simulator, stationId)

            val refs = report.variables.map { it.ref }
            val query = runBlocking { deviceModel.get(stationId, refs) }
            val answered = assertIs<DeviceModelQuery.Answered>(query, "GetVariables 가 답하지 않았다: $query")

            refs.forEach { ref ->
                assertEquals(
                    answered.valueOf(ref),
                    report.valueOf(ref),
                    "$ref 의 값이 보고와 조회에서 다르다",
                )
            }

            // 설정에서 온 값 몇 개는 스펙 값과 직접 맞춰 둔다 — 두 경로가 **함께** 틀렸을
            // 가능성을 남기지 않는다.
            val config = simulator.config
            assertEquals(config.targetSoC.toString(), report.valueOf(DeviceModelVariables.targetSoC()))
            assertEquals(config.maxSoc.toString(), report.valueOf(DeviceModelVariables.maxSoc()))
            assertEquals(
                config.batteryOutTimeout.seconds.toString(),
                report.valueOf(DeviceModelVariables.timeoutOut()),
            )
            assertEquals(config.swapOrder.wireValue, report.valueOf(DeviceModelVariables.swapOrder()))
            assertEquals(
                ConformanceScenario.DISPENSED_C.soC.toInt().toString(),
                report.valueOf(DeviceModelVariables.batterySoC(ConformanceScenario.EVSE_C)),
                "카트리지 SoC 가 슬롯에 꽂힌 배터리의 값이 아니다",
            )
        }
    }

    /**
     * 대소문자를 무시하고 맞춰 본다 (PLAN §4.9 주의 2).
     *
     * **내보낼 때는 정본 철자, 받아서 맞춰 볼 때는 대소문자 무시**가 규칙이다. 보고에 실려
     * 나간 철자가 정본인지도 함께 확인한다 — 우리가 "고쳐서" 보내면 상대가 못 알아듣는다.
     */
    @Test
    fun `변수 이름은 정본 철자로 실리고 대조는 대소문자를 가리지 않는다`() {
        withStation("CS-TC-S-104-CASE") { simulator, stationId ->
            val report = requestFullInventory(simulator, stationId)

            assertTrue(
                report.variables.any { it.ref.variable == DeviceModelVariables.VARIABLE_TARGET_SOC },
                "정본 철자 TargetSoC 가 아니다: ${report.variables.map { it.ref.variable }}",
            )
            assertTrue(
                report.variables.any { it.ref.variable == DeviceModelVariables.VARIABLE_MAX_SOC },
                "정본 철자 MaxSoc 가 아니다: ${report.variables.map { it.ref.variable }}",
            )

            // 철자를 흐트러뜨려 물어도 같은 변수로 찾힌다.
            assertEquals(
                report.valueOf(DeviceModelVariables.maxSoc()),
                report.valueOf(VariableRef("batteryswapctrlr", "MAXSOC")),
            )
        }
    }

    // ------------------------------------------------------------------ Tool validation

    /** 오간 **모든** 메시지가 공식 스키마를 통과하고, 오류 프레임이 하나도 없다. */
    @Test
    fun `오간 모든 메시지가 공식 스키마를 통과하고 오류 프레임이 없다`() {
        withStation("CS-TC-S-104-SCHEMA") { simulator, stationId ->
            requestFullInventory(simulator, stationId)

            val records = simulator.eventLog.of(simulator.config.stationId)
            ConformanceScenario.assertNoErrorFrames(records)
            ConformanceScenario.assertAllSchemaValid(records, validator)

            // GetBaseReportRequest 가 실제로 FullInventory 를 청했다. **받은 것**만 본다 —
            // 같은 action 으로 우리가 낸 CALLRESULT 도 로그에 있고, 그쪽에는 요청 필드가 없다.
            val request = assertNotNull(
                records.stationReceived()
                    .lastOrNull { it.action == BatterySwapWire.GET_BASE_REPORT }
                    ?.callPayload(),
                "GetBaseReportRequest 가 나가지 않았다",
            )
            assertEquals(BatterySwapWire.REPORT_BASE_FULL_INVENTORY, request.path("reportBase").asText())
            assertEquals(requestId, request.path("requestId").asInt())
        }
    }

    // ------------------------------------------------------------------ 공통

    /**
     * step 1~n — 청하고, 받아들여진 것을 확인하고, 조각을 전부 받는다.
     *
     * ### 청한 뒤에 [StationSimulator.reportFullInventory] 를 부르는 순서가 중요하다
     *
     * 시험 대상은 `GetBaseReportResponse` 를 **먼저** 낸 뒤에야 `NotifyReport` 를 보낼 수
     * 있다 (연결당 in-flight CALL 하나, Part 4 §4.1.1). 시험계가 응답을 받은 뒤에 보고를
     * 나르게 하면 그 순서가 시험에서도 그대로 지켜진다.
     */
    private fun requestFullInventory(simulator: StationSimulator, stationId: StationId): DeviceModelReport =
        runBlocking {
            // step 1: 시험계 → GetBaseReportRequest(FullInventory)
            val answer = deviceModel.getBaseReport(stationId, requestId)

            // step 2: 시험 대상 → GetBaseReportResponse(status = Accepted)
            val answered = assertIs<DeviceModelReportRequest.Answered>(answer, "답이 오지 않았다: $answer")
            assertTrue(answered.isAccepted, "전체 재고 보고를 받아들이지 않았다: ${answered.status}")

            // step 3~n: 시험 대상 → NotifyReportRequest 들
            assertEquals(requestId, simulator.reportFullInventory(), "청한 상관 번호로 보고하지 않았다")

            assertNotNull(reports.find(stationId, requestId), "재조립된 보고가 없다")
        }

    /** 시험 대상이 **보낸** `NotifyReport` 들의 페이로드 — 보낸 순서 그대로. */
    private fun StationSimulator.notifyReports() =
        eventLog.of(config.stationId).stationSent()
            .filter { it.action == BatterySwapWire.NOTIFY_REPORT }
            .map { it.callPayload() }

    /**
     * `TC_S_103_CSMS` 와 **같은 스테이션 구성**을 쓴다.
     *
     * 전제조건이 겹치기 때문이다 — 슬롯 4개 중 둘에 배터리가 있어야 `BatteryCartridge` 가
     * 보고에 실리고 (S04.FR.12), 빈 슬롯 둘이 있어야 "없는 카트리지는 싣지 않는다"가
     * 확인된다. 이 케이스를 위한 구성을 새로 만들면 그 두 성질이 우연히 성립하는 셈이 된다.
     */
    private fun withStation(stationId: String, block: (StationSimulator, StationId) -> Unit) {
        val simulator = ConformanceScenario.simulator(ConformanceScenario.config(port, stationId))
        simulator.use {
            runBlocking {
                it.connect()
                it.boot()
            }
            block(it, StationId(stationId))
        }
    }
}
