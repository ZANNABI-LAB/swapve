package dev.swapve.swap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlotTest {

    private val battery = BatteryData(SlotId(1), "1234", soC = 23.0, soH = 85.0)

    @Test
    fun `EMPTY 는 배터리가 없다는 뜻이다`() {
        // ⚠️ 프로토콜의 가용성 열거형은 의미가 반대다. 도메인은 관측 사실대로 명명한다.
        val slot = Slot(SlotId(1), SlotState.EMPTY)

        assertNull(slot.battery)
        assertFailsWith<IllegalArgumentException> { Slot(SlotId(1), SlotState.EMPTY, battery) }
    }

    @Test
    fun `HOLDS_BATTERY 는 교환에 쓸 배터리가 있다는 뜻이다`() {
        val slot = Slot(SlotId(1), SlotState.HOLDS_BATTERY, battery)

        assertEquals(battery, slot.battery)
        assertFailsWith<IllegalArgumentException> { Slot(SlotId(1), SlotState.HOLDS_BATTERY) }
    }

    @Test
    fun `슬롯 상태 이름에 프로토콜 어휘가 새어 들어오지 않았다`() {
        // 이 목록이 늘거나 이름이 프로토콜 열거형으로 바뀌면 도메인이 프로토콜 열거형을 그대로 쓰지 않는다는 원칙 위반이다.
        assertEquals(
            setOf("EMPTY", "HOLDS_BATTERY", "UNUSABLE"),
            SlotState.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `쓸 수 없는 슬롯은 배터리 유무와 무관하다`() {
        // 고장 난 슬롯에 배터리가 갇혀 있을 수 있다.
        assertEquals(battery, Slot(SlotId(1), SlotState.UNUSABLE, battery).battery)
        assertNull(Slot(SlotId(1), SlotState.UNUSABLE).battery)
    }

    @Test
    fun `배터리가 기록한 슬롯과 실제 슬롯이 어긋나면 거부된다`() {
        assertFailsWith<IllegalArgumentException> { Slot(SlotId(2), SlotState.HOLDS_BATTERY, battery) }
    }

    @Test
    fun `스테이션은 소유 사업자를 가진다`() {
        // 값이 항상 하나여도 둔다.
        val station = Station(
            id = StationId("KR-SEOUL-001"),
            operatorId = OperatorId("ZIGBANG-MOBILITY"),
            slots = mapOf(
                SlotId(0) to Slot(SlotId(0), SlotState.EMPTY),
                SlotId(1) to Slot(SlotId(1), SlotState.HOLDS_BATTERY, battery),
            ),
        )

        assertEquals(OperatorId("ZIGBANG-MOBILITY"), station.operatorId)
        assertEquals(listOf(SlotId(1)), station.slotsHoldingBattery.map { it.id })
    }

    @Test
    fun `슬롯 번호와 지도 키가 어긋나면 거부된다`() {
        assertFailsWith<IllegalArgumentException> {
            Station(
                id = StationId("KR-SEOUL-001"),
                operatorId = OperatorId("ZIGBANG-MOBILITY"),
                slots = mapOf(SlotId(0) to Slot(SlotId(1), SlotState.EMPTY)),
            )
        }
    }

    @Test
    fun `교환 상관키는 스테이션과 상관 번호의 쌍이다`() {
        val seoul = SwapKey(StationId("KR-SEOUL-001"), SwapRequestId(42))
        val busan = SwapKey(StationId("KR-BUSAN-007"), SwapRequestId(42))

        assertTrue(seoul != busan, "requestId 는 스테이션 범위에서만 유일하다")
    }
}
