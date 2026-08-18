package dev.swapve.station

import dev.swapve.ocpp.swap.DeviceModelVariables
import dev.swapve.ocpp.swap.VariableRef
import dev.swapve.ocpp.swap.VariableStatus
import dev.swapve.swap.IdToken
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 디바이스 모델의 계약 (PLAN §4.9, S04).
 *
 * ### 이 시험이 지키는 것은 **틀리기 쉬운 세 가지**다
 *
 * 1. **타임아웃은 변수 하나 + 인스턴스다.** `BatterySwapInTimeout`/`BatterySwapOutTimeout`
 *    이라는 변수 두 개가 아니다 (§4.9 주의 1).
 * 2. **대소문자가 일관되지 않다** — `TargetSoC` vs `MaxSoc` (§4.9 주의 2). 정본 그대로 써야
 *    한다. "고쳐서" 보내면 상대가 못 알아듣는다.
 * 3. **`MaxSoc ≥ TargetSoC` 는 스테이션이 지킨다** (S04.FR.06/10). 판정 주체의 근거는
 *    `DeviceModelVariables` KDoc 에 있다.
 */
class SimDeviceModelTest {

    // ------------------------------------------------------------------ §4.9 주의 1 — 인스턴스

    @Test
    fun `Timeout 은 변수 하나에 In Out 인스턴스로 갈린다`() {
        val model = model()

        val timeoutIn = model.read(DeviceModelVariables.timeoutIn())
        val timeoutOut = model.read(DeviceModelVariables.timeoutOut())

        assertEquals(VariableStatus.ACCEPTED, timeoutIn.status)
        assertEquals(VariableStatus.ACCEPTED, timeoutOut.status)
        // 같은 변수 이름인데 값이 다르다 — 인스턴스가 실제로 구분하고 있다는 뜻이다.
        assertEquals(DeviceModelVariables.VARIABLE_TIMEOUT, timeoutIn.ref.variable)
        assertEquals(DeviceModelVariables.VARIABLE_TIMEOUT, timeoutOut.ref.variable)
        assertEquals("30", timeoutIn.value)
        assertEquals("90", timeoutOut.value)
    }

    @Test
    fun `인스턴스 없는 Timeout 은 어느 타임아웃인지 정해지지 않아 UnknownVariable 이다`() {
        val reading = model().read(
            VariableRef(
                DeviceModelVariables.COMPONENT_BATTERY_SWAP_CTRLR,
                DeviceModelVariables.VARIABLE_TIMEOUT,
            ),
        )

        assertEquals(VariableStatus.UNKNOWN_VARIABLE, reading.status)
        assertNull(reading.value, "받아들이지 않은 답에는 값이 없다 (스키마 규정)")
        assertTrue(
            reading.additionalInfo.orEmpty().contains("In/Out"),
            "무엇이 빠졌는지 알려 줘야 한다: ${reading.additionalInfo}",
        )
    }

    @Test
    fun `변수 두 개로 모델링한 이름은 존재하지 않는다`() {
        // Part 2 본문의 축약 표기다. 정본(부록 CSV)에는 이런 변수가 없다 (§4.9 주의 1).
        listOf("BatterySwapInTimeout", "BatterySwapOutTimeout").forEach { wrongName ->
            val reading = model().read(
                VariableRef(DeviceModelVariables.COMPONENT_BATTERY_SWAP_CTRLR, wrongName),
            )
            assertEquals(VariableStatus.UNKNOWN_VARIABLE, reading.status, wrongName)
        }
    }

    // ------------------------------------------------------------------ §4.9 주의 2 — 대소문자

    @Test
    fun `변수 이름 철자가 정본과 같다`() {
        // ⚠️ 끝 글자가 서로 다르다. 이것이 정본이다 — 통일하면 틀린 것이 된다.
        assertEquals("TargetSoC", DeviceModelVariables.VARIABLE_TARGET_SOC)
        assertEquals("MaxSoc", DeviceModelVariables.VARIABLE_MAX_SOC)
        assertEquals("BatterySwapCtrlr", DeviceModelVariables.COMPONENT_BATTERY_SWAP_CTRLR)
        assertEquals("BatteryCartridge", DeviceModelVariables.COMPONENT_BATTERY_CARTRIDGE)
    }

