package dev.swapve.csms.devicemodel

import dev.swapve.ocpp.swap.DeviceModelVariables
import dev.swapve.ocpp.swap.VariableRef
import dev.swapve.swap.StationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `NotifyReport` 재조립의 계약 (B03, `TC_S_104_CS`).
 *
 * ### 여기서 지켜지는 것은 **유실이 드러나는가**다
 *
 * 조각이 다 온 경우는 `TcS104CsTest` 가 실제 소켓 위에서 확인한다. 이 시험이 맡는 것은
 * **그렇지 않은 경우** — 조각이 빠지거나 순서가 어긋났을 때다. 그런 일은 실제 연결에서
 * 만들어 내기 어렵지만, 조용히 삼키면 "일부인 줄 모르는 전부"가 남는다 (PLAN §5.3
 * *"유실 메시지 0"*).
 */
class DeviceModelReportRegistryTest {

    private val stationId = StationId("CS-REPORT")
    private val requestId = 7

    @Test
    fun `조각이 순서대로 다 오면 온전한 한 벌이 된다`() {
        val registry = DeviceModelReportRegistry()

        registry.record(stationId, requestId, seqNo = 0, tbc = true, generatedAt = null, variables = listOf(reported(DeviceModelVariables.targetSoC(), "80")))
        registry.record(stationId, requestId, seqNo = 1, tbc = false, generatedAt = null, variables = listOf(reported(DeviceModelVariables.maxSoc(), "90")))

        val report = registry.find(stationId, requestId)!!
        assertEquals(2, report.parts)
        assertTrue(report.isIntact)
        assertEquals("80", report.valueOf(DeviceModelVariables.targetSoC()))
        assertEquals("90", report.valueOf(DeviceModelVariables.maxSoc()))
    }

    @Test
    fun `마지막 조각이 오기 전에는 완결이 아니다`() {
        val registry = DeviceModelReportRegistry()

        registry.record(stationId, requestId, seqNo = 0, tbc = true, generatedAt = null, variables = listOf(reported(DeviceModelVariables.targetSoC(), "80")))

        val report = registry.find(stationId, requestId)!!
        assertFalse(report.isComplete, "tbc 가 참인데 완결로 봤다")
        assertFalse(report.isIntact)
    }

    /** ★ 조각이 빠지면 **그 사실이 값에 남는다.** 이어 붙이고 끝내지 않는다. */
    @Test
    fun `건너뛴 seqNo 가 유실로 남는다`() {
        val registry = DeviceModelReportRegistry()

        registry.record(stationId, requestId, seqNo = 0, tbc = true, generatedAt = null, variables = listOf(reported(DeviceModelVariables.targetSoC(), "80")))
        // seqNo 1 이 오지 않았다.
        registry.record(stationId, requestId, seqNo = 2, tbc = false, generatedAt = null, variables = listOf(reported(DeviceModelVariables.maxSoc(), "90")))

        val report = registry.find(stationId, requestId)!!
        assertEquals(listOf(1), report.missingSeqNos)
        // 마지막 조각은 왔다. 그런데도 **온전하지 않다** — 둘은 다른 질문이다.
        assertTrue(report.isComplete)
        assertFalse(report.isIntact, "조각이 빠졌는데 온전하다고 답했다")
    }

    @Test
    fun `다른 requestId 와 다른 스테이션의 보고는 섞이지 않는다`() {
        val registry = DeviceModelReportRegistry()

        registry.record(stationId, requestId, seqNo = 0, tbc = false, generatedAt = null, variables = listOf(reported(DeviceModelVariables.targetSoC(), "80")))
        registry.record(StationId("CS-OTHER"), requestId, seqNo = 0, tbc = false, generatedAt = null, variables = listOf(reported(DeviceModelVariables.targetSoC(), "55")))

        assertEquals("80", registry.find(stationId, requestId)!!.valueOf(DeviceModelVariables.targetSoC()))
        assertEquals("55", registry.find(StationId("CS-OTHER"), requestId)!!.valueOf(DeviceModelVariables.targetSoC()))
        assertNull(registry.find(stationId, requestId + 1), "청한 적 없는 보고가 있다")
    }

    /** 이름 비교는 대소문자를 가리지 않는다 (PLAN §4.9 주의 2). */
    @Test
    fun `대소문자가 달라도 같은 변수로 찾힌다`() {
        val registry = DeviceModelReportRegistry()

        registry.record(
            stationId, requestId, seqNo = 0, tbc = false, generatedAt = null,
            variables = listOf(reported(VariableRef("batteryswapctrlr", "maxsoc"), "90")),
        )

        assertEquals("90", registry.find(stationId, requestId)!!.valueOf(DeviceModelVariables.maxSoc()))
    }

    private fun reported(ref: VariableRef, value: String) = ReportedVariable(ref = ref, value = value)
}
