package dev.swapve.station

import dev.swapve.ocpp.session.MessageDirection
import dev.swapve.ocpp.session.OcppEventRecord
import dev.swapve.ocpp.session.OcppSession
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.swap.SlotState
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **실제 CSMS 로는 만들 수 없는 것들** (A2).
 *
 * 선로가 끊어졌다거나 상대가 끝내 답하지 않는 상황은 진짜 CSMS 를 세워 놓고 재현할 수단이
 * 없다. [FakeCsms] 의 모드 셋이 그 자리를 연다.
 *
 * ### 여기서 가리는 것은 "실패했다"가 아니라 **어느 길로 실패했나**다
 *
 * `Gone`(보낼 수 없었다)과 `TimedOut`(보냈는데 답이 없다)은 시뮬레이터 밖에서 보면 둘 다
 * 예외 하나지만, 원인도 남는 상태도 다르다. 그래서 단언은 예외의 종류가 아니라 **메시지와
 * 지나간 시간**을 본다 — 그 둘이 두 경로를 실제로 가른다.
 *
 * 같은 조작이 **어느 단계에서** 끊겼는가도 같은 축이다. `insertBatteries()` 하나가 예외로
 * 끝나는 두 가지 길이 있고, 한쪽만 재전송으로 복구된다 — 그 판정이 아래 두 시험이다.
 */
class StationFailurePathTest {

    @Test
    fun `전송이 Gone 을 돌려주면 조작이 실패로 끝난다`() = runTest {
        val csms = FakeCsms("CS-GONE")
        csms.mode = FakeCsms.Mode.BROKEN

        stationOn(csms).use { station ->
            station.connect()

            val failure = assertFailsWith<IllegalStateException> { station.boot() }

            assertEquals(
                "${BatterySwapWire.BOOT_NOTIFICATION} 을 보낼 연결이 없다: station=${station.config.stationId}",
                failure.message,
                "보낼 수 없었다는 사실이 그대로 올라와야 한다",
            )
            assertEquals(0L, currentTime, "보내지 못한 CALL 은 응답을 기다리지 않는다")
            assertTrue(csms.received(BatterySwapWire.BOOT_NOTIFICATION).isEmpty(), "CSMS 는 아무것도 못 받았다")
            assertTrue(
                station.isConnected,
                "⚠️ 전송은 열려 있다고 말하는데 세션은 Gone 을 받았다 — isConnected 는 소켓의 상태일 뿐이다",
            )
        }
    }

