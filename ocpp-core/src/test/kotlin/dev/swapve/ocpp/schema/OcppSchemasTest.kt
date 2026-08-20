package dev.swapve.ocpp.schema

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M0 뼈대 검증 — 공식 스키마가 빌드 산출물의 클래스패스에 실제로 실려 있는가.
 *
 * 이 테스트가 깨지면 이후 모든 스키마 검증(M2)이 무의미해진다.
 */
class OcppSchemasTest {

    @Test
    fun `공식 스키마 181개가 클래스패스에 실린다`() {
        assertEquals(181, OcppSchemas.names.size, "schemas/ 의 .json 개수와 일치해야 한다")
    }

    @Test
    fun `Battery Swap 기능 블록 메시지 4종이 모두 존재한다`() {
        // 2.1 이 신설한 전용 메시지
        listOf(
            "BatterySwapRequest",
            "BatterySwapResponse",
            "RequestBatterySwapRequest",
            "RequestBatterySwapResponse",
        ).forEach { assertTrue(OcppSchemas.contains(it), "$it 스키마가 없다") }
    }

    @Test
    fun `스키마 원문이 수정되지 않은 채로 읽힌다`() {
        val schema = OcppSchemas.read("BatterySwapRequest")

        assertContains(schema, "\"\$id\": \"urn:OCPP:Cp:2:2025:1:BatterySwapRequest\"")
        // eventType 의 세 값. BatteryOutTimeout 이 §4.7 의 핵심.
        assertContains(schema, "BatteryOutTimeout")
        assertContains(schema, "Creative Commons Attribution-NoDerivatives")
    }

    @Test
    fun `없는 스키마는 조용히 넘어가지 않는다`() {
        assertFailsWith<IllegalArgumentException> { OcppSchemas.read("NoSuchRequest") }
    }
}
