package dev.swapve.station

import dev.swapve.swap.IdToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 시나리오 구성의 불변식.
 *
 * 종단 시험은 **정상 경로가 통한다**를 보여 준다. 이 시험은 **성립하지 않는 시나리오가
 * 애초에 만들어지지 않는다**를 보여 준다 — 어긋난 구성으로 시험을 돌려 놓고 결과를
 * 해석하려 드는 것이 가장 흔한 시간 낭비다.
 */
class StationSimConfigTest {

    @Test
    fun `연결 URL 은 엔드포인트에 스테이션 식별자를 붙인 것이다`() {
        // Part 4 §3.1.1 — 마지막 경로 세그먼트가 스테이션 식별자다.
        assertEquals("ws://localhost:8080/ocpp/CS001", config().connectUrl)
        assertEquals("ws://localhost:8080/ocpp/CS001", config(csmsUrl = "ws://localhost:8080/ocpp/").connectUrl)
    }

    @Test
    fun `투입 슬롯은 비어 있어야 한다`() {
        // Part 6 `AuthorizedBatterySwapping` 전제조건.
        assertFailsWith<IllegalArgumentException> {
            config(insertSlots = listOf(3), dispenseSlots = listOf(4))
        }
    }

    @Test
    fun `반출 슬롯에는 배터리가 있어야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            config(insertSlots = listOf(1), dispenseSlots = listOf(2))
        }
    }

    @Test
    fun `투입 슬롯과 반출 슬롯은 겹칠 수 없다`() {
        assertFailsWith<IllegalArgumentException> {
            config(
                slots = listOf(SlotConfig(1, battery = SimBattery("BAT-1", 50.0, 90.0))),
                insertSlots = listOf(1),
                dispenseSlots = listOf(1),
            )
        }
    }

    @Test
    fun `들어온 수와 나간 수가 맞아야 한다`() {
        // COMPLETED 불변식을 시나리오 단계에서 미리 막는다.
        assertFailsWith<IllegalArgumentException> {
            config(
                insertSlots = listOf(1, 2),
                dispenseSlots = listOf(3),
                incomingBatteries = listOf(
                    SimBattery("BAT-A", 10.0, 90.0),
                    SimBattery("BAT-B", 20.0, 90.0),
                ),
            )
        }
    }

    @Test
    fun `슬롯 번호 0 은 슬롯이 아니다`() {
        // EVSE id 0 은 "충전소 전체"를 가리키는 예약값이다 (Part 2 §K).
        assertFailsWith<IllegalArgumentException> { SlotConfig(0) }
    }

    @Test
    fun `실행 진입점이 실제로 실행 가능하다`() {
        // application 플러그인의 mainClass 가 가리키는 정적 main 이 실제로 있는가.
        // 없으면 `./gradlew :station-sim:run` 이 실행 시점에야 깨진다.
        val main = StationSimCli::class.java.getMethod("main", Array<String>::class.java)
        assertEquals(true, java.lang.reflect.Modifier.isStatic(main.modifiers))

        // --help 는 연결도 하지 않고 사용법만 찍고 끝난다.
        StationSimCli.main(arrayOf("--help"))
    }

    @Test
    fun `교환 순서의 전선 표기는 스펙 표기 그대로다`() {
        // S03.FR.07 — 역순이면 `BatterySwapCtrlr.SwapOrder = "Out-In"` 으로 보고한다.
        assertEquals("In-Out", SwapOrder.IN_OUT.wireValue)
        assertEquals("Out-In", SwapOrder.OUT_IN.wireValue)
    }

    private fun config(
        csmsUrl: String = "ws://localhost:8080/ocpp",
        slots: List<SlotConfig> = listOf(
            SlotConfig(1),
            SlotConfig(2),
            SlotConfig(3, battery = SimBattery("BAT-FULL-3", 90.0, 95.0)),
            SlotConfig(4, battery = SimBattery("BAT-FULL-4", 92.0, 96.0)),
        ),
        insertSlots: List<Int> = listOf(1, 2),
        dispenseSlots: List<Int> = listOf(3, 4),
        incomingBatteries: List<SimBattery> = listOf(
            SimBattery("BAT-USED-1", 20.0, 88.0),
            SimBattery("BAT-USED-2", 25.0, 89.0),
        ),
    ) = StationSimConfig(
        csmsUrl = csmsUrl,
        stationId = "CS001",
        slots = slots,
        idToken = IdToken("RFID-0001", "ISO14443"),
        requestId = 1,
        insertSlots = insertSlots,
        dispenseSlots = dispenseSlots,
        incomingBatteries = incomingBatteries,
    )
}
