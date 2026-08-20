package dev.swapve.csms.conformance

import dev.swapve.csms.conformance.ConformanceScenario.callPayload
import dev.swapve.csms.conformance.ConformanceScenario.resultPayload
import dev.swapve.csms.conformance.ConformanceScenario.stationReceived
import dev.swapve.csms.conformance.ConformanceScenario.stationSent
import dev.swapve.csms.support.BasicAuthStations
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.support.TestStations
import dev.swapve.csms.swap.RemoteSwapStart
import dev.swapve.csms.swap.RemoteSwapStarter
import dev.swapve.csms.swap.SwapTransactionRegistry
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.swap.BatteryRejectionReason
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.station.StationSimulator
import dev.swapve.swap.StationId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ★★ **`TC_S_102_CSMS` — Remote Start, 배터리 부족** (Part 6 p.1366, UC S01/S02)
 *
 * **성공 기준 S2**. 시험 대상(System under test)이 **CSMS** 인 공식 적합성
 * 케이스다. 시험계(Test System) 역할은 `station-sim` 이 맡는다.
 *
 * ```
 * Manual Action: Let CSMS trigger a request for swapping batteries
 * 1. CSMS  → RequestBatterySwapRequest
 * 2. 시험계 → RequestBatterySwapResponse
 *             status = Rejected
 *             statusInfo.reasonCode = NoBatteryAvailable
 * ```
 *
 * **Tool validation — Step 1 `RequestBatterySwapRequest`:**
 * - `requestId` 가 있어야 한다
 * - `idToken.idToken` 이 설정된 valid_idtoken 이어야 한다
 *
 * ### 여기서 확인되는 설계 결정
 *
 * **재고 판정은 스테이션이 한다** (S02.FR.04). CSMS 는 재고를 모른 채 요청을
 * 보내고, 거부 사유를 받아 기록한다. v2 의 *"CSMS 가 재고를 알아야 한다"* 는 잘못된
 * 전제였고, 이 케이스가 그것을 실증한다.
 */
