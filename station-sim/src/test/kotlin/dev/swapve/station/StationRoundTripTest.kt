package dev.swapve.station

import dev.swapve.ocpp.rpc.RpcErrorCode
import dev.swapve.ocpp.session.MessageDirection
import dev.swapve.ocpp.session.OcppResult
import dev.swapve.ocpp.swap.BatteryRejectionReason
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.ocpp.swap.DeviceModelVariables
import dev.swapve.ocpp.swap.VariableReading
import dev.swapve.ocpp.swap.VariableRef
import dev.swapve.ocpp.swap.VariableStatus
import dev.swapve.ocpp.swap.VariableWrite
import dev.swapve.swap.SlotState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 시뮬레이터가 **가짜 CSMS 와 실제로 왕복하는** 것들 (A2).
 *
 * ### 여기서 시험되는 것은 두 겹이다
 *
 * 눈에 보이는 것은 단언이지만, 그전에 [FakeCsms] 를 지나며 **양쪽 스키마 검증이 이미
 * 돌았다**. 스테이션이 내보낸 페이로드는 CSMS 세션이 `<Action>Request` 로 검증하고, CSMS 가
 * 돌려준 답은 스테이션 세션이 `<Action>Response` 로 검증한다. 그래서 이 시험들이 초록이라는
 * 사실 자체가 "시뮬레이터가 만드는 모든 프레임이 공식 스키마를 통과한다"의 증거다.
 *
 * 전송 이음새의 계약(붙었는가·다시 붙는가·함께 닫히는가)은 `StationTransportSeamTest` 의
 * 몫이라 여기서 다시 묻지 않는다.
 */
class StationRoundTripTest {

    // ------------------------------------------------------------------ 스테이션 → CSMS

