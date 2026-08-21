package dev.swapve.ocpp.swap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⚠️ **반전 함정을 못박는다** (Part 2 §S Ch.2).
 *
 * 이 시험이 빨개지면 배터리 장부가 통째로 뒤집힌다. 종단 시험이 같은 것을 확인하지만,
 * 그쪽은 실패했을 때 "교환이 완주하지 못했다"까지만 말해 준다. 원인이 이 한 줄이라는 것을
 * 곧바로 말하는 시험이 따로 있어야 한다.
 */
class AvailabilityStateTest {

    @Test
    fun `Occupied 가 배터리 있음이다`() {
        // *"an 'Occupied' slot does have a battery that can be used for swapping"*
        assertEquals(true, AvailabilityState.OCCUPIED.holdsBattery)
        assertEquals(true, AvailabilityState.holdsBattery("Occupied"))
    }

    @Test
    fun `Available 이 배터리 없음이다`() {
        // *"An 'Available' slot does not contain a battery for swapping"*
        assertEquals(false, AvailabilityState.AVAILABLE.holdsBattery)
        assertEquals(false, AvailabilityState.holdsBattery("Available"))
    }

    @Test
    fun `대소문자가 달라도 읽는다`() {
        // actualValue 는 enum 이 아니라 자유 문자열(maxLength 2500)이라, 스테이션이 어떤
        // 철자로 보내든 스키마 검증을 통과한다. 읽지 못하면 슬롯 상태가 "모름"이 되고
        // 배터리가 장부에서 빠진다.
        assertEquals(true, AvailabilityState.holdsBattery("occupied"))
        assertEquals(true, AvailabilityState.holdsBattery("OCCUPIED"))
        assertEquals(false, AvailabilityState.holdsBattery("available"))
        assertEquals(AvailabilityState.UNAVAILABLE, AvailabilityState.parse("UNAVAILABLE"))
    }

    @Test
    fun `내보내는 철자는 정본 그대로다`() {
        // 관대한 것은 받을 때뿐이다. 우리가 만드는 값은 흔들리지 않는다.
        assertEquals("Occupied", AvailabilityState.wireOf(holdsBattery = true))
        assertEquals("Available", AvailabilityState.wireOf(holdsBattery = false))
    }

    @Test
    fun `배터리를 넣으면 Occupied 를 보고한다`() {
        assertEquals("Occupied", AvailabilityState.wireOf(holdsBattery = true))
        assertEquals("Available", AvailabilityState.wireOf(holdsBattery = false))
    }

    @Test
    fun `Unavailable 은 배터리 유무를 말해 주지 않는다`() {
        // 모른다는 사실을 false 로 뭉개지 않는다.
        assertNull(AvailabilityState.UNAVAILABLE.holdsBattery)
        assertNull(AvailabilityState.holdsBattery("Unavailable"))
    }

    @Test
    fun `모르는 값은 null 이다`() {
        assertNull(AvailabilityState.parse("Reserved"))
        assertNull(AvailabilityState.holdsBattery("Reserved"))
    }

    @Test
    fun `왕복해도 의미가 뒤집히지 않는다`() {
        listOf(true, false).forEach { holdsBattery ->
            assertEquals(holdsBattery, AvailabilityState.holdsBattery(AvailabilityState.wireOf(holdsBattery)))
        }
    }

    @Test
    fun `전선 위의 값은 스키마의 표기 그대로다`() {
        // AvailabilityStateEnumType — 열거형 이름이 바뀌어도 전선 위의 값은 흔들리지 않아야 한다.
        assertTrue(AvailabilityState.entries.map { it.wireValue }.containsAll(listOf("Available", "Occupied", "Unavailable")))
    }
}
