package dev.swapve.csms.devicemodel

import dev.swapve.csms.charging.ChargingScenario
import dev.swapve.csms.conformance.ConformanceScenario
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.session.OcppEventRecord
import dev.swapve.ocpp.swap.DeviceModelVariables
import dev.swapve.ocpp.swap.VariableRef
import dev.swapve.ocpp.swap.VariableStatus
import dev.swapve.station.StationSimulator
import dev.swapve.swap.StationId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ★ **디바이스 모델 조회·설정** (S04.FR.05/06/10/12).
 *
 * 실제 소켓 위에서 돈다. CSMS 가 `GetVariablesRequest`/`SetVariablesRequest` 를 M4 의
 * `StationCommandBus` 로 내보내고, 시뮬레이터가 자기 디바이스 모델로 답한다.
 *
 * ### 여기서 확인하는 것
 *
 * - **S04.FR.12** — `BatteryCartridge.SoC` 로 슬롯의 배터리 충전 상태를 묻는다. 스펙이
 *   *"CSMS 가 재고를 알고 싶다면"* 의 수단으로 든 바로 그 길이다.
 * - **S04.FR.06/10** — `MaxSoc < TargetSoC` 는 **스테이션이 거부한다.** CSMS 는 그 답을
 *   받아 기록할 뿐이고, 미리 막지 않는다 (판정 주체의 근거는 `DeviceModelVariables` KDoc).
 * - **§4.9 주의 1** — `Timeout` 은 인스턴스 `In`/`Out` 으로 갈린다. 변수 두 개가 아니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
class DeviceModelClientTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var deviceModel: DeviceModelClient

    @Autowired
    private lateinit var validator: OcppPayloadValidator

    // ------------------------------------------------------------------ S04.FR.12

    @Test
    fun `GetVariables 로 슬롯에 꽂힌 배터리의 SoC 를 조회한다`() {
        withStation("CS-DM-SOC") { stationId ->
            val answered = assertIs<DeviceModelQuery.Answered>(
                deviceModel.get(
                    stationId,
                    ChargingScenario.DISPENSE_SLOTS.map { DeviceModelVariables.batterySoC(it) },
                ),
            )

            ChargingScenario.DISPENSE_SLOTS.forEach { slotId ->
                val expected = assertNotNull(ChargingScenario.DISPENSED[slotId])
                assertEquals(
                    expected.soC.toInt().toString(),
                    answered.valueOf(DeviceModelVariables.batterySoC(slotId)),
                    "슬롯 $slotId 의 SoC",
                )
            }
        }
    }

    @Test
    fun `배터리가 없는 슬롯은 UnknownComponent 다`() {
        withStation("CS-DM-EMPTY") { stationId ->
            val answered = assertIs<DeviceModelQuery.Answered>(
                // 슬롯 1 은 비어 있다 (교환으로 배터리가 들어올 자리).
                deviceModel.get(stationId, DeviceModelVariables.batterySoC(1)),
            )

            val reading = assertNotNull(answered.readingOf(DeviceModelVariables.batterySoC(1)))
            assertEquals(VariableStatus.UNKNOWN_COMPONENT, reading.status)
            // 없는 값을 지어내지 않는다 — 0% 로 답하면 "다 쓴 배터리가 꽂혀 있다"가 된다.
            assertNull(reading.value)
        }
    }

    // ------------------------------------------------------------------ §4.9 주의 1 — Timeout 인스턴스

    @Test
    fun `Timeout 은 인스턴스 In Out 으로 구분돼 조회된다`() {
        withStation("CS-DM-TIMEOUT") { stationId ->
            val answered = assertIs<DeviceModelQuery.Answered>(
                deviceModel.get(
                    stationId,
                    listOf(DeviceModelVariables.timeoutIn(), DeviceModelVariables.timeoutOut()),
                ),
            )

            // 같은 변수 이름인데 답이 둘이고 값이 다르다 — 인스턴스가 실제로 구분한다.
            assertEquals(2, answered.readings.size)
            assertTrue(answered.readings.all { it.ref.variable == DeviceModelVariables.VARIABLE_TIMEOUT })
            assertEquals("30", answered.valueOf(DeviceModelVariables.timeoutIn()))
            assertEquals("90", answered.valueOf(DeviceModelVariables.timeoutOut()))
        }
    }

    @Test
    fun `변수 두 개로 모델링한 이름은 스테이션이 알지 못한다`() {
        withStation("CS-DM-WRONG-NAME") { stationId ->
            // Part 2 본문의 축약 표기다. 정본(부록 CSV)에는 없는 변수다 (주의 1).
            val wrong = VariableRef(DeviceModelVariables.COMPONENT_BATTERY_SWAP_CTRLR, "BatterySwapInTimeout")
            val answered = assertIs<DeviceModelQuery.Answered>(deviceModel.get(stationId, wrong))

            assertEquals(VariableStatus.UNKNOWN_VARIABLE, assertNotNull(answered.readingOf(wrong)).status)
        }
    }

    // ------------------------------------------------------------------ S04.FR.05/06/10

    @Test
    fun `SetVariables 로 TargetSoC 와 MaxSoc 를 설정한다`() {
        withStation("CS-DM-SET") { stationId ->
            val updated = assertIs<DeviceModelUpdate.Answered>(
                deviceModel.set(
                    stationId,
                    listOf(
                        VariableAssignment(DeviceModelVariables.maxSoc(), "95"),
                        VariableAssignment(DeviceModelVariables.targetSoC(), "70"),
                    ),
                ),
            )
            assertTrue(updated.isAllAccepted, "제약을 지키는 설정이 거부됐다: ${updated.results}")

            // 조회하면 바뀐 값이 나온다 — 정말로 반영됐다는 뜻이다.
            val answered = assertIs<DeviceModelQuery.Answered>(
                deviceModel.get(
                    stationId,
                    listOf(DeviceModelVariables.targetSoC(), DeviceModelVariables.maxSoc()),
                ),
            )
            assertEquals("70", answered.valueOf(DeviceModelVariables.targetSoC()))
            assertEquals("95", answered.valueOf(DeviceModelVariables.maxSoc()))
        }
    }

    @Test
    fun `MaxSoc 를 TargetSoC 아래로 내리는 설정은 거부된다`() {
        withStation("CS-DM-REJECT") { stationId ->
            // 스테이션의 TargetSoC 는 40 이다. MaxSoc 를 30 으로 내리려 한다.
            val updated = assertIs<DeviceModelUpdate.Answered>(
                deviceModel.set(stationId, DeviceModelVariables.maxSoc(), "30"),
            )

            val result = assertNotNull(updated.resultOf(DeviceModelVariables.maxSoc()))
            assertEquals(VariableStatus.REJECTED, result.status, "S04.FR.06/10 위반이 통과했다")
            assertTrue(!updated.isAllAccepted)
            // 왜 거부됐는지가 함께 온다. 삼키지 않는다.
            assertNotNull(result.reasonCode)
            assertTrue(
                result.additionalInfo.orEmpty().contains("S04.FR.06/10"),
                "거부 사유가 스펙 조항을 가리키지 않는다: ${result.additionalInfo}",
            )

            // 거부됐으니 값도 그대로다.
            val answered = assertIs<DeviceModelQuery.Answered>(
                deviceModel.get(stationId, DeviceModelVariables.maxSoc()),
            )
            assertEquals(ChargingScenario.MAX_SOC.toString(), answered.valueOf(DeviceModelVariables.maxSoc()))
        }
    }

    @Test
    fun `연결이 없으면 예외가 아니라 Unreachable 이다`() {
        // 스테이션을 띄우지 않는다. 연결이 없는 것도 결과다 (M4 OcppResult 와 같은 태도).
        val query = runBlocking {
            deviceModel.get(StationId("CS-DM-ABSENT"), DeviceModelVariables.targetSoC())
        }
        assertIs<DeviceModelQuery.Unreachable>(query)

        val update = runBlocking {
            deviceModel.set(StationId("CS-DM-ABSENT"), DeviceModelVariables.targetSoC(), "50")
        }
        assertIs<DeviceModelUpdate.Unreachable>(update)
    }

    // ------------------------------------------------------------------ 프로토콜

    @Test
    fun `오간 모든 메시지가 공식 스키마를 통과한다`() {
        val records = withStation("CS-DM-SCHEMA") { stationId ->
            deviceModel.get(stationId, DeviceModelVariables.batterySoC(3))
            deviceModel.set(stationId, DeviceModelVariables.maxSoc(), "30") // 거부되는 설정도 포함한다
            deviceModel.set(stationId, DeviceModelVariables.targetSoC(), "45")
        }

        // 거부 응답도 공식 스키마를 지난다 — `attributeStatusInfo` 를 붙이고도 통과해야 한다.
        ConformanceScenario.assertAllSchemaValid(records, validator)
        ConformanceScenario.assertNoErrorFrames(records)
    }

    // ------------------------------------------------------------------ 공통

    /**
     * 부팅까지 마친 스테이션 하나를 띄우고 [block] 을 돌린다.
     *
     * @return 오간 프레임 원문 전부 (이벤트 로그).
     */
    private fun withStation(
        stationId: String,
        block: suspend (StationId) -> Unit,
    ): List<OcppEventRecord> {
        val simulator: StationSimulator = ChargingScenario.simulator(
            ChargingScenario.config(port, stationId),
        ).first

        simulator.use {
            runBlocking {
                it.connect()
                it.boot()
                block(StationId(stationId))
            }
        }
        return simulator.eventLog.of(stationId)
    }
}