    /**
     * ★ **실패한 투입이 슬롯에 배터리를 남긴다.**
     *
     * `insertBatteries` 는 슬롯에 배터리를 꽂아 놓고 나서 첫 CALL 을 한다. 그 CALL 이 실패하면
     * 스테이션 안의 사실(배터리가 꽂혔다)은 남고 CSMS 는 그것을 모르는 상태가 된다.
     *
     * **여기서는 관측된 그대로 못박는다.** 물리적으로는 옳은 순서다 — 배터리는 실제로 들어갔고,
     * 그것을 보고하지 못했을 뿐이다. 다만 그 뒤에 **`insertBatteries` 를 다시 부르는 길**은
     * *"이미 배터리가 든 슬롯에 투입하려 한다"* 로 막힌다.
     *
     * ### ★ 재전송으로 복구할 수 있는지는 **어디서 끊겼는가**에 달렸다
     *
     * 여기서 끊긴 곳은 슬롯 보고 단계다. `BatterySwap` 은 발신 시도조차 되지 않았으므로 이벤트
     * 로그에 그 프레임이 없고, [StationSimulator.resendLastBatterySwap] 은 **되보낼 것을 찾지
     * 못한다.** 이 교환에 대한 복구 경로는 없다.
     *
     * `BatterySwap` **발신 시점**에 끊긴 경우는 다르다 — 그쪽은 재전송이 복구 경로다.
     * 바로 아래 시험 *"BatterySwap 발신 시점에 끊기면 재전송이 복구 경로다"* 가 그것을 가른다.
     */
    @Test
    fun `Gone 으로 실패한 투입은 슬롯에 배터리를 남긴다`() = runTest {
        val csms = FakeCsms("CS-GONE-INSERT")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()

            csms.mode = FakeCsms.Mode.BROKEN
            val receivedBefore = csms.receivedActions().size

            assertFailsWith<IllegalStateException> { station.insertBatteries() }

            assertEquals(SlotState.HOLDS_BATTERY, station.slotState(1), "배터리는 실제로 들어갔다")
            assertNull(station.chargingTransactionAt(1), "충전 트랜잭션은 열리지 못했다")
            assertEquals(receivedBefore, csms.receivedActions().size, "CSMS 는 투입을 전혀 보지 못했다")

            csms.mode = FakeCsms.Mode.ANSWERING
            assertFailsWith<IllegalStateException>("반쪽 상태에서 다시 투입할 길이 없다") {
                station.insertBatteries()
            }

            assertTrue(
                station.outboundBatterySwaps().isEmpty(),
                "슬롯 보고에서 끊겼으므로 BatterySwap 은 발신 시도조차 되지 않았다",
            )
            val noFrame = assertFailsWith<IllegalStateException> {
                station.resendLastBatterySwap(sameMessageId = false)
            }
            assertEquals(
                "보낸 BatterySwap 이 없다: ${station.config.stationId}",
                noFrame.message,
                "되보낼 프레임이 없다 — 재전송도 복구 경로가 아니다",
            )
        }
    }

    /**
     * ★ **`BatterySwap` 발신 시점에 끊기면 재전송이 복구 경로다.**
     *
     * 바로 위 시험과 밖에서 보이는 결말은 같다 — `insertBatteries()` 가 예외로 끝난다. 그러나
     * **끊긴 자리가 다르고, 그래서 끝이 다르다.** 여기서는 슬롯 보고와 충전 트랜잭션이 이미
     * CSMS 에 닿았고 `BatterySwap` 하나만 나가지 못했다.
     *
     * 그 프레임은 **로그에 남아 있다.** `OcppSession.emitRaw` 가 기록을 전송보다 먼저 하기
     * 때문이다 — *"a `TransmitOutcome.Gone` leaves a record of a frame that never left, and that
     * is the direction this is meant to fail in."* [StationSimulator.resendLastBatterySwap] 이
     * 읽는 것이 그 기록이므로, 선로가 돌아오면 되보낼 수 있다.
     *
     * ### 끊는 방법이 [FaultInjection.failingAt] 과 다르다
     *
     * 예외를 던지면 프레임이 만들어지기 전에 시퀀스가 끝나 로그에도 남지 않는다. 그러면 (A) 와
     * 같은 상황이 될 뿐이다. 그래서 훅에서는 **선로만 끊는다** — [FakeCsms.Mode.BROKEN] 으로
     * 바꿔 놓고 시퀀스는 그대로 진행시킨다.
     */
    @Test
    fun `BatterySwap 발신 시점에 끊기면 재전송이 복구 경로다`() = runTest {
        val csms = FakeCsms("CS-GONE-SWAP")
        val breakAtBatteryIn = FaultInjection { step, _ ->
            if (step == SimStep.BATTERY_IN) csms.mode = FakeCsms.Mode.BROKEN
        }

        stationOn(csms, faults = breakAtBatteryIn).use { station ->
            station.connect()
            station.boot()
            station.authorize()

            val slotReportsBefore = csms.received(BatterySwapWire.NOTIFY_EVENT).size
            val transactionsBefore = csms.received(BatterySwapWire.TRANSACTION_EVENT).size

            assertFailsWith<IllegalStateException> { station.insertBatteries() }

            assertTrue(csms.received(BatterySwapWire.BATTERY_SWAP).isEmpty(), "BatterySwap 은 닿지 못했다")
            assertEquals(
                slotReportsBefore + 1,
                csms.received(BatterySwapWire.NOTIFY_EVENT).size,
                "슬롯 보고는 닿았다 — (A) 와 갈리는 지점이 여기다",
            )
            assertEquals(
                transactionsBefore + 1,
                csms.received(BatterySwapWire.TRANSACTION_EVENT).size,
                "충전 트랜잭션도 닿았다",
            )

            assertEquals(SlotState.HOLDS_BATTERY, station.slotState(1), "배터리는 들어갔고 보고만 실패했다")
            val recorded = station.outboundBatterySwaps().single()

            csms.mode = FakeCsms.Mode.ANSWERING
            station.resendLastBatterySwap(sameMessageId = false)

            val delivered = csms.received(BatterySwapWire.BATTERY_SWAP).single()
            assertEquals(
                callPayloadOf(recorded).get("requestId"),
                callPayloadOf(delivered).get("requestId"),
                "원래 보내려던 교환 그대로여야 한다",
            )
            assertEquals(
                callPayloadOf(recorded).get("batteryData"),
                callPayloadOf(delivered).get("batteryData"),
                "실린 배터리도 그대로여야 한다",
            )
            assertNotEquals(
                recorded.messageId,
                delivered.messageId,
                "새 발번이라 멱등 원장이 잡지 않고 상위 계층까지 올라간다",
            )
        }
    }

    @Test
    fun `끊긴 채로 보내면 같은 실패가 나되 세션은 살아 있다`() = runTest {
        val csms = FakeCsms("CS-DETACHED")

        stationOn(csms).use { station ->
            station.connect()
            station.boot()
            station.disconnect()

            val failure = assertFailsWith<IllegalStateException> { station.authorize() }
            assertEquals(
                "${BatterySwapWire.AUTHORIZE} 을 보낼 연결이 없다: station=${station.config.stationId}",
                failure.message,
                "죽은 전송과 없는 전송은 같은 결말이다 — 둘 다 Gone 이다",
            )

            station.reconnect()
            station.authorize()

            assertEquals(1, csms.received(BatterySwapWire.AUTHORIZE).size, "다시 붙은 뒤에는 정상으로 돌아야 한다")
        }
    }

    /**
     * ★ **답이 오지 않는 것은 보내지 못한 것과 다른 경로다.**
     *
     * [FakeCsms.Mode.SILENT] 은 프레임을 받아 두되 CSMS 세션에 넘기지 않는다. 스테이션 쪽에서
     * 보면 **보내기는 성공했는데 답이 없다** — `Gone` 이 아니라 `TimedOut` 이다.
     *
     * 그 둘을 가르는 것이 [OcppSession.DEFAULT_CALL_TIMEOUT] 만큼 지나간 가상 시간이다.
     * 실제로는 1 밀리초도 기다리지 않는다.
     */
    @Test
    fun `상대가 답하지 않으면 CALL 이 타임아웃으로 끝난다`() = runTest {
        val csms = FakeCsms("CS-SILENT")
        csms.mode = FakeCsms.Mode.SILENT

        stationOn(csms).use { station ->
            station.connect()

            val failure = assertFailsWith<CallFailed> { station.boot() }

            assertTrue(
                failure.message.orEmpty().startsWith("${BatterySwapWire.BOOT_NOTIFICATION} 응답이 오지 않았다"),
                "Gone 이 아니라 타임아웃이어야 한다: ${failure.message}",
            )
            assertEquals(
                OcppSession.DEFAULT_CALL_TIMEOUT.inWholeMilliseconds,
                currentTime,
                "정확히 정해진 시간만큼만 기다려야 한다",
            )
            assertEquals(1, csms.withheld.size, "프레임은 선로에 올랐다 — 못 보낸 것이 아니다")

            // ★ 관측도 두 갈래로 갈린다. 프레임은 나갔으므로 `lastTransmitFailure` 는 비어
            //   있고, 답이 없었다는 사실은 이 칸에만 남는다 — 예외를 놓치면 어디에도 없다.
            assertEquals(failure.message, station.lastCallTimeout, "무응답이 관측으로 남지 않았다")
            assertNull(station.lastTransmitFailure, "나간 프레임을 나가지 못한 것으로 적었다")

            csms.mode = FakeCsms.Mode.ANSWERING
            station.boot()

            assertEquals(1, csms.received(BatterySwapWire.BOOT_NOTIFICATION).size, "타임아웃이 세션을 망가뜨리면 안 된다")
            assertNull(station.lastCallTimeout, "답이 왔는데 지난 무응답이 남아 있다")
        }
    }

    /** 스테이션이 **내보낸** `BatterySwap` CALL 기록. 나가지 못한 것도 여기에 남는다. */
    private fun StationSimulator.outboundBatterySwaps(): List<OcppEventRecord> =
        eventLog.of(config.stationId).filter {
            it.direction == MessageDirection.OUTBOUND && it.action == BatterySwapWire.BATTERY_SWAP
        }
}
