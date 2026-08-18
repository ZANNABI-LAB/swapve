package dev.swapve.csms.conformance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.ocpp.rpc.MessageType
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.schema.PayloadValidation
import dev.swapve.ocpp.session.MessageDirection
import dev.swapve.ocpp.session.OcppEventRecord
import dev.swapve.station.FaultInjection
import dev.swapve.station.SimBattery
import dev.swapve.station.SlotConfig
import dev.swapve.station.StationSimConfig
import dev.swapve.station.StationSimulator
import dev.swapve.station.SwapOrder
import dev.swapve.swap.IdToken
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.assertTrue

/**
 * ★ **공식 적합성 케이스의 값들 — 스펙 원문 그대로** (Part 6 p.1366–1369, PLAN §7.1).
 *
 * ### 왜 값을 한 곳에 모으나
 *
 * `TC_S_103_CSMS` 는 배터리 일련번호·SoC·SoH·트랜잭션 식별자를 **구체적인 값으로** 지정한다.
 * 그 값들을 시험 코드에 흩어 적으면 나중에 스펙과 한 줄씩 대조할 수 없다. 여기 모아 두면
 * 이 파일 하나가 **스펙 원문의 기계 판독 가능한 사본**이 된다.
 *
 * ### 시험계(Test System) 역할은 station-sim 이 맡는다
 *
 * 시험 대상(System under test)은 **CSMS** 다. 시뮬레이터는 스펙 시퀀스를 그대로 연기하고,
 * 우리가 만든 CSMS 가 그 앞에서 시험받는다 (PLAN §7.1).
 */
object ConformanceScenario {

    /**
     * 시험 설정의 `valid_idtoken`.
     *
     * `application.yml` 의 인가 목록에 있는 토큰이다. **S02.FR.03** 때문에 이 값이어야 한다 —
     * 인가되지 않은 토큰이면 CSMS 가 `RequestBatterySwap` 을 보내지 **않는다**.
     */
    val VALID_ID_TOKEN = IdToken("RFID-0001", "ISO14443")

    // ------------------------------------------------------------------ 슬롯 (step 5/9/11/21)

    /** 헌 배터리가 들어갈 빈 슬롯 — 스펙의 `evseId` · `evseId2`. */
    const val EVSE_A = 1
    const val EVSE_B = 2

    /** 새 배터리가 나올 슬롯 — 스펙의 `evseId3` · `evseId4`. **입고 슬롯과 다르다.** */
    const val EVSE_C = 3
    const val EVSE_D = 4

    val INSERT_SLOTS = listOf(EVSE_A, EVSE_B)
    val DISPENSE_SLOTS = listOf(EVSE_C, EVSE_D)

    /** Tool validation (step 5/9): `evse.connectorId = 1`. */
    const val CONNECTOR_ID = 1

    // ------------------------------------------------------------------ 충전 트랜잭션 (step 5/9/13/17)

    /**
     * 스펙이 지정한 트랜잭션 식별자들.
     *
     * ### ★ `-1`/`-2` 와 `-3`/`-4` 의 차이가 이 케이스의 함정이다 (PLAN §7.1 읽을 것 5)
     *
     * - `…-3` / `…-4` — **입고** 배터리의 충전. step 5/9 에서 **시작**된다.
     * - `…-1` / `…-2` — **반출** 배터리의 충전. 이 시나리오 **이전에** 시작됐고 step 13/17 에서
     *   **종료만** 온다.
     *
     * 즉 CSMS 는 **자기가 시작을 본 적 없는 트랜잭션의 종료**를 받는다. 거기서 폭발하면 안 된다.
     */
    const val TX_DISPENSED_C = "111-222-333-444-1"
    const val TX_DISPENSED_D = "111-222-333-444-2"
    const val TX_INSERTED_A = "111-222-333-444-3"
    const val TX_INSERTED_B = "111-222-333-444-4"

    // ------------------------------------------------------------------ 배터리 (step 11/21)

    /** step 11 `batteryData[0]` — `{evseId, serialNumber:1234, soC:23, soH:85}`. */
    val INCOMING_A = SimBattery(serialNumber = "1234", soC = 23.0, soH = 85.0)

    /** step 11 `batteryData[1]` — `{evseId2, serialNumber:5678, soC:45, soH:87}`. */
    val INCOMING_B = SimBattery(serialNumber = "5678", soC = 45.0, soH = 87.0)

    /** step 21 `batteryData[0]` — `{evseId3, serialNumber:4321, soC:80, soH:95}`. */
    val DISPENSED_C = SimBattery(serialNumber = "4321", soC = 80.0, soH = 95.0)

