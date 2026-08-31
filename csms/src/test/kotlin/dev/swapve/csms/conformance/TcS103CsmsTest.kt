package dev.swapve.csms.conformance

import com.fasterxml.jackson.databind.JsonNode
import dev.swapve.csms.conformance.ConformanceScenario.callPayload
import dev.swapve.csms.conformance.ConformanceScenario.resultPayload
import dev.swapve.csms.conformance.ConformanceScenario.stationReceived
import dev.swapve.csms.conformance.ConformanceScenario.stationSent
import dev.swapve.csms.support.BasicAuthStations
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.support.TestStations
import dev.swapve.csms.swap.ChargingTransactionRegistry
import dev.swapve.csms.swap.RemoteSwapStart
import dev.swapve.csms.swap.RemoteSwapStarter
import dev.swapve.csms.swap.SwapTransactionRegistry
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.session.OcppEventRecord
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.station.StationSimulator
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapTransaction
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
 * ★★★ **`TC_S_103_CSMS` — Remote Start, 배터리 충분** (Part 6 p.1366–1369, UC S02·S03·S04)
 *
 * **성공 기준 S2 이자 이 프로젝트의 최종 합격 기준** (적합성).
 *
 * 전제조건: **`BatterySwapCtrlr.Idtoken` 은 설정하지 않는다.** 그래서 충전 트랜잭션이
 * `NoAuthorization` + 빈 문자열로 보고되고, 아래 함정 1 이 발동한다.
 *
 * ```
 *  1. CSMS  → RequestBatterySwapRequest
 *  2. 시험계 → RequestBatterySwapResponse(status=Accepted)     (배터리 2개 세트 투입)
 *  3. 시험계 → 슬롯 상태 변경 알림          4. CSMS 응답
 *  5. 시험계 → TransactionEvent(Started, CablePluggedIn, EVConnected, evse=A, tx=…-3)
 *  6. CSMS  → TransactionEventResponse
 *  7. 시험계 → 슬롯 상태 변경 알림          8. CSMS 응답
 *  9. 시험계 → TransactionEvent(Started, …, evse=B, tx=…-4)
 * 10. CSMS  → TransactionEventResponse
 * 11. 시험계 → BatterySwap(BatteryIn, requestId, batteryData 2건)
 * 12. CSMS  → BatterySwapResponse                              (배터리 2개 세트 반출)
 * 13. 시험계 → TransactionEvent(Ended, EnergyLimitReached, Idle, EVDisconnected, tx=…-1)
 * 14. CSMS  → TransactionEventResponse
 * 15. 시험계 → 슬롯 상태 변경 알림         16. CSMS 응답
 * 17. 시험계 → TransactionEvent(Ended, …, tx=…-2)
 * 18. CSMS  → TransactionEventResponse
 * 19. 시험계 → 슬롯 상태 변경 알림         20. CSMS 응답
 * 21. 시험계 → BatterySwap(BatteryOut, 같은 requestId, batteryData 2건)
 * 22. CSMS  → BatterySwapResponse
 * ```
 *
 * **Tool validation — CSMS 가 통과해야 하는 것:**
 * ```
 * Step 1           RequestBatterySwapRequest  — requestId 존재, idToken.idToken = valid_idtoken
 * Step 6/10/14/18  TransactionEventResponse   — idTokenInfo.status must be Accepted
 * Step 12/22       BatterySwapResponse        — 회신 사실 자체
 * ```
 *
 * ### 이 케이스가 잡아내는 함정 4가지
 *
 * 1. **무인가 요청인데 응답에는 `idTokenInfo` 가 있어야 한다.** step 5/9/13/17 은
 *    `idToken.type = NoAuthorization` 에 `idToken.idToken = ""` 다 — 인가 대상이 아니다.
 *    그런데 step 6/10/14/18 응답에는 `idTokenInfo.status = Accepted` 를 요구한다.
 *    스키마상 `idTokenInfo` 는 **선택 필드**라 **스키마 검증으로는 절대 잡히지 않는다.**
 * 2. **종료 사건은 세 값을 각각 요구한다** — `triggerReason=EnergyLimitReached` ·
 *    `stoppedReason=EVDisconnected` · `chargingState=Idle`. 셋 다 enum 으로는 유효해서
 * 역시 스키마가 잡지 못한다 (M7 정정).
 * 3. **시작 시점의 `chargingState` 는 `EVConnected` 이지 `Charging` 이 아니다**
 *    (`TxStartPoint = EVConnected`, S04.FR.08).
 * 4. **CSMS 는 자기가 시작을 본 적 없는 트랜잭션의 종료를 받는다.** 반출 배터리의
 *    tx `…-1`/`…-2` 는 이 시나리오 이전에 시작됐다. 거기서 폭발하면 안 된다.
 *
 * ### 스펙 오타 주의
 *
 * `TC_S_103_CSMS` 의 Tool validation Step 1 은 `Message: RequestBatterySwapResponse` 라
 * 적고 **요청 필드**(`requestId`, `idToken.idToken`)를 나열한다. 같은 자리의
 * `TC_S_102_CSMS` 는 `RequestBatterySwapRequest` 다. **Request 가 맞다.**
 */
