package dev.swapve.station

import dev.swapve.ocpp.session.OcppSession
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.swap.SlotState
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
     * 그것을 보고하지 못했을 뿐이다. 다만 그 뒤에 `insertBatteries` 를 다시 부르면
     * *"이미 배터리가 든 슬롯에 투입하려 한다"* 로 막히므로, **재시도할 길이 없다.**
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

            val failure = assertFailsWith<IllegalStateException> { station.boot() }

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

            csms.mode = FakeCsms.Mode.ANSWERING
            station.boot()

            assertEquals(1, csms.received(BatterySwapWire.BOOT_NOTIFICATION).size, "타임아웃이 세션을 망가뜨리면 안 된다")
        }
    }
}
