package dev.swapve.station

import dev.swapve.ocpp.rpc.RpcErrorCode
import dev.swapve.ocpp.session.InboundResponse
import dev.swapve.ocpp.session.OcppCall
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.swap.SlotState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **상대가 모르는 action 을 만났을 때** (A2).
 *
 * ### 왜 이 축이 생겼나
 *
 * 이 시뮬레이터를 우리 CSMS 가 아닌 다른 OCPP 2.0.1 구현에 붙여 봤더니
 * `SecurityEventNotification` 을 모른다고 답했고, **부팅 시퀀스가 거기서 통째로 죽었다.**
 * 그 뒤의 교환은 한 번도 시도되지 못했으므로, 상대가 무엇을 더 못 하는지도 알 수 없었다.
 * 상대의 능력을 알아보러 간 도구가 첫 미구현 응답에서 멈추면 그 목적을 이루지 못한다.
 *
 * ### 관대함이 어디까지인지가 이 시험의 전부다
 *
 * 넘어가는 것은 **응답 본문을 아무도 읽지 않는 통보**([StationSimulator] 의 `notify`)가
 * **"그 action 을 모른다"는 코드**로 거부됐을 때뿐이다. 두 조건 중 하나라도 어긋나면 지금까지와
 * 똑같이 예외다 — 아는 action 을 처리하다 난 `InternalError` 도, 답을 읽는 `Authorize` 의
 * 거부도 그대로 시나리오의 실패다. 아래 마지막 두 시험이 그 경계를 지킨다.
 */
class StationUnsupportedActionTest {

    @Test
    fun `SecurityEventNotification 을 모른다고 답해도 부팅과 교환이 끝까지 간다`() = runTest {
        val csms = FakeCsms("CS-UNSUP")
        csms.refuse(BatterySwapWire.SECURITY_EVENT_NOTIFICATION, RpcErrorCode.NotSupported)

        stationOn(csms).use { station ->
            station.connect()
            station.boot()
            station.runSwap()

            assertTrue(
                csms.received(BatterySwapWire.SECURITY_EVENT_NOTIFICATION).isNotEmpty(),
                "보내기는 보냈다 — 거부당한 것이지 건너뛴 것이 아니다",
            )
            assertEquals(
                SlotState.HOLDS_BATTERY,
                station.slotState(1),
                "부팅 뒤의 교환까지 실제로 진행됐다 — 투입 슬롯에 배터리가 들어 있다",
            )
            assertEquals(SlotState.EMPTY, station.slotState(2), "반출 슬롯은 비었다")
            assertTrue(
                csms.received(BatterySwapWire.BATTERY_SWAP).isNotEmpty(),
                "죽은 지점 뒤의 BatterySwap 이 CSMS 에 닿았다 — 이것이 이 변경의 목적이다",
            )
        }
    }

    @Test
    fun `모른다고 답한 action 이 관측에 남고 중복은 한 번만 남는다`() = runTest {
        val csms = FakeCsms("CS-UNSUP-OBS")
        csms.refuse(BatterySwapWire.SECURITY_EVENT_NOTIFICATION, RpcErrorCode.NotSupported)

        stationOn(csms).use { station ->
            station.connect()
            station.boot()

            assertEquals(
                listOf(BatterySwapWire.SECURITY_EVENT_NOTIFICATION),
                station.unsupportedActions,
                "넘어갔다는 사실이 남는 자리는 여기 하나뿐이다 — 이벤트 로그의 CALLERROR 는 " +
                    "그것이 시나리오를 멈춘 실패였는지 넘어간 통보였는지 말하지 않는다",
            )

            // 재부팅이 같은 통보를 한 번 더 보낸다. 상대의 답도 같다.
            station.reboot()

            assertEquals(
                2,
                csms.received(BatterySwapWire.SECURITY_EVENT_NOTIFICATION).size,
                "두 번 보냈다",
            )
            assertEquals(
                listOf(BatterySwapWire.SECURITY_EVENT_NOTIFICATION),
                station.unsupportedActions,
                "그래도 목록에는 한 번만 남는다 — 이것은 사건의 수가 아니라 상대가 모르는 것의 목록이다",
            )
        }
    }

    @Test
    fun `NotImplemented 도 같게 다뤄진다`() = runTest {
        val csms = FakeCsms("CS-UNSUP-NOTIMPL")
        csms.refuse(BatterySwapWire.NOTIFY_EVENT, RpcErrorCode.NotImplemented)

        stationOn(csms).use { station ->
            station.connect()
            station.boot()

            assertEquals(
                listOf(BatterySwapWire.NOTIFY_EVENT),
                station.unsupportedActions,
                "표준이 둘을 두었고 구현마다 고르는 것이 다르다. 통보하는 쪽에게는 같은 결론이다",
            )
        }
    }

    @Test
    fun `같은 통보라도 InternalError 는 여전히 예외다`() = runTest {
        val csms = FakeCsms("CS-UNSUP-INTERNAL")
        csms.refuse(BatterySwapWire.SECURITY_EVENT_NOTIFICATION, RpcErrorCode.InternalError)

        stationOn(csms).use { station ->
            station.connect()

            val failure = assertFailsWith<IllegalStateException> { station.boot() }

            assertTrue(
                failure.message.orEmpty().startsWith("${BatterySwapWire.SECURITY_EVENT_NOTIFICATION} 이 거부됐다"),
                "아는 action 을 처리하다 실패한 것은 시나리오의 실패다: ${failure.message}",
            )
            assertTrue(
                station.unsupportedActions.isEmpty(),
                "모른다고 한 적이 없으므로 관측에도 남지 않는다",
            )
        }
    }

    @Test
    fun `답을 읽는 Authorize 는 NotSupported 여도 예외다`() = runTest {
        val csms = FakeCsms("CS-UNSUP-AUTH")
        csms.refuse(BatterySwapWire.AUTHORIZE, RpcErrorCode.NotSupported)

        stationOn(csms).use { station ->
            station.connect()
            station.boot()

            val failure = assertFailsWith<IllegalStateException> { station.authorize() }

            assertTrue(
                failure.message.orEmpty().startsWith("${BatterySwapWire.AUTHORIZE} 이 거부됐다"),
                "인가 결과를 읽어 다음을 정하는 호출이다 — 답이 없으면 이어갈 근거가 없다: ${failure.message}",
            )
            assertTrue(
                station.unsupportedActions.isEmpty(),
                "관대함은 통보에만 있다. 이 목록이 늘면 경계가 넓어진 것이다",
            )
        }
    }
}

/**
 * 이 action 하나만 [code] 로 거부하고 나머지는 지금까지처럼 답한다.
 *
 * 기본 응답기를 **읽어서 감싼다** — 다시 짜면 시험이 확인하는 것이 실제 왕복이 아니라
 * 이 파일이 적어 둔 답이 된다.
 */
private fun FakeCsms.refuse(action: String, code: RpcErrorCode) {
    val answer: suspend (OcppCall) -> InboundResponse = onStationCall
    onStationCall = { call ->
        if (call.action == action) {
            InboundResponse.Fail(code, "이 시험의 CSMS 가 거부한다: $action")
        } else {
            answer(call)
        }
    }
}