    @Test
    fun `부팅이 가짜 CSMS 와 끝까지 왕복한다`() = runTest {
        val csms = FakeCsms("CS-BOOT")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()

            assertEquals(
                listOf<String?>(
                    BatterySwapWire.BOOT_NOTIFICATION,
                    BatterySwapWire.NOTIFY_EVENT,
                    BatterySwapWire.NOTIFY_EVENT,
                    BatterySwapWire.SECURITY_EVENT_NOTIFICATION,
                    BatterySwapWire.TRANSACTION_EVENT,
                ),
                csms.receivedActions(),
                "Part 6 BootedBatterySwapping 의 차례 그대로 와야 한다",
            )

            assertEquals(SlotState.EMPTY, station.slotState(1))
            assertEquals(SlotState.HOLDS_BATTERY, station.slotState(2))
            assertNull(station.chargingTransactionAt(1), "빈 슬롯에는 열 트랜잭션이 없다")
            assertNotNull(
                station.chargingTransactionAt(2),
                "S04.FR.11 — 배터리가 든 EVSE 마다 충전 트랜잭션을 연다",
            )
        }
    }

    @Test
    fun `교환 한 건이 완주한다`() = runTest {
        val csms = FakeCsms("CS-SWAP")

        stationOn(csms).use { station ->
            station.connect()
            station.bootAndSwap()

            assertEquals(SlotState.HOLDS_BATTERY, station.slotState(1), "헌 배터리가 투입 슬롯에 남는다")
            assertEquals("BAT-IN", station.batteryAt(1)?.serialNumber)
            assertEquals(SlotState.EMPTY, station.slotState(2), "새 배터리는 이용자가 가져갔다")
            assertNull(station.batteryAt(2))
            assertNotNull(station.chargingTransactionAt(1), "투입된 배터리는 충전 중이다")
            assertNull(station.chargingTransactionAt(2), "반출된 슬롯의 트랜잭션은 닫혔다")

            val swaps = csms.received(BatterySwapWire.BATTERY_SWAP).map(::callPayloadOf)
            assertEquals(2, swaps.size, "입고 하나 · 출고 하나")

            // 아래 둘은 **리터럴**이다. `BatterySwapWire` 를 기댓값으로 쓰면 그 상수로 만든 값을
            // 같은 상수와 견주는 꼴이라, 값을 잘못 고쳐도 시험이 초록으로 남는다.
            // 출처는 `schemas/BatterySwapRequest.json` 의 `BatterySwapEventEnumType` 이다. 지우지 말 것.
            assertEquals("BatteryIn", swaps[0].path("eventType").asText())
            assertEquals("BatteryOut", swaps[1].path("eventType").asText())
            assertEquals(
                swaps[0].path("requestId").asInt(),
                swaps[1].path("requestId").asInt(),
                "S02.FR.02 — 출고는 입고의 requestId 를 승계한다(SHALL)",
            )
        }
    }

    /**
     * ★ **협상 결과는 관측 대상이지 관문이 아니다.**
     *
     * 스테이션은 `ocpp2.1` 과 `ocpp2.0.1` 을 순서대로 제시하고, 무엇이 될지는 상대가 고른다.
     * 상대가 2.0.1 을 골랐다고 해서 우리가 2.1 짜리 조작을 스스로 막으면, 그 CSMS 가
     * `BatterySwap` 을 받고 어떻게 굴러가는지는 **아무도 볼 수 없게 된다** — 시험 도구가
     * 시험을 거절하는 꼴이다. 그래서 여기서는 협상값이 그대로 비치는 것과, 그 위에서
     * `BatterySwap(BatteryIn)` 이 실제로 나가는 것을 한 시험에서 함께 붙잡는다.
     */
    @Test
    fun `2·0·1 로 협상돼도 그 값이 그대로 비치고 조작은 막히지 않는다`() = runTest {
        val csms = FakeCsms("CS-2001").apply { negotiatedSubprotocol = "ocpp2.0.1" }

        stationOn(csms).use { station ->
            station.connect()

            assertEquals("ocpp2.0.1", station.subprotocol, "상대가 고른 것을 그대로 내보인다")

            station.boot()
            station.authorize()
            station.insertBatteries()

            val swaps = csms.received(BatterySwapWire.BATTERY_SWAP).map(::callPayloadOf)
            assertEquals(1, swaps.size, "2.0.1 위에서도 BatterySwap 은 나간다")

            // 리터럴이다 — 상수를 기댓값으로 쓰면 동어반복이 된다.
            // `schemas/BatterySwapRequest.json` 의 `BatterySwapEventEnumType`. 지우지 말 것.
            assertEquals("BatteryIn", swaps.single().path("eventType").asText())
        }
    }

    // ------------------------------------------------------------------ CSMS → 스테이션

    @Test
    fun `원격 개시를 받아들이면 그 상관 번호로 교환이 열린다`() = runTest {
        val csms = FakeCsms("CS-S02")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()

            val result = csms.call(
                BatterySwapWire.REQUEST_BATTERY_SWAP,
                CsmsPayloads.requestBatterySwap(REMOTE_REQUEST_ID, station.config.idToken),
            )

            val accepted = assertIs<OcppResult.Accepted>(result, "스테이션이 답하지 않았다: $result")

            // 리터럴이다 — 상수를 기댓값으로 쓰면 그 상수로 만든 값을 같은 상수와 견주게 된다.
            // `schemas/RequestBatterySwapResponse.json` 의 `GenericStatusEnumType`. 지우지 말 것.
            assertEquals("Accepted", accepted.payload.path("status").asText())
            assertEquals(
                REMOTE_REQUEST_ID,
                station.awaitRemoteStart(),
                "S02.FR.02 — 이어지는 BatterySwap 은 CSMS 가 준 번호를 써야 한다",
            )
        }
    }

    @Test
    fun `내줄 배터리가 없으면 스테이션이 원격 개시를 거절한다`() = runTest {
        val csms = FakeCsms("CS-S02-EMPTY")

        stationOn(csms).use { station ->
            station.connect()
            station.bootAndSwap()

            val result = csms.call(
                BatterySwapWire.REQUEST_BATTERY_SWAP,
                CsmsPayloads.requestBatterySwap(REMOTE_REQUEST_ID, station.config.idToken),
            )

            val answered = assertIs<OcppResult.Accepted>(result, "거부도 CALLRESULT 로 온다: $result")

            // 리터럴이다 — 상수를 기댓값으로 쓰면 동어반복이 된다.
            // `schemas/RequestBatterySwapResponse.json` 의 `GenericStatusEnumType`. 지우지 말 것.
            assertEquals("Rejected", answered.payload.path("status").asText())
            assertEquals(
                BatteryRejectionReason.NO_BATTERY_AVAILABLE.wireValue,
                answered.payload.path("statusInfo").path("reasonCode").asText(),
                "S02.FR.04 — 재고 판정은 스테이션이 한다",
            )
        }
    }

    @Test
    fun `GetVariables 는 항목마다 따로 판정한다`() = runTest {
        val csms = FakeCsms("CS-GET")
        val unknown = VariableRef(DeviceModelVariables.COMPONENT_BATTERY_SWAP_CTRLR, "NoSuchVariable")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()

            val result = csms.call(
                BatterySwapWire.GET_VARIABLES,
                CsmsPayloads.getVariables(
                    listOf(
                        DeviceModelVariables.maxSoc(),
                        DeviceModelVariables.timeoutOut(),
                        DeviceModelVariables.batterySoC(2),
                        unknown,
                    ),
                ),
            )

            val accepted = assertIs<OcppResult.Accepted>(result, "스테이션이 답하지 않았다: $result")
            val readings = accepted.payload.path("getVariableResult").mapNotNull { VariableReading.read(it) }

            assertEquals(4, readings.size, "getVariableResult 는 요청 항목과 1:1 이다")
            assertEquals("100", readings[0].value, "MaxSoc")
            assertEquals("120", readings[1].value, "Timeout(Out) 은 초 단위 정수다")
            assertEquals("60", readings[2].value, "BatteryCartridge[2].SoC 는 슬롯에서 파생한다")
            assertEquals(
                VariableStatus.UNKNOWN_VARIABLE,
                readings[3].status,
                "모르는 변수 하나가 나머지 셋을 실패로 만들면 안 된다",
            )
            assertTrue(readings.take(3).all { it.isAccepted })
        }
    }

    @Test
    fun `SetVariables 로 바뀐 MaxSoc 이 충전 상한을 실제로 옮긴다`() = runTest {
        val csms = FakeCsms("CS-SET")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()

            val result = csms.call(
                BatterySwapWire.SET_VARIABLES,
                CsmsPayloads.setVariables(listOf(DeviceModelVariables.maxSoc() to LOWERED_MAX_SOC)),
            )

            val accepted = assertIs<OcppResult.Accepted>(result, "스테이션이 답하지 않았다: $result")
            val write = accepted.payload.path("setVariableResult").mapNotNull { VariableWrite.read(it) }.single()
            assertEquals(VariableStatus.ACCEPTED, write.status, "TargetSoC 이상이므로 받아들여야 한다")

            assertEquals(
                listOf(70.0, 80.0, 85.0),
                station.chargeUntilMaxSoc(2, stepPercent = 10.0),
                "설정이 응답으로만 끝나면 안 된다 — 상한이 실제로 85 가 돼야 한다",
            )
            assertTrue(station.isChargingSuspended(2), "S04.FR.06 — 상한에 닿으면 SuspendedEVSE")
            assertNotNull(station.chargingTransactionAt(2), "급전이 멈춘 것이지 트랜잭션이 끝난 것이 아니다")
        }
    }

    @Test
    fun `TargetSoC 아래로 내리는 MaxSoc 은 스테이션이 거절한다`() = runTest {
        val csms = FakeCsms("CS-SET-REJECT")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()

            val result = csms.call(
                BatterySwapWire.SET_VARIABLES,
                CsmsPayloads.setVariables(listOf(DeviceModelVariables.maxSoc() to TOO_LOW_MAX_SOC)),
            )

            val accepted = assertIs<OcppResult.Accepted>(result, "거부도 CALLRESULT 로 온다: $result")
            val write = accepted.payload.path("setVariableResult").mapNotNull { VariableWrite.read(it) }.single()

            assertEquals(VariableStatus.REJECTED, write.status, "S04.FR.06/10 위반")
            assertEquals(SimDeviceModel.REASON_SOC_ORDER, write.reasonCode)
        }
    }

    @Test
    fun `GetBaseReport 를 받아들이면 전체 재고가 여러 건으로 쪼개져 온다`() = runTest {
        val csms = FakeCsms("CS-REPORT")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()

            val requested = csms.call(
                BatterySwapWire.GET_BASE_REPORT,
                CsmsPayloads.getBaseReport(REPORT_REQUEST_ID, BatterySwapWire.REPORT_BASE_FULL_INVENTORY),
            )

            val accepted = assertIs<OcppResult.Accepted>(requested, "스테이션이 답하지 않았다: $requested")

            // 리터럴이다 — 상수를 기댓값으로 쓰면 동어반복이 된다.
            // `schemas/GetBaseReportResponse.json` 의 `GenericDeviceModelStatusEnumType`. 지우지 말 것.
            assertEquals("Accepted", accepted.payload.path("status").asText())

            assertEquals(REPORT_REQUEST_ID, station.reportFullInventory(), "청한 쪽의 상관 번호를 그대로 되돌린다")

            val pages = csms.received(BatterySwapWire.NOTIFY_REPORT).map(::callPayloadOf)
            assertEquals(EXPECTED_REPORT_PAGES, pages.size, "설정 7 개 + 배터리가 든 슬롯 하나의 SoC·SoH 2 개")
            pages.forEachIndexed { index, page ->
                assertEquals(REPORT_REQUEST_ID, page.path("requestId").asInt())
                assertEquals(index, page.path("seqNo").asInt(), "seqNo 는 0 부터 1 씩 오른다")
            }
            assertTrue(pages.dropLast(1).all { it.path("tbc").asBoolean() }, "뒤에 더 올 것이 있으면 tbc 다")
            assertTrue(
                pages.last().path("tbc").isMissingNode,
                "마지막 조각은 tbc 를 아예 싣지 않는다 (스키마 기본값이 곧 '여기서 끝')",
            )
        }
    }

    @Test
    fun `만들 수 없는 보고는 지원하지 않는다고 답한다`() = runTest {
        val csms = FakeCsms("CS-REPORT-NO")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()

            val result = csms.call(
                BatterySwapWire.GET_BASE_REPORT,
                CsmsPayloads.getBaseReport(REPORT_REQUEST_ID, CONFIGURATION_INVENTORY),
            )

            val accepted = assertIs<OcppResult.Accepted>(result, "거부도 CALLRESULT 로 온다: $result")
            assertEquals(
                BatterySwapWire.DEVICE_MODEL_NOT_SUPPORTED,
                accepted.payload.path("status").asText(),
                "'못 만든다'는 '안 만든다'(Rejected)와 다른 답이다",
            )
            assertEquals(UNSUPPORTED_BASE, accepted.payload.path("statusInfo").path("reasonCode").asText())
        }
    }

    @Test
    fun `구현하지 않은 action 은 NotImplemented 로 되돌아온다`() = runTest {
        val csms = FakeCsms("CS-RESET")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()

            val result = csms.call(RESET, CsmsPayloads.reset(RESET_IMMEDIATE))

            val rejected = assertIs<OcppResult.Rejected>(result, "있는 척하면 안 된다: $result")
            assertEquals(RpcErrorCode.NotImplemented, rejected.knownErrorCode)
        }
    }

    // ------------------------------------------------------------------ F6 재접속 재전송

    @Test
    fun `재접속을 건너는 재전송에 원장이 같은 답을 되돌린다`() = runTest {
        val csms = FakeCsms("CS-F6")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()
            station.authorize()
            station.insertBatteries()

            val sent = station.eventLog.of(station.config.stationId)
                .last { it.direction == MessageDirection.OUTBOUND && it.action == BatterySwapWire.BATTERY_SWAP }
            val handledBefore = csms.handledCalls
            val firstReply = replyTo(station, sent.messageId)

            station.disconnect()
            station.reconnect()
            station.resendLastBatterySwap(sameMessageId = true)

            assertEquals(2, csms.opened.size, "재접속은 실제로 새 연결이어야 한다")
            assertEquals(
                handledBefore,
                csms.handledCalls,
                "재전송으로 상위 계층이 다시 불렸다 — 배터리 장부가 두 번 계상된다",
            )
            assertEquals(2, station.repliesTo(sent.messageId), "재전송에도 회신은 와야 한다")
            assertEquals(
                firstReply,
                replyTo(station, sent.messageId),
                "저장된 응답을 바이트 그대로 되돌려야 한다 (Part 4 §4.1.4)",
            )
        }
    }

    /** 이 messageId 로 스테이션에 마지막으로 되돌아온 프레임 원문. */
    private fun replyTo(station: StationSimulator, messageId: String): String =
        station.eventLog.of(station.config.stationId)
            .last { it.direction == MessageDirection.INBOUND && it.messageId == messageId }
            .payload

    private companion object {
        const val REMOTE_REQUEST_ID = 7777
        const val REPORT_REQUEST_ID = 5150

        /** `TargetSoC`(80) 이상이라 받아들여지는 새 상한. */
        const val LOWERED_MAX_SOC = "85"

        /** `TargetSoC`(80) 미만이라 거부되는 값. */
        const val TOO_LOW_MAX_SOC = "70"

        /** 설정 7 개(Available·TargetSoC·MaxSoc·IdToken·Timeout(In)·Timeout(Out)·SwapOrder) + 카트리지 2 개를 3 씩. */
        const val EXPECTED_REPORT_PAGES = 3

        /** `ReportBaseEnumType` — 이 스테이션이 만들 수 없는 목록이다. */
        const val CONFIGURATION_INVENTORY = "ConfigurationInventory"

        /** `StationSimulator` 가 그 거부에 다는 사유. 20자 제한이라 짧다. */
        const val UNSUPPORTED_BASE = "UnsupportedBase"

        /** 스키마가 있는 실제 action 이지만 시뮬레이터가 구현하지 않은 것. */
        const val RESET = "Reset"
        const val RESET_IMMEDIATE = "Immediate"
    }
}