    /** step 21 `batteryData[1]` — `{evseId4, serialNumber:8765, soC:85, soH:78}`. */
    val DISPENSED_D = SimBattery(serialNumber = "8765", soC = 85.0, soH = 78.0)

    val INCOMING = listOf(INCOMING_A, INCOMING_B)

    /** 시험 시나리오에서 오가는 배터리 전부. F3 의 "등록된 배터리 목록"이 이것이다. */
    val ALL_SERIALS = listOf(INCOMING_A, INCOMING_B, DISPENSED_C, DISPENSED_D).map { it.serialNumber }

    // ------------------------------------------------------------------ 구성

    /** 시뮬레이터도 고정 시계를 쓴다 — 양쪽 다 결정적이어야 한다. `sleep` 이 없다. */
    fun clock(): Clock = Clock.fixed(FixedClockConfig.FIXED_NOW, ZoneOffset.UTC)

    /**
     * `TC_S_103_CSMS` 의 스테이션 구성.
     *
     * ### 전제조건: `BatterySwapCtrlr.Idtoken` 을 설정하지 않는다
     *
     * 스펙이 그렇게 못박았다. 그래서 [StationSimConfig.chargingIdToken] 이 `null` 이고,
     * 충전 트랜잭션은 `idToken.type = NoAuthorization` 에 `idToken.idToken = ""` 로 보고된다.
     * **이것이 함정 1 을 발동시키는 조건이다** — 인가 대상이 아닌 요청인데도 응답에는
     * `idTokenInfo.status = Accepted` 가 있어야 한다.
     */
    fun config(
        port: Int,
        stationId: String,
        requestId: Int = 0,
        order: SwapOrder = SwapOrder.IN_OUT,
    ) = StationSimConfig(
        csmsUrl = "ws://localhost:$port/ocpp",
        stationId = stationId,
        slots = listOf(
            SlotConfig(EVSE_A, battery = null, connectorId = CONNECTOR_ID, chargingTransactionId = TX_INSERTED_A),
            SlotConfig(EVSE_B, battery = null, connectorId = CONNECTOR_ID, chargingTransactionId = TX_INSERTED_B),
            // ★ 이 두 슬롯의 충전은 이 시나리오 **이전에** 시작됐다. 부팅 때 Started 를
            //   보내지 않으므로 CSMS 는 종료만 보게 된다 (PLAN §7.1 읽을 것 5).
            SlotConfig(
                EVSE_C,
                battery = DISPENSED_C,
                connectorId = CONNECTOR_ID,
                chargingTransactionId = TX_DISPENSED_C,
                chargingAlreadyStarted = true,
            ),
            SlotConfig(
                EVSE_D,
                battery = DISPENSED_D,
                connectorId = CONNECTOR_ID,
                chargingTransactionId = TX_DISPENSED_D,
                chargingAlreadyStarted = true,
            ),
        ),
        idToken = VALID_ID_TOKEN,
        requestId = requestId,
        insertSlots = INSERT_SLOTS,
        dispenseSlots = DISPENSE_SLOTS,
        incomingBatteries = INCOMING,
        swapOrder = order,
        // 전제조건 — BatterySwapCtrlr.Idtoken 을 설정하지 않는다.
        chargingIdToken = null,
    )

    /**
     * `TC_S_102_CSMS` 의 스테이션 구성 — **내줄 배터리가 하나도 없다.**
     *
     * 재고 판정은 스테이션이 하므로 (PLAN §4.5, S02.FR.04), 이 구성의 시뮬레이터는
     * `RequestBatterySwapRequest` 에 `Rejected` + `NoBatteryAvailable` 로 답한다.
     */
    fun emptyStationConfig(port: Int, stationId: String) = StationSimConfig(
        csmsUrl = "ws://localhost:$port/ocpp",
        stationId = stationId,
        slots = listOf(
            SlotConfig(EVSE_A, connectorId = CONNECTOR_ID),
            SlotConfig(EVSE_B, connectorId = CONNECTOR_ID),
        ),
        idToken = VALID_ID_TOKEN,
        requestId = 0,
        insertSlots = emptyList(),
        dispenseSlots = emptyList(),
        incomingBatteries = emptyList(),
        chargingIdToken = null,
    )

