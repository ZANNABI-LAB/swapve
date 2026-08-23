package dev.swapve.csms.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.ocpp.session.MessageDirection
import dev.swapve.ocpp.session.OcppEventRecord
import dev.swapve.station.StationSimulator
import dev.swapve.station.SwapOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 시뮬레이터가 보내는 메시지가 **Part 6 스테이션 측 Tool validation** 을 통과하는지 본다
 * (시험 대상이 Charging Station 인 케이스가 곧 시뮬레이터의 명세다).
 *
 * 검사 대상은 시뮬레이터가 남긴 이벤트 로그의 **원문**이다. 내부 상태가 아니라
 * 실제로 전선 위로 나간 바이트를 본다는 것이 요점이다.
 *
 * ## 기댓값을 왜 리터럴로 적어 두었나
 *
 * 이 파일의 기댓값은 전부 **이 파일 안에 직접 쓴 문자열 리터럴**이다. `BatterySwapWire` 나
 * `AvailabilityState` 를 참조하지 않는다. 그 상수를 기댓값으로 쓰면 시뮬레이터가 실어 보낸
 * 상수를 같은 상수와 견주는 꼴이라, 상수를 잘못 고쳐도 시험이 초록으로 남았다.
 *
 * 다만 이것으로 **외부 심판이 생기는 것은 아니다.** 리터럴 역시 우리가 옮겨 적은 것이고,
 * 출처는 (1) 이 파일에 Part 6 Tool validation 을 옮겨 적어 둔 아래 주석들과
 * (2) `schemas/` 의 공식 JSON 스키마 enum 대조뿐이다. 스펙 PDF 원문을 다시 읽어 맞춘 것이
 * 아니므로 *"스펙에 대조했다"* 고 읽으면 안 된다. 이 배치가 사는 것은 딱 하나다 —
 * **기댓값과 프로덕션 상수가 서로 다른 두 자리에 있게 되어, 상수를 조용히 고치면 시험이
 * 깨진다.** 리터럴 옆 주석이 그 값을 어디서 옮겨 왔는지를 가리키므로 지우지 말 것.
 *
 * `component.name`·`variable.name`·`SecurityEventNotification.type`·`idToken.type` 은
 * 스키마상 enum 이 아니라 자유 문자열이어서 스키마 대조로는 확인할 수 없었다. 근거가 이
 * 파일의 주석뿐이라는 뜻이다. 그래도 상수 참조보다는 리터럴이 낫다고 보고 옮겨 적었다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
class ProtocolContractTest {

    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    // ------------------------------------------------------------------ BootedBatterySwapping

    @Test
    fun `부팅 시퀀스가 모든 슬롯의 상태와 보안 이벤트를 알린다`() {
        val sent = runScenario(SwapOrder.IN_OUT, "CS-CONTRACT-BOOT", 4201).outbound()

        val boot = assertNotNull(sent.firstOrNull { it.action == "BootNotification" })
        assertTrue(sent.indexOf(boot) == 0, "BootNotification 이 첫 메시지여야 한다")

        // 3. 충전소가 **모든 커넥터의 현재 상태를 알린다**
        val statusReports = sent.filter { it.action == "NotifyEvent" }
            .map { it.payloadOf().slotStatus() }
        assertEquals(listOf(1, 2, 3, 4), statusReports.take(4).map { it.slotId }, "슬롯 4개 전부를 알려야 한다")

        // Tool validation: trigger=Delta, component.name=Connector, variable.name=AvailabilityState,
        // actualValue 는 Available 또는 Occupied — 아래 리터럴은 이 줄에서 옮겨 적은 것이다.
        statusReports.forEach { report ->
            assertEquals("Delta", report.trigger)
            assertEquals("Connector", report.componentName)
            assertEquals("AvailabilityState", report.variableName)
            assertTrue(
                report.actualValue in setOf("Available", "Occupied"),
                "actualValue=${report.actualValue}",
            )
        }

        // 5. 충전소가 `SecurityEventNotificationRequest` — type 은 StartupOfTheDevice 또는 ResetOrReboot
        val security = assertNotNull(sent.firstOrNull { it.action == "SecurityEventNotification" })
        val type = security.payloadOf().path("type").asText()
        assertTrue(type in setOf("StartupOfTheDevice", "ResetOrReboot"), "type=$type")

        // deprecated 된 StatusNotification 은 쓰지 않는다.
        assertTrue(sent.none { it.action == "StatusNotification" })
    }

