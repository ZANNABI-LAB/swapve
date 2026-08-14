package dev.swapve.swap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BatteryDataTest {

    @Test
    fun `SoC 가 범위 밖이면 생성 시점에 거부된다`() {
        assertFailsWith<IllegalArgumentException> { BatteryData(SlotId(0), "1234", soC = 100.1, soH = 85.0) }
        assertFailsWith<IllegalArgumentException> { BatteryData(SlotId(0), "1234", soC = -0.1, soH = 85.0) }
    }

    @Test
    fun `SoH 가 범위 밖이면 생성 시점에 거부된다`() {
        assertFailsWith<IllegalArgumentException> { BatteryData(SlotId(0), "1234", soC = 50.0, soH = 101.0) }
        assertFailsWith<IllegalArgumentException> { BatteryData(SlotId(0), "1234", soC = 50.0, soH = -1.0) }
    }

    @Test
    fun `경계값 0 과 100 은 허용된다`() {
        val empty = BatteryData(SlotId(0), "1234", soC = 0.0, soH = 0.0)
        val full = BatteryData(SlotId(0), "1234", soC = 100.0, soH = 100.0)

        assertEquals(0.0, empty.soC)
        assertEquals(100.0, full.soH)
    }

    @Test
    fun `일련번호가 비면 거부된다`() {
        assertFailsWith<IllegalArgumentException> { BatteryData(SlotId(0), " ", soC = 50.0, soH = 50.0) }
    }

    @Test
    fun `슬롯 번호는 음수일 수 없다`() {
        assertFailsWith<IllegalArgumentException> { SlotId(-1) }
    }

    @Test
    fun `인가 토큰은 식별자와 종류를 함께 가진다`() {
        // PLAN §11.3 — 사용자 테이블 FK 가 아니라 값 객체다.
        val token = IdToken("049A1B2C3D", "ISO14443")

        assertEquals(token, IdToken("049A1B2C3D", "ISO14443"))
        assertEquals("ISO14443", token.type)
        assertFailsWith<IllegalArgumentException> { IdToken("049A1B2C3D", "") }
    }

    @Test
    fun `토큰 종류는 표에 없는 값도 받는다`() {
        // 로밍 토큰은 우리가 아는 종류가 아닐 수 있다. 버리지 않고 기록한다 (PLAN §11.0).
        assertEquals("SomeRoamingScheme", IdToken("XYZ", "SomeRoamingScheme").type)
    }
}