    /**
     * F3 — 이용자가 **우리 것이 아닌 배터리**를 가져왔다 (PLAN §5.4).
     *
     * `TC_S_103_CSMS` 와 같은 시퀀스를 돌되 투입 배터리의 일련번호만 등록 목록 밖의 값이다.
     * 적합성 케이스 쪽 구성을 건드리지 않는 것이 요점이다 — 그쪽은 **거부가 붙지 않는다**를
     * 확인해야 하므로 배터리가 전부 등록돼 있어야 한다.
     */
    val UNKNOWN_INCOMING = listOf(
        SimBattery(serialNumber = "STOLEN-0001", soC = 23.0, soH = 85.0),
        SimBattery(serialNumber = "STOLEN-0002", soC = 45.0, soH = 87.0),
    )

    fun unknownBatteryConfig(port: Int, stationId: String) =
        config(port, stationId).copy(incomingBatteries = UNKNOWN_INCOMING)

    fun simulator(config: StationSimConfig, faults: FaultInjection = FaultInjection.None) =
        StationSimulator(config, clock = clock(), faults = faults)

    // ------------------------------------------------------------------ 공통 단언

    private val MAPPER = ObjectMapper()

    /** 프레임 원문에서 CALL 페이로드를 꺼낸다 — `[2,"<id>","<action>",{payload}]`. */
    fun OcppEventRecord.callPayload(): JsonNode = MAPPER.readTree(payload).get(3)

    /** 프레임 원문에서 CALLRESULT 페이로드를 꺼낸다 — `[3,"<id>",{payload}]`. */
    fun OcppEventRecord.resultPayload(): JsonNode = MAPPER.readTree(payload).get(2)

    fun OcppEventRecord.messageType(): MessageType? =
        MessageType.ofNumber(MAPPER.readTree(payload).get(0).asInt())

    /**
     * **적합성 시퀀스 중 오류 프레임이 하나도 오가지 않았다.**
     *
     * 적합성은 "결국 상태가 맞았다"가 아니라 "규약대로 대화했다"를 묻는다. 중간에 CALLERROR
     * 가 오갔다면 어느 한쪽이 상대의 메시지를 처리하지 못한 것이고, 그건 통과가 아니다.
     */
    fun assertNoErrorFrames(records: List<OcppEventRecord>) {
        val errors = records.filter {
            it.messageType() == MessageType.CALL_ERROR || it.messageType() == MessageType.CALL_RESULT_ERROR
        }
        assertTrue(errors.isEmpty(), "오류 프레임이 오갔다:\n" + errors.joinToString("\n") { it.payload })
    }

    /**
     * **오간 모든 메시지가 공식 스키마를 통과한다** (M6 `SchemaCrossCheckTest` 와 같은 방식).
     *
     * 검사 대상이 우리가 만든 객체가 아니라 **실제로 소켓을 지난 바이트**라는 것이 요점이다.
     * F3 의 `customData` 거부 응답도 이 검사를 지난다 — 표준이 정한 우회가 정말로 표준
     * 스키마를 통과하는지는 주장이 아니라 실행되는 검사로 확인해야 한다 (PLAN §4.8).
     */
    fun assertAllSchemaValid(records: List<OcppEventRecord>, validator: OcppPayloadValidator) {
        var checked = 0
        records.forEach { record ->
            val frame = MAPPER.readTree(record.payload)
            val validation = when (record.messageType()) {
                MessageType.CALL -> validator.validateCall(frame.get(2).asText(), frame.get(3))
                MessageType.SEND -> validator.validateSend(frame.get(2).asText(), frame.get(3))
                MessageType.CALL_RESULT -> {
                    // CALLRESULT 에는 action 이 없다. 짝이 되는 CALL 의 action 을 세션이 기록해 두었다.
                    val action = requireNotNull(record.action) { "CALLRESULT 의 짝을 찾지 못했다: ${record.payload}" }
                    validator.validateCallResult(action, frame.get(2))
                }
                // 페이로드 스키마가 없는 프레임이다 (Part 4 §4.2.3).
                else -> null
            } ?: return@forEach

            checked++
            assertTrue(
                validation !is PayloadValidation.Invalid,
                "${record.direction} ${record.action} 이 공식 스키마를 통과하지 못했다: " +
                    "${(validation as? PayloadValidation.Invalid)?.errorDescription}\n원문: ${record.payload}",
            )
        }
        assertTrue(checked > 0, "검증한 메시지가 없다")
    }

    /** 시뮬레이터가 **보낸** 것만 (= CSMS 가 받은 것). */
    fun List<OcppEventRecord>.stationSent(): List<OcppEventRecord> =
        filter { it.direction == MessageDirection.OUTBOUND }

    /** 시뮬레이터가 **받은** 것만 (= CSMS 가 보낸 것). */
    fun List<OcppEventRecord>.stationReceived(): List<OcppEventRecord> =
        filter { it.direction == MessageDirection.INBOUND }
}