@Tag("conformance")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
@ContextConfiguration(initializers = [BasicAuthStations::class])
class TcS103CsmsTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var remoteSwapStarter: RemoteSwapStarter

    @Autowired
    private lateinit var swaps: SwapTransactionRegistry

    @Autowired
    private lateinit var chargingTransactions: ChargingTransactionRegistry

    @Autowired
    private lateinit var validator: OcppPayloadValidator

    // ------------------------------------------------------------------ 전 시퀀스

    @Test
    fun `TC_S_103_CSMS — 22단계 전 시퀀스를 완주한다`() {
        val run = runSequence(TestStations.TC_S_103)

        // step 2 — 스테이션이 받아들였고, 교환이 열렸다.
        val accepted = assertIs<RemoteSwapStart.Accepted>(run.outcome, "개시가 받아들여지지 않았다: ${run.outcome}")

        // step 11 + 21 이 끝나면 교환은 균형 있게 완료된다 (COMPLETED 불변식).
        val transaction = assertNotNull(swaps.find(StationId(run.stationId), accepted.key.requestId.value))
        val completed = assertIs<SwapTransaction.Completed>(transaction, "교환이 완료되지 않았다: $transaction")
        assertEquals(2, completed.batteriesIn.size, "배터리 2개 세트가 들어와야 한다")
        assertEquals(2, completed.batteriesOut.size, "배터리 2개 세트가 나가야 한다")

        // 오간 메시지 전량이 공식 스키마를 통과하고, 오류 프레임이 하나도 없다.
        ConformanceScenario.assertAllSchemaValid(run.records, validator)
        ConformanceScenario.assertNoErrorFrames(run.records)
    }

    // ------------------------------------------------------------------ Tool validation: step 1

    @Test
    fun `step 1 — RequestBatterySwapRequest 에 requestId 와 valid_idtoken 이 있다`() {
        val run = runSequence(TestStations.TC_S_103_STEP1)

        val request = assertNotNull(
            run.records.stationReceived()
                .firstOrNull { it.action == BatterySwapWire.REQUEST_BATTERY_SWAP }
                ?.callPayload(),
            "step 1 의 RequestBatterySwapRequest 가 없다",
        )

        assertTrue(request.path("requestId").isInt, "requestId 가 없다")
        assertEquals(
            ConformanceScenario.VALID_ID_TOKEN.idToken,
            request.path("idToken").path("idToken").asText(),
        )

        // step 2 — status = Accepted
        val response = assertNotNull(
            run.records.stationSent()
                .firstOrNull { it.action == BatterySwapWire.REQUEST_BATTERY_SWAP }
                ?.resultPayload(),
        )
        // step 2 원문: `status = Accepted`. 기댓값은 리터럴이다 — [ConformanceScenario] 의 전사 규약.
        assertEquals("Accepted", response.path("status").asText())
    }

    // ------------------------------------------------------------------ ★★ Tool validation: step 6/10/14/18

    /**
     * ★ **함정 1 — 이 프로젝트에서 스키마가 잡아 주지 않는 유일한 종류의 실패다.**
     *
     * `TransactionEventResponse` 스키마에는 top-level `required` 가 없다. 즉 `idTokenInfo`
     * 를 통째로 빼도 스키마는 통과한다. 그런데 Tool validation 은 그 안의 `status` 가
     * `Accepted` 이길 요구한다. **적합성 시험만이 이것을 잡는다.**
     *
     * 그러니 이 시험의 기댓값이 우리 상수여서는 안 된다. `0d9faea` 는 함정 2 를 고치면서
     * *"함정 1 은 같은 방식으로 확인하지 않았다"* 고 적어 두었는데, 확인해 보니 같은 종류였다.
     * 아래 두 기댓값은 리터럴이다 — [ConformanceScenario] 의 전사 규약.
     */
    @Test
    fun `step 6·10·14·18 — 무인가 TransactionEvent 에도 응답의 idTokenInfo_status 가 Accepted 다`() {
        val run = runSequence(TestStations.TC_S_103_IDTOKENINFO)

        val requests = run.records.stationSent().filter { it.action == BatterySwapWire.TRANSACTION_EVENT }
        val responses = run.records.stationReceived().filter { it.action == BatterySwapWire.TRANSACTION_EVENT }

        // step 5·9·13·17 네 건 (Started 2 + Ended 2).
        assertEquals(4, requests.size, "TransactionEvent 는 네 건이어야 한다 (step 5·9·13·17)")
        assertEquals(4, responses.size, "TransactionEventResponse 도 네 건이어야 한다 (step 6·10·14·18)")

        // 전제 확인 — 요청은 전부 **인가 대상이 아니다.**
        requests.forEach { record ->
            val idToken = record.callPayload().path("idToken")
            // `idToken.type` 은 **스키마가 제약하지 않는 자리**다(자유 문자열). 상수를 기댓값으로
            // 쓰면 오타든 잘못 고른 값이든 스키마도 시험도 잡지 않는다. 리터럴이다 — 전사 규약 참조.
            assertEquals(
                "NoAuthorization",
                idToken.path("type").asText(),
                "전제조건: BatterySwapCtrlr.Idtoken 은 설정하지 않는다",
            )
            assertEquals("", idToken.path("idToken").asText(), "무인가 토큰은 빈 문자열이다")
        }

        // ★ 그런데도 응답에는 idTokenInfo.status = Accepted 가 있어야 한다.
        responses.forEachIndexed { index, record ->
            val step = listOf(6, 10, 14, 18)[index]
            val info = record.resultPayload().path("idTokenInfo")
            assertTrue(info.isObject, "step $step — idTokenInfo 가 없다: ${record.payload}")
            assertEquals(
                "Accepted",
                info.path("status").asText(),
                "step $step — idTokenInfo.status",
            )
        }
    }

    // ------------------------------------------------------------------ step 5/9

    @Test
    fun `step 5·9 — 시작 사건은 CablePluggedIn 과 EVConnected 이고 evse_connectorId 가 1 이다`() {
        val run = runSequence(TestStations.TC_S_103_STARTED)

        // 필터의 값도 리터럴이다. `TX_STARTED` 를 Ended 로 뒤집으면 발신과 필터가 같이 바뀌어
        // 이 시험이 초록으로 남는다 — 셋 다 `TransactionEventEnumType` 의 정당한 멤버라
        // `WireContractTest` 의 스키마 대조도 통과한다. 전사 규약 참조.
        val started = run.transactionEvents().filter {
            it.path("eventType").asText() == "Started"
        }
        assertEquals(2, started.size, "step 5 와 9")

        started.forEachIndexed { index, event ->
            val step = listOf(5, 9)[index]
            // step 5·9 원문: `triggerReason = CablePluggedIn`. 리터럴이다 — 전사 규약 참조.
            assertEquals("CablePluggedIn", event.path("triggerReason").asText(), "step $step")
            // ★ 함정 3 — EVConnected 이지 Charging 이 아니다 (TxStartPoint = EVConnected, S04.FR.08).
            // 함정을 적어 두고 기댓값으로 우리 상수를 쓰면 그 함정을 못 잡는다. 함정 2 가 그랬다(`0d9faea`).
            assertEquals(
                "EVConnected",
                event.path("transactionInfo").path("chargingState").asText(),
                "step $step — chargingState 는 EVConnected 다",
            )
            assertEquals(
                ConformanceScenario.CONNECTOR_ID,
                event.path("evse").path("connectorId").asInt(),
                "step $step — evse.connectorId",
            )
        }

        // step 5 는 evse=A / tx=…-3, step 9 는 evse=B / tx=…-4
        assertEquals(ConformanceScenario.EVSE_A, started[0].path("evse").path("id").asInt())
        assertEquals(ConformanceScenario.TX_INSERTED_A, started[0].transactionId())
        assertEquals(ConformanceScenario.EVSE_B, started[1].path("evse").path("id").asInt())
        assertEquals(ConformanceScenario.TX_INSERTED_B, started[1].transactionId())
    }

    // ------------------------------------------------------------------ step 13/17

    @Test
    fun `step 13·17 — 종료 사건은 EnergyLimitReached · Idle · EVDisconnected 세 값을 각각 싣는다`() {
        val run = runSequence(TestStations.TC_S_103_ENDED)

        val ended = run.transactionEvents().filter {
            it.path("eventType").asText() == "Ended"
        }
        assertEquals(2, ended.size, "step 13 과 17")

        ended.forEachIndexed { index, event ->
            val step = listOf(13, 17)[index]
            val info = event.path("transactionInfo")

            // ★ 함정 2 — 초기 설계는 triggerReason 자리를 EVCommunicationLost 로 추정했다.
            // 아래 셋은 **리터럴**이다. `BatterySwapWire` 를 기댓값으로 쓰면 상수로 만든 값을
            // 같은 상수와 견주는 꼴이라, 값을 잘못 고쳐도 시험이 초록으로 남는다. 실제로 그랬다 —
            // 상수를 EVCommunicationLost 로 되돌려도 게이트 6개가 전부 통과했다(2026-08-27 실측).
            // 출처는 Part 6 pp.1366-1369 의 TC_S_103_CSMS step 13·17 이다. 지우지 말 것.
            assertEquals(
                "EnergyLimitReached",
                event.path("triggerReason").asText(),
                "step $step — triggerReason (왜 끝났나)",
            )
            assertEquals(
                "EVDisconnected",
                info.path("stoppedReason").asText(),
                "step $step — stoppedReason (무엇이 끊겼나)",
            )
            assertEquals(
                "Idle",
                info.path("chargingState").asText(),
                "step $step — chargingState (끝난 뒤의 상태)",
            )
        }

        // step 13 은 tx=…-1, step 17 은 tx=…-2
        assertEquals(ConformanceScenario.TX_DISPENSED_C, ended[0].transactionId())
        assertEquals(ConformanceScenario.TX_DISPENSED_D, ended[1].transactionId())
    }

    /**
     * ★ **함정 4 — CSMS 는 자기가 시작을 본 적 없는 트랜잭션의 종료를 받는다**.
     *
     * 반출 배터리의 tx `…-1`/`…-2` 는 이 시나리오 **이전에** 시작됐다. 여기서는 종료만 온다.
     * 교환 생명주기와 충전 생명주기를 한 객체로 합쳤다면 이 지점에서 깨진다.
     */
    @Test
    fun `시작을 본 적 없는 트랜잭션의 종료를 받아도 깨지지 않는다`() {
        val run = runSequence(TestStations.TC_S_103_ORPHAN_TX)
        val station = StationId(run.stationId)

        // 이 두 트랜잭션의 Started 는 전선 위에 없었다.
        val startedIds = run.transactionEvents()
            .filter { it.path("eventType").asText() == "Started" }
            .map { it.transactionId() }
        assertTrue(
            ConformanceScenario.TX_DISPENSED_C !in startedIds && ConformanceScenario.TX_DISPENSED_D !in startedIds,
            "반출 배터리의 트랜잭션 시작이 전송됐다 — 이 케이스의 전제가 깨졌다",
        )

        // 그런데도 CSMS 는 종료를 받아 기록했다.
        listOf(ConformanceScenario.TX_DISPENSED_C, ConformanceScenario.TX_DISPENSED_D).forEach { transactionId ->
            val recorded = assertNotNull(
                chargingTransactions.find(station, transactionId),
                "시작을 못 본 트랜잭션 $transactionId 의 종료가 기록되지 않았다",
            )
            assertTrue(recorded.isEnded, "$transactionId 가 종료로 기록되지 않았다")
            assertEquals(1, recorded.events.size, "$transactionId 에는 종료 사건 하나만 있다")
        }

        // 교환은 그와 **무관하게** 완료됐다 — 두 생명주기가 독립이라는 뜻이다.
        val accepted = assertIs<RemoteSwapStart.Accepted>(run.outcome)
        assertIs<SwapTransaction.Completed>(swaps.find(station, accepted.key.requestId.value))
    }

    // ------------------------------------------------------------------ step 11/21 · 12/22

    @Test
    fun `step 11·21 — 배터리 2개 세트가 스펙 원문의 값으로 오간다`() {
        val run = runSequence(TestStations.TC_S_103_BATTERIES)

        val swapCalls = run.records.stationSent()
            .filter { it.action == BatterySwapWire.BATTERY_SWAP }
            .map { it.callPayload() }
        assertEquals(2, swapCalls.size, "step 11 과 21")

        val batteryIn = swapCalls[0]
        val batteryOut = swapCalls[1]
        // step 11·21 원문: `eventType = BatteryIn` · `BatteryOut`. 리터럴이다 — 전사 규약 참조.
        // 둘이 뒤바뀌어도 스키마는 통과한다 — `BatterySwapEventTypeEnumType` 의 정당한 멤버다.
        assertEquals("BatteryIn", batteryIn.path("eventType").asText(), "step 11")
        assertEquals("BatteryOut", batteryOut.path("eventType").asText(), "step 21")

        // S02.FR.02 — 이어지는 BatterySwapRequest 는 **같은 requestId 를 써야 한다(SHALL)**.
        val requestId = assertIs<RemoteSwapStart.Accepted>(run.outcome).key.requestId.value
        assertEquals(requestId, batteryIn.path("requestId").asInt(), "step 11 requestId")
        assertEquals(requestId, batteryOut.path("requestId").asInt(), "step 21 requestId — 승계돼야 한다")

        // idToken.idToken = valid_idtoken (step 11/21 원문)
        listOf(batteryIn to 11, batteryOut to 21).forEach { (call, step) ->
            assertEquals(
                ConformanceScenario.VALID_ID_TOKEN.idToken,
                call.path("idToken").path("idToken").asText(),
                "step $step — idToken",
            )
        }

        // step 11: {evseId=A, sn=1234, soC=23, soH=85}, {evseId2=B, sn=5678, soC=45, soH=87}
        assertBattery(batteryIn, 0, ConformanceScenario.EVSE_A, "1234", 23.0, 85.0)
        assertBattery(batteryIn, 1, ConformanceScenario.EVSE_B, "5678", 45.0, 87.0)

        // step 21: {evseId3=C, sn=4321, soC=80, soH=95}, {evseId4=D, sn=8765, soC=85, soH=78}
        assertBattery(batteryOut, 0, ConformanceScenario.EVSE_C, "4321", 80.0, 95.0)
        assertBattery(batteryOut, 1, ConformanceScenario.EVSE_D, "8765", 85.0, 78.0)
    }

    @Test
    fun `step 12·22 — BatterySwapResponse 로 회신한다`() {
        val run = runSequence(TestStations.TC_S_103_ACK)

        val responses = run.records.stationReceived().filter { it.action == BatterySwapWire.BATTERY_SWAP }
        assertEquals(2, responses.size, "step 12 와 22 — 두 번 회신해야 한다")

        // Tool validation 은 **회신 사실 자체**만 요구한다. 빈 응답이 정상이다
        // ("Empty response by CSMS to confirm receipt").
        responses.forEachIndexed { index, record ->
            val step = listOf(12, 22)[index]
            assertTrue(record.resultPayload().isObject, "step $step — 응답이 객체가 아니다")
            // 이 시나리오의 배터리는 전부 등록된 것이라 customData 거부가 붙지 않는다 (F3 는 별도 시험).
            assertTrue(
                record.resultPayload().path("customData").isMissingNode,
                "step $step — 정상 교환에 거부 customData 가 붙었다",
            )
        }
    }

    // ------------------------------------------------------------------ 시퀀스 순서

    /**
     * **오간 메시지의 순서가 스펙 시퀀스와 같다.**
     *
     * 개별 필드가 다 맞아도 순서가 다르면 다른 대화다. 특히 step 13→15 (트랜잭션 종료가
     * 슬롯 알림보다 **먼저**) 는 투입 쪽 step 3→5 와 뒤집혀 있어 틀리기 쉽다.
     */
    @Test
    fun `오간 메시지 순서가 스펙 시퀀스와 같다`() {
        val run = runSequence(TestStations.TC_S_103_ORDER)

        // 부팅 시퀀스를 지나 step 1 부터 본다.
        val actions = run.records.stationSent().map { it.action }
        val fromStep2 = actions.drop(
            actions.indexOfFirst { it == BatterySwapWire.REQUEST_BATTERY_SWAP },
        )

        assertEquals(
            listOf(
                // step 2 — RequestBatterySwapResponse (시뮬레이터가 낸 응답이 이 로그의 OUTBOUND 다)
                BatterySwapWire.REQUEST_BATTERY_SWAP,
                // step 3·5 — 슬롯 알림 → 트랜잭션 시작 (슬롯 A)
                BatterySwapWire.NOTIFY_EVENT,
                BatterySwapWire.TRANSACTION_EVENT,
                // step 7·9 — 슬롯 알림 → 트랜잭션 시작 (슬롯 B)
                BatterySwapWire.NOTIFY_EVENT,
                BatterySwapWire.TRANSACTION_EVENT,
                // step 11 — BatteryIn
                BatterySwapWire.BATTERY_SWAP,
                // step 13·15 — 트랜잭션 종료 → 슬롯 알림 (슬롯 C). **투입과 순서가 뒤집혀 있다.**
                BatterySwapWire.TRANSACTION_EVENT,
                BatterySwapWire.NOTIFY_EVENT,
                // step 17·19 — 트랜잭션 종료 → 슬롯 알림 (슬롯 D)
                BatterySwapWire.TRANSACTION_EVENT,
                BatterySwapWire.NOTIFY_EVENT,
                // step 21 — BatteryOut
                BatterySwapWire.BATTERY_SWAP,
            ),
            fromStep2,
            "시퀀스 순서가 스펙과 다르다",
        )
    }

    // ------------------------------------------------------------------ 공통

    private class SequenceRun(
        val stationId: String,
        val outcome: RemoteSwapStart,
        val records: List<OcppEventRecord>,
    ) {
        /** 시뮬레이터가 보낸 `TransactionEventRequest` 페이로드들, 보낸 순서대로. */
        fun transactionEvents(): List<JsonNode> =
            records.stationSent()
                .filter { it.action == BatterySwapWire.TRANSACTION_EVENT }
                .map { it.callPayload() }
    }

    /**
     * `TC_S_103_CSMS` 를 처음부터 끝까지 한 번 돌린다.
     *
     * ### step 1·2 가 끝난 **뒤에** step 3 을 시작한다
     *
     * [RemoteSwapStarter.start] 는 `RequestBatterySwapResponse` 를 받은 뒤에야 돌아온다.
     * 즉 그것이 돌아왔다는 것은 **스테이션이 이미 step 2 를 보냈다**는 뜻이다. 그 뒤에
     * 시퀀스를 이으면 step 2 → step 3 순서가 구조적으로 보장된다.
     *
     * 시퀀스를 미리 코루틴으로 띄워 두면 안 된다. 스테이션이 응답을 **전송하기 직전에**
     * 상관 번호를 알게 되므로, 깨어난 시퀀스의 첫 CALL 이 응답보다 먼저 소켓에 나갈 수
     * 있다 — 순서 시험이 간헐적으로 깨지는 종류의 경합이다.
     */
    private fun runSequence(stationId: String): SequenceRun {
        val simulator = ConformanceScenario.simulator(ConformanceScenario.config(port, stationId))
        return simulator.use {
            runBlocking {
                it.connect()
                it.boot()

                // step 1·2 — CSMS 가 개시하고 스테이션이 Accepted 로 답한다.
                val outcome = remoteSwapStarter.start(StationId(stationId), ConformanceScenario.VALID_ID_TOKEN)

                // step 3~22 — awaitRemoteStart 는 이미 완료돼 있어 곧바로 이어진다.
                it.runRemoteSwap()

                SequenceRun(stationId, outcome, it.eventLog.of(stationId))
            }
        }
    }

    private fun assertBattery(
        call: JsonNode,
        index: Int,
        evseId: Int,
        serialNumber: String,
        soC: Double,
        soH: Double,
    ) {
        val entry = call.path("batteryData").get(index)
        assertNotNull(entry, "batteryData[$index] 가 없다")
        assertEquals(evseId, entry.path("evseId").asInt(), "batteryData[$index].evseId")
        assertEquals(serialNumber, entry.path("serialNumber").asText(), "batteryData[$index].serialNumber")
        assertEquals(soC, entry.path("soC").asDouble(), "batteryData[$index].soC")
        assertEquals(soH, entry.path("soH").asDouble(), "batteryData[$index].soH")
    }

    private fun JsonNode.transactionId(): String = path("transactionInfo").path("transactionId").asText()
}
