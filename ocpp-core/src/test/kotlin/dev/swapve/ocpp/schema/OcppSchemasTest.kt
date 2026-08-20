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

    @Test
    fun `판본을 스키마 원문에서 얻는다`() {
        assertContains(OcppSchemas.version, "OCPP 2.1 Edition 1")

        // 손으로 적은 값이 아니라 원문에서 온 값임을 대조한다. schemas/ 를 교체하면
        // 양쪽이 함께 바뀌므로 이 시험은 그대로 통과한다.
        assertContains(OcppSchemas.read("BatterySwapRequest"), OcppSchemas.version)
    }

    /**
     * ★ **일반 경로를 쓰지 않는다.**
     *
     * `ocpp/schemas/` 처럼 흔한 경로에 두면 다른 OCPP 라이브러리나 소비자의 리소스와
     * 클래스패스에서 겹친다. 겹치면 `getResourceAsStream` 이 첫 번째만 돌려주므로 인덱스가
     * 통째로 가려지고, **모든 페이로드가 "모르는 action" 으로 거부된다** — 예외가 아니라
     * 오판정이라 원인 추적이 어렵다. 그래서 경로에 패키지 이름을 붙였다.
     */
    @Test
    fun `스키마는 패키지 경로 아래에만 있다`() {
        val loader = OcppSchemas::class.java.classLoader

        assertTrue(
            loader.getResource("dev/swapve/ocpp/schemas/_index.txt") != null,
            "패키지 경로에 인덱스가 없다",
        )
        assertTrue(
            loader.getResource("ocpp/schemas/_index.txt") == null,
            "일반 경로 ocpp/schemas/ 에 인덱스가 남아 있다 — 소비자 리소스와 겹칠 수 있다",
        )
    }
}