    @Test
    fun `조회는 대소문자를 가리지 않는다`() {
        // 공식 스키마: variable.name 은 *"Case Insensitive"* 다. 상대가 어떻게 적어 보내든
        // 같은 변수를 가리켜야 한다.
        val reading = model().read(VariableRef("batteryswapctrlr", "maxsoc"))

        assertEquals(VariableStatus.ACCEPTED, reading.status)
        assertEquals("90", reading.value)
    }

    // ------------------------------------------------------------------ S04.FR.06/10

    @Test
    fun `MaxSoc 를 TargetSoC 아래로 내리는 설정은 거부된다`() {
        val model = model()

        // TargetSoC=80 인데 MaxSoc 를 70 으로 내리려 한다.
        val rejected = model.write(DeviceModelVariables.maxSoc(), "70")

        assertEquals(VariableStatus.REJECTED, rejected.status, "S04.FR.06/10 위반이 통과했다")
        assertEquals(SimDeviceModel.REASON_SOC_ORDER, rejected.reasonCode)
        // 거부됐으니 값도 그대로다 — 거부해 놓고 바꿔 두면 그게 더 나쁘다.
        assertEquals("90", model.valueOf(DeviceModelVariables.maxSoc()))
    }

    @Test
    fun `TargetSoC 를 MaxSoc 위로 올리는 설정도 같은 위반이다`() {
        // 반대 방향으로 우회할 수 있으면 검사가 아니다.
        val model = model()

        val rejected = model.write(DeviceModelVariables.targetSoC(), "95")

        assertEquals(VariableStatus.REJECTED, rejected.status)
        assertEquals(SimDeviceModel.REASON_SOC_ORDER, rejected.reasonCode)
        assertEquals("80", model.valueOf(DeviceModelVariables.targetSoC()))
    }

    @Test
    fun `제약을 지키는 설정은 받아들여진다`() {
        val model = model()

        assertEquals(VariableStatus.ACCEPTED, model.write(DeviceModelVariables.maxSoc(), "100").status)
        assertEquals(VariableStatus.ACCEPTED, model.write(DeviceModelVariables.targetSoC(), "95").status)

        assertEquals("100", model.valueOf(DeviceModelVariables.maxSoc()))
        assertEquals("95", model.valueOf(DeviceModelVariables.targetSoC()))
    }

    @Test
    fun `퍼센트가 아닌 값은 거부된다`() {
        val model = model()

        listOf("", "가득", "101", "-1").forEach { value ->
            assertEquals(
                VariableStatus.REJECTED,
                model.write(DeviceModelVariables.targetSoC(), value).status,
                "TargetSoC=$value",
            )
        }
    }

    // ------------------------------------------------------------------ S04.FR.12 — BatteryCartridge

    @Test
    fun `BatteryCartridge SoC 는 슬롯마다 다르다`() {
        val model = model()

        assertEquals("40", model.valueOf(DeviceModelVariables.batterySoC(1)))
        assertEquals("77", model.valueOf(DeviceModelVariables.batterySoC(2)))
        assertEquals("91", model.valueOf(DeviceModelVariables.batterySoH(1)))
    }

    @Test
    fun `배터리가 바뀌면 조회 결과도 바뀐다`() {
        // 저장해 두는 값이 아니라 **슬롯에서 파생하는 값**이라는 뜻이다.
        var battery = SimBattery("BAT-1", soC = 40.0, soH = 91.0)
        val model = SimDeviceModel.of(config()) { evseId -> battery.takeIf { evseId == 1 } }

        assertEquals("40", model.valueOf(DeviceModelVariables.batterySoC(1)))
        battery = battery.copy(soC = 55.0)
        assertEquals("55", model.valueOf(DeviceModelVariables.batterySoC(1)))
    }

    @Test
    fun `빈 슬롯에는 카트리지가 없다`() {
        val model = SimDeviceModel.of(config()) { null }

        val reading = model.read(DeviceModelVariables.batterySoC(1))

        // 없는 값을 지어내지 않는다 — 0% 로 답하면 "다 쓴 배터리가 꽂혀 있다"가 된다.
        assertEquals(VariableStatus.UNKNOWN_COMPONENT, reading.status)
        assertNull(reading.value)
    }

