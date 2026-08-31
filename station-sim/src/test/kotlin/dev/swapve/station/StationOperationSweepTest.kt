package dev.swapve.station

import dev.swapve.ocpp.session.OcppResult
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.ocpp.swap.DeviceModelVariables
import dev.swapve.ocpp.swap.VariableReading
import dev.swapve.swap.SlotState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D3 의 조작들을 가짜 CSMS 상대로 훑는다 (A2).
 *
 * `StationRoundTripTest` 가 부팅·교환·인바운드 CALL 을 덮고, 여기서는 그 바깥의 조작들을
 * 실제 왕복 위에서 한 번씩 돌려 본다 — 재부팅 · 주기 보고 · 반출 타임아웃 · 중복 입고 ·
 * 역순 교환. **한 번도 돌아 본 적 없는 경로가 "구현했다"로 남지 않게 하는 것**이 목적이라
 * 단언은 그 조작이 실제로 선로에 무엇을 냈는지에 둔다.
 */
class StationOperationSweepTest {

    /**
     * ★ **S04.FR.11 — 재부팅**.
     *
     * 진행 중이던 트랜잭션은 종료 통보 없이 사라지고, 배터리가 든 슬롯마다 **새 트랜잭션**이
     * 열린다. 옛 식별자를 이어 쓰면 같은 트랜잭션이 두 번 시작한 것이 되어 장부가 거짓이 된다.
     */
    @Test
    fun `재부팅은 새 연결 위에서 트랜잭션을 새로 연다`() = runTest {
        val csms = FakeCsms("CS-REBOOT")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()
            val before = station.chargingTransactionAt(2)

            station.reboot()

            assertEquals(2, csms.opened.size, "재부팅은 끊고 다시 붙는다")
            assertNotEquals(before, station.chargingTransactionAt(2), "옛 식별자를 이어 쓰면 안 된다")
            assertEquals(SlotState.HOLDS_BATTERY, station.slotState(2), "전원이 나가도 배터리는 그대로 있다")

            val boots = csms.received(BatterySwapWire.BOOT_NOTIFICATION).map(::callPayloadOf)
            assertEquals(2, boots.size)

            // 리터럴이다 — 상수를 기댓값으로 쓰면 그 상수로 만든 값을 같은 상수와 견주게 된다.
            // `schemas/BootNotificationRequest.json` 의 `BootReasonEnumType`. 지우지 말 것.
            assertEquals("PowerUp", boots[0].path("reason").asText())
            // 바로 위와 같은 이유로 리터럴이다. `BootReasonEnumType` 은 아홉 값을 허용하므로
            // `PowerUp` 과 `LocalReset` 을 뒤바꿔도 스키마는 갈라내지 못한다. 지우지 말 것.
            assertEquals(
                "LocalReset",
                boots[1].path("reason").asText(),
                "전원 인가와 재시작은 다른 사건이고 표준이 그 둘을 구분할 값을 준다",
            )

            val securityEvents = csms.received(BatterySwapWire.SECURITY_EVENT_NOTIFICATION).map(::callPayloadOf)

            // ★ 아래 둘은 **스키마가 한 글자도 못 잡아 주는 자리**다. `SecurityEventNotificationRequest`
            // 의 `type` 은 enum 이 아니라 maxLength 50 자유 문자열이라, 오타든 엉뚱한 값이든 검증을
            // 그대로 통과한다. 그래서 여기서만이라도 리터럴로 못 박는다 — 상수를 기댓값으로 쓰면
            // 그 상수로 만든 값을 같은 상수와 견주는 꼴이라 아무것도 못 잡는다.
            // 출처는 Part 6 `BootedBatterySwapping` 전사다. 지우지 말 것.
            assertEquals("StartupOfTheDevice", securityEvents[0].path("type").asText())
            assertEquals("ResetOrReboot", securityEvents[1].path("type").asText())
        }
    }

    /**
     * ★ **S04.FR.04 — SoC 주기 보고**.
     *
     * 상태가 바뀌어서 보내는 것이 아니므로 `chargingState` 를 싣지 않고 `triggerReason` 도
     * `MeterValuePeriodic` 이다. 매번 `ChargingStateChanged` 를 실으면 "상태가 바뀌었다"는
     * 신호를 매번 거짓으로 내는 셈이 된다.
     */
    @Test
    fun `주기 보고가 계량값을 싣고 나간다`() = runTest {
        val csms = FakeCsms("CS-SOC")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()

            assertEquals(70.0, station.advanceCharging(2, byPercent = 10.0))

            val periodic = csms.received(BatterySwapWire.TRANSACTION_EVENT)
                .map(::callPayloadOf)
                .filter { it.path("triggerReason").asText() == BatterySwapWire.TRIGGER_REASON_METER_VALUE_PERIODIC }
                .single()

            assertTrue(periodic.path("transactionInfo").path("chargingState").isMissingNode, "상태는 바뀌지 않았다")
            val sample = periodic.path("meterValue").get(0).path("sampledValue").get(0)
            // 둘 다 리터럴이다 — 상수를 기댓값으로 쓰면 동어반복이라 값을 잘못 고쳐도 초록이다.
            // `measurand` 는 `schemas/TransactionEventRequest.json` 의 `MeasurandEnumType` 이 덮지만,
            // ★ `unitOfMeasure.unit` 은 **enum 이 아니라 자유 문자열**이다 — 스키마는 "Part 2
            // Appendices 의 표준 단위를 쓸 것"이라고만 하고 검사하지 않으므로 "percent" 같은 오타가
            // 그대로 통과한다. 그 자리를 잡는 것은 이 단언뿐이다. 지우지 말 것.
            assertEquals("SoC", sample.path("measurand").asText())
            assertEquals("Percent", sample.path("unitOfMeasure").path("unit").asText())
            assertEquals(70.0, sample.path("value").asDouble())
            assertEquals(70.0, station.batteryAt(2)?.soC, "보고한 값과 배터리의 값이 두 벌이면 하나는 거짓이다")
        }
    }

    /**
     * ★ **S03.FR.06 — 제공된 배터리를 이용자가 꺼내가지 않았다** (F2).
     *
     * 슬롯 상태도 충전 트랜잭션도 건드리지 않는다. 배터리는 아직 스테이션 안에 있고, CSMS
     * 에게는 *"BatteryOut 이 오지 않는 orphan BatteryIn"* 이 남으므로 그 사실만 알린다.
     */
    @Test
    fun `꺼내가지 않은 배터리를 타임아웃으로 알린다`() = runTest {
        val csms = FakeCsms("CS-OUT-TIMEOUT")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()
            station.authorize()
            station.insertBatteries()
            station.reportBatteryOutTimeout()

            val timedOut = csms.received(BatterySwapWire.BATTERY_SWAP).map(::callPayloadOf).last()

            // 리터럴이다 — 상수를 기댓값으로 쓰면 동어반복이 된다.
            // `schemas/BatterySwapRequest.json` 의 `BatterySwapEventEnumType`. 지우지 말 것.
            assertEquals("BatteryOutTimeout", timedOut.path("eventType").asText())
            assertEquals(station.config.requestId, timedOut.path("requestId").asInt(), "상관 번호는 그대로다")
            assertEquals(
                "BAT-OUT",
                timedOut.path("batteryData").get(0).path("serialNumber").asText(),
                "어느 배터리가 orphan 인지가 남아야 보상이 된다",
            )
            assertEquals(SlotState.HOLDS_BATTERY, station.slotState(2), "배터리는 아직 스테이션 안에 있다")
        }
    }

    /**
     * ★ **F4 — 중복 `BatteryIn`**.
     *
     * 새 messageId 로 다시 보내면 멱등 원장은 그냥 통과시킨다. 여기서 걸러야 하는 것은
     * 프로토콜 계층이 아니라 상태머신이고, 그 사실이 이 시험에 그대로 드러난다 —
     * 가짜 CSMS 의 상위 계층이 **두 번 불린다.**
     *
     * `sameMessageId = true` 쪽(F6)은 `StationRoundTripTest` 가 본다.
     */
    @Test
    fun `새 messageId 로 다시 보낸 입고는 원장을 지나간다`() = runTest {
        val csms = FakeCsms("CS-F4")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()
            station.authorize()
            station.insertBatteries()

            val handledBefore = csms.handledCalls
            station.resendLastBatterySwap(sameMessageId = false)

            assertEquals(handledBefore + 1, csms.handledCalls, "멱등 원장은 새 messageId 를 막지 않는다")

            val swaps = csms.received(BatterySwapWire.BATTERY_SWAP)
            assertEquals(2, swaps.size)
            assertNotEquals(swaps[0].messageId, swaps[1].messageId, "재전송이 아니라 새 메시지다")
            assertEquals(
                callPayloadOf(swaps[0]),
                callPayloadOf(swaps[1]),
                "실려 온 사실은 같다 — 거르는 일은 상태머신의 몫이다",
            )
        }
    }

    /**
     * ★ **역순 교환** (S03 Remark, S03.FR.07).
     *
     * 새 배터리를 먼저 내주고 헌 배터리를 나중에 받는다. 같은 상태 집합을 지나되 진입 순서만
     * 다르며, 그 사실을 `BatterySwapCtrlr.SwapOrder` 로 **보고해야 한다**.
     */
    @Test
    fun `Out-In 스테이션은 반출을 먼저 하고 그 순서를 보고한다`() = runTest {
        val csms = FakeCsms("CS-OUT-IN")
        val config = testConfig(csms.stationId, swapOrder = SwapOrder.OUT_IN)

        stationOn(csms, config).use { station ->
            station.connect()
            station.bootAndSwap()

            val eventTypes = csms.received(BatterySwapWire.BATTERY_SWAP)
                .map { callPayloadOf(it).path("eventType").asText() }
            // 리터럴이다 — 상수 둘을 서로 뒤바꾸면 발신과 기댓값이 함께 바뀌어 이 시험이
            // 초록으로 남는다. 둘 다 `BatterySwapEventEnumType` 의 정당한 멤버라 스키마도
            // 갈라내지 못한다. `schemas/BatterySwapRequest.json`. 지우지 말 것.
            assertEquals(
                listOf("BatteryOut", "BatteryIn"),
                eventTypes,
                "역순 스테이션은 반출이 먼저다",
            )

            assertEquals(SlotState.HOLDS_BATTERY, station.slotState(1))
            assertEquals(SlotState.EMPTY, station.slotState(2))
            assertNull(station.chargingTransactionAt(2))

            val result = csms.call(
                BatterySwapWire.GET_VARIABLES,
                CsmsPayloads.getVariables(listOf(DeviceModelVariables.swapOrder())),
            )
            val accepted = assertIs<OcppResult.Accepted>(result, "스테이션이 답하지 않았다: $result")
            val reading = accepted.payload.path("getVariableResult").mapNotNull { VariableReading.read(it) }.single()
            assertEquals(SwapOrder.OUT_IN.wireValue, reading.value, "S03.FR.07 — 역순은 반드시 보고된다")
        }
    }
}