@Tag("conformance")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
@ContextConfiguration(initializers = [BasicAuthStations::class])
class TcS102CsmsTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var remoteSwapStarter: RemoteSwapStarter

    @Autowired
    private lateinit var swaps: SwapTransactionRegistry

    @Autowired
    private lateinit var validator: OcppPayloadValidator

    // ------------------------------------------------------------------ 전 시퀀스

    @Test
    fun `TC_S_102_CSMS — 배터리가 부족하면 Rejected 와 NoBatteryAvailable 을 받아 기록한다`() {
        val stationId = TestStations.TC_S_102
        withStation(stationId) { simulator ->
            // Manual Action — CSMS 가 교환을 개시한다.
            // step 1: CSMS → RequestBatterySwapRequest
            val outcome = runBlocking {
                remoteSwapStarter.start(StationId(stationId), ConformanceScenario.VALID_ID_TOKEN)
            }

            // step 2: 시험계 → RequestBatterySwapResponse(status=Rejected,
            //                    statusInfo.reasonCode=NoBatteryAvailable)
            val rejected = assertIs<RemoteSwapStart.Rejected>(outcome, "거부되지 않았다: $outcome")
            assertEquals(BatteryRejectionReason.NO_BATTERY_AVAILABLE.wireValue, rejected.rejection.reasonCode)
            assertEquals(BatteryRejectionReason.NO_BATTERY_AVAILABLE, rejected.rejection.reason)

            // CSMS 가 기록했다 (F1).
            val recorded = assertNotNull(
                swaps.rejections().lastOrNull { it.key.stationId.value == stationId },
                "거부가 기록되지 않았다",
            )
            assertEquals(BatteryRejectionReason.NO_BATTERY_AVAILABLE, recorded.reason)

            // **교환은 열리지 않았다.** 인가가 나지 않았으므로 열렸다가 닫힌 것이 아니라
            // 처음부터 없었다.
            assertEquals(
                null,
                swaps.find(StationId(stationId), rejected.rejection.key.requestId.value),
                "거부된 개시가 교환을 열었다",
            )

            assertSequence(simulator)
        }
    }

    // ------------------------------------------------------------------ Tool validation (step 1)

    @Test
    fun `Tool validation step 1 — 발신한 RequestBatterySwapRequest 에 requestId 와 valid_idtoken 이 있다`() {
        val stationId = TestStations.TC_S_102_VALIDATION
        withStation(stationId) { simulator ->
            val requestId = 10_299
            runBlocking {
                remoteSwapStarter.start(StationId(stationId), ConformanceScenario.VALID_ID_TOKEN, requestId)
            }

            // 검사 대상은 **실제로 소켓을 지난 바이트**다. 시뮬레이터가 받은 것 = CSMS 가 보낸 것.
            val request = assertNotNull(
                simulator.eventLog.of(stationId).stationReceived()
                    .lastOrNull { it.action == BatterySwapWire.REQUEST_BATTERY_SWAP }
                    ?.callPayload(),
                "RequestBatterySwapRequest 가 나가지 않았다",
            )

            // Tool validation: requestId 가 있어야 한다.
            assertTrue(request.path("requestId").isInt, "requestId 가 없다: $request")
            assertEquals(requestId, request.path("requestId").asInt())

            // Tool validation: idToken.idToken 이 설정된 valid_idtoken 이어야 한다.
            assertEquals(
                ConformanceScenario.VALID_ID_TOKEN.idToken,
                request.path("idToken").path("idToken").asText(),
            )
            assertEquals(
                ConformanceScenario.VALID_ID_TOKEN.type,
                request.path("idToken").path("type").asText(),
            )
        }
    }

    // ------------------------------------------------------------------ S02.FR.03

    /**
     * **S02.FR.03 — CSMS 는 인가되지 않은 idToken 으로 `RequestBatterySwap` 을 보내면 안 된다(SHALL NOT).**
     *
     * 보내고 나서 거부당한 것과 **아예 보내지 않은 것**은 다르다. 전자는 규칙 위반이고
     * 후자가 준수다. 그래서 결과 타입뿐 아니라 **전선 위에 프레임이 없었다**는 사실을 확인한다.
     */
    @Test
    fun `S02_FR_03 — 인가되지 않은 idToken 으로는 발신하지 않는다`() {
        val stationId = TestStations.TC_S_102_UNAUTHORIZED
        withStation(stationId) { simulator ->
            val outcome = runBlocking {
                remoteSwapStarter.start(
                    StationId(stationId),
                    dev.swapve.swap.IdToken("NOT-REGISTERED-9999", "ISO14443"),
                )
            }

            assertIs<RemoteSwapStart.NotAuthorized>(outcome, "인가되지 않은 토큰인데 보냈다: $outcome")

            // 프레임 자체가 나가지 않았다.
            assertTrue(
                simulator.eventLog.of(stationId)
                    .none { it.action == BatterySwapWire.REQUEST_BATTERY_SWAP },
                "인가되지 않은 토큰으로 RequestBatterySwap 이 나갔다",
            )
        }
    }

    // ------------------------------------------------------------------ 공통

    /** step 2 의 응답이 실제로 어떤 모양이었는지, 그리고 오류 프레임이 없었는지. */
    private fun assertSequence(simulator: StationSimulator) {
        val records = simulator.eventLog.of(simulator.config.stationId)

        val response = assertNotNull(
            records.stationSent()
                .lastOrNull { it.action == BatterySwapWire.REQUEST_BATTERY_SWAP }
                ?.resultPayload(),
            "RequestBatterySwapResponse 가 없다",
        )
        assertEquals(BatterySwapWire.GENERIC_REJECTED, response.path("status").asText())
        assertEquals(
            BatteryRejectionReason.NO_BATTERY_AVAILABLE.wireValue,
            response.path("statusInfo").path("reasonCode").asText(),
        )

        // 적합성 시퀀스 중 CALLERROR 가 오가지 않는다.
        ConformanceScenario.assertNoErrorFrames(records)
        // 오간 모든 메시지가 공식 스키마를 통과한다.
        ConformanceScenario.assertAllSchemaValid(records, validator)
    }

    /** 배터리가 하나도 없는 스테이션을 붙이고 부팅시킨다 — 이 케이스의 전제조건이다. */
    private fun withStation(stationId: String, block: (StationSimulator) -> Unit) {
        val simulator = ConformanceScenario.simulator(
            ConformanceScenario.emptyStationConfig(port, stationId),
        )
        simulator.use {
            runBlocking {
                it.connect()
                it.boot()
            }
            block(it)
        }
    }
}