    // ------------------------------------------------------------------ ⚠️ 반전 함정

    @ParameterizedTest(name = "{0} — 넣으면 Occupied, 빼면 Available 을 보고한다")
    @EnumSource(SwapOrder::class)
    fun `슬롯 가용성 보고가 반전돼 있다`(order: SwapOrder) {
        val sent = runScenario(order, "CS-CONTRACT-INVERT-${order.name}", 4211 + order.ordinal).outbound()

        // 부팅 때의 4건은 초기 상태 보고다. 그 뒤의 보고가 교환으로 인한 변화다.
        val reports = sent.filter { it.action == "NotifyEvent" }
            .map { it.payloadOf().slotStatus() }
        val (initial, changes) = reports.take(4) to reports.drop(4)

        // 초기 상태 — 배터리가 든 슬롯 3·4 는 Occupied, 빈 슬롯 1·2 는 Available.
        assertEquals("Available", initial.first { it.slotId == 1 }.actualValue)
        assertEquals("Occupied", initial.first { it.slotId == 3 }.actualValue)

        // 배터리를 **넣은** 슬롯 → Occupied
        SwapScenario.INSERT_SLOTS.forEach { slotId ->
            val report = assertNotNull(changes.lastOrNull { it.slotId == slotId }, "슬롯 $slotId 변화 보고")
            assertEquals("Occupied", report.actualValue, "슬롯 $slotId")
        }
        // 배터리를 **뺀** 슬롯 → Available
        SwapScenario.DISPENSE_SLOTS.forEach { slotId ->
            val report = assertNotNull(changes.lastOrNull { it.slotId == slotId }, "슬롯 $slotId 변화 보고")
            assertEquals("Available", report.actualValue, "슬롯 $slotId")
        }
    }

    // ------------------------------------------------------------------ EVConnectedPreSession

    @Test
    fun `TransactionEvent(Started) 가 CablePluggedIn 과 evse_connectorId 를 싣는다`() {
        val sent = runScenario(SwapOrder.IN_OUT, "CS-CONTRACT-TX", 4221).outbound()

        val started = sent.filter { it.action == "TransactionEvent" }
            .map { it.payloadOf() }
            .filter { it.path("eventType").asText() == "Started" }
        assertTrue(started.isNotEmpty(), "Started 사건이 없다")

        started.forEach { event ->
            // Tool validation: 케이블이 꽂혀 시작한 사건이므로 triggerReason 은 CablePluggedIn
            assertEquals("CablePluggedIn", event.path("triggerReason").asText())
            // Tool validation: evse 와 evse.connectorId 가 **둘 다** 있어야 한다
            assertTrue(event.path("evse").isObject, "evse 가 없다")
            assertTrue(event.path("evse").path("connectorId").isInt, "evse.connectorId 가 없다")
            // idToken.type 은 Central 또는 NoAuthorization
            val idToken = event.path("idToken")
            val type = idToken.path("type").asText()
            when (type) {
                "Central" ->
                    assertEquals(SwapScenario.CHARGING_TOKEN, idToken.path("idToken").asText())
                // NoAuthorization 이면 **빈 문자열**이다
                "NoAuthorization" ->
                    assertEquals("", idToken.path("idToken").asText())

                else -> error("idToken.type 은 Central 또는 NoAuthorization 이어야 한다: $type")
            }
        }
    }

    @Test
    fun `EnergyTransferStarted 가 Charging 으로 보고된다`() {
        val sent = runScenario(SwapOrder.IN_OUT, "CS-CONTRACT-CHARGING", 4231).outbound()

        val updated = sent.filter { it.action == "TransactionEvent" }
            .map { it.payloadOf() }
            .filter { it.path("eventType").asText() == "Updated" }

        assertEquals(SwapScenario.INSERT_SLOTS.size, updated.size, "투입된 배터리마다 한 건")
        updated.forEach { event ->
            // 충전이 시작됐다는 보고이므로 triggerReason=ChargingStateChanged, chargingState=Charging
            assertEquals("ChargingStateChanged", event.path("triggerReason").asText())
            assertEquals("Charging", event.path("transactionInfo").path("chargingState").asText())
        }
    }