    @Test
    fun `EVSE 를 지정하지 않은 카트리지 조회는 어느 배터리인지 정해지지 않는다`() {
        val reading = model().read(
            VariableRef(
                DeviceModelVariables.COMPONENT_BATTERY_CARTRIDGE,
                DeviceModelVariables.VARIABLE_SOC,
            ),
        )

        assertEquals(VariableStatus.UNKNOWN_COMPONENT, reading.status)
    }

    @Test
    fun `배터리의 값은 설정할 수 없다`() {
        val write = model().write(DeviceModelVariables.batterySoC(1), "100")

        assertEquals(VariableStatus.REJECTED, write.status)
        assertEquals(SimDeviceModel.REASON_READ_ONLY, write.reasonCode)
    }

    // ------------------------------------------------------------------ 그 밖

    @Test
    fun `모르는 컴포넌트는 UnknownComponent 다`() {
        val reading = model().read(VariableRef("SmartChargingCtrlr", "Enabled"))

        // 스마트차징은 범위 밖이다 (PLAN §10 결정 #8). 없는 것을 있는 척하지 않는다.
        assertEquals(VariableStatus.UNKNOWN_COMPONENT, reading.status)
    }

    @Test
    fun `IdToken 은 비어 있을 수 있다 — 그것이 설정하지 않음이다`() {
        // TC_S_103_CSMS 의 전제조건이 이 상태다. 빈 값이면 충전 트랜잭션이 NoAuthorization 이다.
        val model = SimDeviceModel.of(config(chargingIdToken = null)) { null }

        val reading = model.read(DeviceModelVariables.idToken())
        assertEquals(VariableStatus.ACCEPTED, reading.status)
        assertEquals("", reading.value)

        assertEquals(VariableStatus.ACCEPTED, model.write(DeviceModelVariables.idToken(), "BSS-1").status)
        assertEquals("BSS-1", model.valueOf(DeviceModelVariables.idToken()))
    }

    @Test
    fun `Available 은 boolean 이다`() {
        val model = model()

        assertEquals("true", model.valueOf(DeviceModelVariables.available()))
        assertEquals(VariableStatus.REJECTED, model.write(DeviceModelVariables.available(), "yes").status)
        assertEquals(VariableStatus.ACCEPTED, model.write(DeviceModelVariables.available(), "false").status)
    }

    @Test
    fun `설정된 변수는 정확히 이 여섯 개다`() {
        // 목록이 늘어나면 이 시험이 먼저 그 사실을 알린다 (PLAN §4.9 표).
        val model = model()
        listOf(
            DeviceModelVariables.available(),
            DeviceModelVariables.targetSoC(),
            DeviceModelVariables.maxSoc(),
            DeviceModelVariables.idToken(),
            DeviceModelVariables.timeoutIn(),
            DeviceModelVariables.timeoutOut(),
        ).forEach { ref ->
            assertNotNull(model.valueOf(ref), "$ref 가 없다")
        }
    }

    // ------------------------------------------------------------------ 공통

    private fun model() = SimDeviceModel.of(config()) { evseId ->
        when (evseId) {
            1 -> SimBattery("BAT-1", soC = 40.0, soH = 91.0)
            2 -> SimBattery("BAT-2", soC = 77.0, soH = 88.0)
            else -> null
        }
    }

    private fun config(chargingIdToken: String? = "BSS-CENTRAL-0001") = StationSimConfig(
        csmsUrl = "ws://localhost:8080/ocpp",
        stationId = "CS-DM",
        slots = listOf(SlotConfig(1), SlotConfig(2)),
        idToken = IdToken("RFID-0001", "ISO14443"),
        requestId = 1,
        insertSlots = emptyList(),
        dispenseSlots = emptyList(),
        incomingBatteries = emptyList(),
        chargingIdToken = chargingIdToken,
        targetSoC = 80,
        maxSoc = 90,
        batteryInTimeout = Duration.ofSeconds(30),
        batteryOutTimeout = Duration.ofSeconds(90),
    )
}