    // ------------------------------------------------------------------ BatterySwap

    @ParameterizedTest(name = "{0} — BatteryIn 의 batteryData 가 배터리마다 4개 필드를 갖는다")
    @EnumSource(SwapOrder::class)
    fun `BatterySwap 의 batteryData 가 완전하다`(order: SwapOrder) {
        val sent = runScenario(order, "CS-CONTRACT-DATA-${order.name}", 4241 + order.ordinal).outbound()
        val requestId = 4241 + order.ordinal

        val swaps = sent.filter { it.action == "BatterySwap" }.map { it.payloadOf() }
        assertEquals(2, swaps.size, "입고 1건 + 출고 1건")

        val batteryIn = assertNotNull(swaps.firstOrNull { it.path("eventType").asText() == "BatteryIn" })
        assertEquals(requestId, batteryIn.path("requestId").asInt())
        assertEquals(SwapScenario.AUTHORIZED_TOKEN.idToken, batteryIn.path("idToken").path("idToken").asText())

        val entries = batteryIn.path("batteryData")
        assertEquals(SwapScenario.INCOMING.size, entries.size(), "투입된 배터리마다 한 항목")
        entries.forEach { entry ->
            assertTrue(entry.path("evseId").isInt, "evseId 가 없다")
            assertTrue(entry.path("serialNumber").isTextual, "serialNumber 가 없다")
            assertTrue(entry.path("soC").isNumber, "soC 가 없다")
            assertTrue(entry.path("soH").isNumber, "soH 가 없다")
        }
    }

    @ParameterizedTest(name = "{0} — 입고와 출고가 같은 requestId 를 쓴다")
    @EnumSource(SwapOrder::class)
    fun `입고와 출고가 같은 requestId 를 승계한다`(order: SwapOrder) {
        // S02.FR.02 — 스테이션은 이어지는 BatterySwapRequest 에 **같은 requestId 를 써야 한다(SHALL)**.
        val requestId = 4251 + order.ordinal
        val sent = runScenario(order, "CS-CONTRACT-REQID-${order.name}", requestId).outbound()

        val swaps = sent.filter { it.action == "BatterySwap" }.map { it.payloadOf() }
        assertEquals(2, swaps.size)
        assertEquals(listOf(requestId, requestId), swaps.map { it.path("requestId").asInt() })

        // 순서만 다르고 짝은 같다 — eventType 은 BatteryIn / BatteryOut.
        val expectedOrder = when (order) {
            SwapOrder.IN_OUT -> listOf("BatteryIn", "BatteryOut")
            SwapOrder.OUT_IN -> listOf("BatteryOut", "BatteryIn")
        }
        assertEquals(expectedOrder, swaps.map { it.path("eventType").asText() })
    }

    // ------------------------------------------------------------------ 공통

    private data class SlotStatusReport(
        val slotId: Int,
        val trigger: String,
        val actualValue: String,
        val componentName: String,
        val variableName: String,
    )

    private fun JsonNode.slotStatus(): SlotStatusReport {
        val event = path("eventData").first()
        return SlotStatusReport(
            slotId = event.path("component").path("evse").path("id").asInt(),
            trigger = event.path("trigger").asText(),
            actualValue = event.path("actualValue").asText(),
            componentName = event.path("component").path("name").asText(),
            variableName = event.path("variable").path("name").asText(),
        )
    }

    /** 프레임 원문에서 CALL 페이로드를 꺼낸다 — `[2,"<id>","<action>",{payload}]`. */
    private fun OcppEventRecord.payloadOf(): JsonNode = mapper.readTree(payload).get(3)

    /** 시뮬레이터가 **보낸** 것만. 우리가 받은 응답은 여기서 볼 대상이 아니다. */
    private fun StationSimulator.outbound(): List<OcppEventRecord> =
        eventLog.of(config.stationId).filter { it.direction == MessageDirection.OUTBOUND }

    private fun runScenario(order: SwapOrder, stationId: String, requestId: Int): StationSimulator {
        val simulator = SwapScenario.simulator(SwapScenario.config(port, stationId, order, requestId))
        simulator.use {
            runBlocking {
                it.connect()
                it.bootAndSwap()
            }
        }
        return simulator
    }
}
