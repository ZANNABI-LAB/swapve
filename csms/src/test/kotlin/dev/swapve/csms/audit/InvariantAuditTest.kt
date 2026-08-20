package dev.swapve.csms.audit

import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.csms.swap.ChargingEvent
import dev.swapve.csms.swap.ChargingTransactionRegistry
import dev.swapve.csms.swap.SlotStateRegistry
import dev.swapve.csms.swap.SwapTransactionRegistry
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.session.MessageDirection
import dev.swapve.ocpp.session.OcppEventRecord
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.swap.BatteryData
import dev.swapve.swap.IdToken
import dev.swapve.swap.SlotId
import dev.swapve.swap.SlotState
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapKey
import dev.swapve.swap.SwapRequestId
import dev.swapve.swap.SwapTransaction
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ★ **감사가 실제로 검출하는지 시험한다.**
 *
 * [LoadAuditTest] 는 부하를 돌리고 감사를 통과시킨다. 그런데 **통과했다는 사실만으로는
 * 감사가 무언가를 보고 있다는 증거가 되지 않는다** — 필터가 잘못 걸려 아무것도 검사하지 않는
 * 감사도 초록으로 끝난다. 그래서 여기서는 반대로 한다: 불변식을 하나씩 **일부러 깨뜨린
 * 로그**를 만들어 해당 항목이 정말 빨개지는지 확인한다.
 *
 * 이 시험이 없으면 S4 의 "전항목 통과"는 주장이고, 있으면 판정이다.
 *
 * 로그는 [OcppEventRecord] 를 직접 만들어 쓴다 — 실제 프레임 원문과 같은 모양이라 [EventLogReplay]
 * 가 부하 때와 똑같은 경로로 읽는다. `seq` 결번처럼 정상 경로로는 만들 수 없는 상황도
 * 여기서는 만들 수 있다.
 *
 * 부하 게이트가 아니라 **L1 단위 게이트**에서 돈다. 소켓도 Spring 컨텍스트도 필요 없다.
 */
class InvariantAuditTest {

    private val validator = OcppPayloadValidator()

    // ------------------------------------------------------------------ 기준선

    @Test
    fun `정상 로그는 전항목을 통과한다`() {
        val report = audit(Fixture())

        assertTrue(
            report.failures.isEmpty(),
            "기준선이 실패하면 이후 시험이 무의미하다:\n" + report.render(),
        )
        // 항목마다 실제로 무언가를 셌다는 사실까지 확인한다.
        report.items.forEach { assertTrue(it.checked > 0, "${it.name} 이 0건을 검사했다") }
    }

    // ------------------------------------------------------------------ 항목별 검출

    @Test
    fun `장부가 어긋나면 배터리 수량 보존이 실패한다`() {
        val fixture = Fixture()
        // 배터리 두 개를 넣고 하나만 내줬다.
        fixture.batteriesIn = listOf(SlotBattery(1, "IN-1"), SlotBattery(2, "IN-2"))

        assertFails(fixture, "배터리 수량 보존", "입고 2개 ≠ 출고 1개")
    }

    @Test
    fun `들어온 배터리가 그대로 나가면 배터리 수량 보존이 실패한다`() {
        val fixture = Fixture()
        fixture.batteriesOut = listOf(SlotBattery(3, "IN-1"))

        assertFails(fixture, "배터리 수량 보존", "들어온 배터리가 그대로 나갔다")
    }

    @Test
    fun `한 슬롯이 두 교환에 겹쳐 묶이면 슬롯 이중 예약이 실패한다`() {
        val fixture = Fixture()
        // 두 번째 교환이 같은 입고 슬롯을 쓰고, 두 교환의 사건 구간이 겹친다.
        fixture.secondSwapOnSameSlot = true

        assertFails(fixture, "슬롯 이중 예약 0", "겹친다")
    }

    @Test
    fun `한 교환이 같은 슬롯으로 넣고 빼면 슬롯 이중 예약이 실패한다`() {
        val fixture = Fixture()
        fixture.batteriesOut = listOf(SlotBattery(1, "OUT-1"))

        assertFails(fixture, "슬롯 이중 예약 0", "입고와 출고에 동시에 묶였다")
    }

    @Test
    fun `응답 없는 CALL 이 있으면 유실 메시지가 실패한다`() {
        val fixture = Fixture()
        fixture.dropResponseFor = BatterySwapWire.BATTERY_SWAP

        assertFails(fixture, "유실 메시지 0", "응답되지 않았다")
    }

    @Test
    fun `같은 반쪽이 두 번 오면 상관키 유일이 실패한다`() {
        val fixture = Fixture()
        fixture.duplicateBatteryIn = true

        assertFails(fixture, "(stationId, requestId) 유일", "같은 반쪽이")
    }

    @Test
    fun `레지스트리에만 있는 교환은 상관키 유일이 실패한다`() {
        val fixture = Fixture()
        fixture.extraRegistrySwap = 99

        assertFails(fixture, "(stationId, requestId) 유일", "로그로 설명되지 않는다")
    }

    @Test
    fun `교환 완료로 입고 슬롯 충전이 닫히면 교환 충전 분리가 실패한다`() {
        val fixture = Fixture()
        // 들어온 배터리의 충전을 교환 종료와 함께 닫아 버렸다 — 두 생명주기를 합치면 안 된다는 규칙이 금지하는 상황이다.
        fixture.endIncomingCharging = true

        assertFails(fixture, "교환/충전 분리", "교환 완료와 함께 끊겼다")
    }

    @Test
    fun `seq 에 결번이 있으면 이벤트 로그 순서가 실패한다`() {
        val fixture = Fixture()
        fixture.skipSeq = true

        assertFails(fixture, "이벤트 로그 순서", "이어지지 않는다")
    }

    @Test
    fun `레지스트리가 로그와 다르면 재구성 대조가 실패한다`() {
        val fixture = Fixture()
        // 로그는 슬롯 1 에 배터리가 있다고 하는데 레지스트리는 비었다고 한다.
        fixture.registrySlotStateOverride = SlotState.EMPTY

        assertFails(fixture, "로그 재구성 ↔ 레지스트리", "슬롯 1")
    }

    @Test
    fun `레지스트리 배터리가 로그와 다르면 재구성 대조가 실패한다`() {
        val fixture = Fixture()
        fixture.registryIncomingSerialOverride = "다른-배터리"

        assertFails(fixture, "로그 재구성 ↔ 레지스트리", "입고 배터리가 다르다")
    }

    @Test
    fun `스키마를 어긴 메시지가 있으면 공식 스키마 위반이 실패한다`() {
        val fixture = Fixture()
        // `requestId` 가 빠진 BatterySwapRequest — 공식 스키마의 required 위반이다.
        fixture.corruptBatterySwapPayload = true

        assertFails(fixture, "공식 스키마 위반 0", "requestId")
    }

    @Test
    fun `CALLERROR 가 오가면 그 항목이 실패한다`() {
        val fixture = Fixture()
        fixture.answerWithCallError = BatterySwapWire.NOTIFY_EVENT

        assertFails(fixture, "CALLERROR 0", "InternalError")
    }

    @Test
    fun `검사 대상이 0건이면 통과가 아니다`() {
        // 로그도 레지스트리도 비었다. 위반은 없지만 확인한 것도 없다.
        val report = InvariantAudit(
            replayed = listOf(EventLogReplay.replay(STATION, emptyList())),
            swaps = SwapTransactionRegistry(),
            slotStates = SlotStateRegistry(),
            charging = ChargingTransactionRegistry(),
            validator = validator,
        ).run()

        assertTrue(report.failures.isNotEmpty(), "0건 검사가 통과로 처리됐다:\n" + report.render())
        assertTrue(
            report.failures.all { it.violations.isEmpty() && it.checked == 0 },
            "0건 검사 실패의 사유가 위반이 아니어야 한다",
        )
    }

    // ------------------------------------------------------------------ 시험 재료

    private fun audit(fixture: Fixture): AuditReport {
        val swaps = SwapTransactionRegistry()
        val slotStates = SlotStateRegistry()
        val charging = ChargingTransactionRegistry()
        fixture.populate(swaps, slotStates, charging)

        return InvariantAudit(
            replayed = listOf(EventLogReplay.replay(STATION, fixture.records())),
            swaps = swaps,
            slotStates = slotStates,
            charging = charging,
            validator = validator,
        ).run()
    }

    private fun assertFails(fixture: Fixture, itemName: String, expectedFragment: String) {
        val report = audit(fixture)
        val item = report.items.first { it.name == itemName }

        assertFalse(item.passed, "$itemName 이 위반을 놓쳤다:\n" + report.render())
        assertTrue(
            item.violations.any { expectedFragment in it },
            "$itemName 의 위반 설명에 '$expectedFragment' 가 없다: ${item.violations}",
        )
        // 검사 자체는 돌았어야 한다 — 대상이 사라져서 실패한 것이면 다른 문제다.
        assertTrue(item.checked > 0, "$itemName 이 0건을 검사하고 실패했다")
    }

    private data class SlotBattery(val slotId: Int, val serialNumber: String)

    /**
     * 교환 1 건이 오간 로그와, 그것과 일치하는 레지스트리 상태.
     *
     * 필드를 바꾸면 **로그만** 바뀐다 (레지스트리 쪽 재정의 필드는 예외). 그래서 대부분의
     * 시험이 "로그가 이런 모양이면 감사가 잡아내는가"를 묻게 된다.
     */
    private class Fixture {
        var batteriesIn = listOf(SlotBattery(1, "IN-1"))
        var batteriesOut = listOf(SlotBattery(3, "OUT-3"))
        var duplicateBatteryIn = false
        var secondSwapOnSameSlot = false
        var endIncomingCharging = false
        var dropResponseFor: String? = null
        var answerWithCallError: String? = null
        var corruptBatterySwapPayload = false
        var skipSeq = false
        var extraRegistrySwap: Int? = null
        var registrySlotStateOverride: SlotState? = null
        var registryIncomingSerialOverride: String? = null

        private val built = ArrayList<OcppEventRecord>()
        private var seq = 0L

        fun records(): List<OcppEventRecord> {
            if (built.isNotEmpty()) return built

            call(BatterySwapWire.AUTHORIZE, authorizePayload(), ACCEPTED_TOKEN_INFO)
            call(BatterySwapWire.NOTIFY_EVENT, slotStatusPayload(batteriesIn.first().slotId, holdsBattery = true))
            call(BatterySwapWire.TRANSACTION_EVENT, transactionEventPayload(TX_IN, batteriesIn.first().slotId, BatterySwapWire.TX_STARTED), ACCEPTED_TOKEN_INFO)
            call(BatterySwapWire.BATTERY_SWAP, batterySwapPayload(BatterySwapWire.BATTERY_IN, batteriesIn))
            if (duplicateBatteryIn) {
                call(BatterySwapWire.BATTERY_SWAP, batterySwapPayload(BatterySwapWire.BATTERY_IN, batteriesIn))
            }
            if (secondSwapOnSameSlot) {
                call(BatterySwapWire.BATTERY_SWAP, batterySwapPayload(BatterySwapWire.BATTERY_IN, batteriesIn, requestId = SECOND_REQUEST_ID))
            }
            call(BatterySwapWire.TRANSACTION_EVENT, transactionEventPayload(TX_OUT, batteriesOut.first().slotId, BatterySwapWire.TX_ENDED), ACCEPTED_TOKEN_INFO)
            if (endIncomingCharging) {
                call(BatterySwapWire.TRANSACTION_EVENT, transactionEventPayload(TX_IN, batteriesIn.first().slotId, BatterySwapWire.TX_ENDED), ACCEPTED_TOKEN_INFO)
            }
            call(BatterySwapWire.NOTIFY_EVENT, slotStatusPayload(batteriesOut.first().slotId, holdsBattery = false))
            call(BatterySwapWire.BATTERY_SWAP, batterySwapPayload(BatterySwapWire.BATTERY_OUT, batteriesOut))
            if (secondSwapOnSameSlot) {
                call(
                    BatterySwapWire.BATTERY_SWAP,
                    batterySwapPayload(BatterySwapWire.BATTERY_OUT, listOf(SlotBattery(4, "OUT-4")), requestId = SECOND_REQUEST_ID),
                )
            }

            return built
        }

        /** 레지스트리를 로그와 **일치하게** 채운다. 재정의 필드가 있을 때만 어긋난다. */
        fun populate(
            swaps: SwapTransactionRegistry,
            slotStates: SlotStateRegistry,
            charging: ChargingTransactionRegistry,
        ) {
            val station = StationId(STATION)

            // ★ 수량이 안 맞는 `Completed` 는 **타입이 거부한다** (`SwapTransaction.Completed` 의
            //   생성자 require). 그래서 장부가 깨진 상황의 레지스트리는 반쪽이 열린 채로 남는다 —
            //   감사 항목 1 이 "레지스트리에만 기대면 안 된다"고 적은 이유가 이것이다.
            val key = SwapKey(station, SwapRequestId(REQUEST_ID))
            val storedIn = batteriesIn.mapIndexed { index, battery ->
                BatteryData(
                    SlotId(battery.slotId),
                    if (index == 0) registryIncomingSerialOverride ?: battery.serialNumber else battery.serialNumber,
                    SOC,
                    SOH,
                )
            }

            swaps.store(
                key,
                if (batteriesIn.size == batteriesOut.size) {
                    SwapTransaction.Completed(
                        key = key,
                        idToken = TOKEN,
                        authorizedAt = AT,
                        batteriesIn = storedIn,
                        batteriesOut = batteriesOut.map { BatteryData(SlotId(it.slotId), it.serialNumber, SOC, SOH) },
                        startedAt = AT,
                        completedAt = AT,
                    )
                } else {
                    SwapTransaction.HalfIn(key, TOKEN, AT, storedIn, AT)
                },
            )

            if (secondSwapOnSameSlot) {
                swaps.store(
                    SwapKey(station, SwapRequestId(SECOND_REQUEST_ID)),
                    SwapTransaction.Completed(
                        key = SwapKey(station, SwapRequestId(SECOND_REQUEST_ID)),
                        idToken = TOKEN,
                        authorizedAt = AT,
                        batteriesIn = batteriesIn.map { BatteryData(SlotId(it.slotId), it.serialNumber, SOC, SOH) },
                        batteriesOut = listOf(BatteryData(SlotId(4), "OUT-4", SOC, SOH)),
                        startedAt = AT,
                        completedAt = AT,
                    ),
                )
            }

            extraRegistrySwap?.let { requestId ->
                swaps.store(
                    SwapKey(station, SwapRequestId(requestId)),
                    SwapTransaction.Authorized(SwapKey(station, SwapRequestId(requestId)), TOKEN, AT),
                )
            }

            slotStates.observe(
                station,
                SlotId(batteriesIn.first().slotId),
                registrySlotStateOverride ?: SlotState.HOLDS_BATTERY,
                AT,
            )
            slotStates.observe(station, SlotId(batteriesOut.first().slotId), SlotState.EMPTY, AT)

            charging.record(station, TX_IN, chargingEvent(BatterySwapWire.TX_STARTED, batteriesIn.first().slotId))
            if (endIncomingCharging) {
                charging.record(station, TX_IN, chargingEvent(BatterySwapWire.TX_ENDED, batteriesIn.first().slotId))
            }
            charging.record(station, TX_OUT, chargingEvent(BatterySwapWire.TX_ENDED, batteriesOut.first().slotId))
        }

        // -------------------------------------------------------------- 프레임 만들기

        private fun call(action: String, payload: String, responsePayload: String = "{}") {
            val messageId = "MSG-${built.size}"
            append(MessageDirection.INBOUND, action, messageId, """[2,"$messageId","$action",$payload]""")

            if (action == dropResponseFor) return
            if (action == answerWithCallError) {
                append(
                    MessageDirection.OUTBOUND,
                    action,
                    messageId,
                    """[4,"$messageId","InternalError","처리하지 못했다",{}]""",
                )
                return
            }
            append(MessageDirection.OUTBOUND, action, messageId, """[3,"$messageId",$responsePayload]""")
        }

        private fun append(direction: MessageDirection, action: String, messageId: String, payload: String) {
            seq++
            // 결번을 만든다 — 정상 이벤트 로그 경로로는 불가능한 상황이다.
            if (skipSeq && seq == 3L) seq++
            built += OcppEventRecord(seq, STATION, direction, action, messageId, payload, AT)
        }

        private fun authorizePayload() =
            """{"idToken":{"idToken":"${TOKEN.idToken}","type":"${TOKEN.type}"}}"""

        private fun slotStatusPayload(slotId: Int, holdsBattery: Boolean) = """
            {"generatedAt":"$AT_TEXT","seqNo":0,"eventData":[
              {"eventId":$slotId,"timestamp":"$AT_TEXT","trigger":"${BatterySwapWire.TRIGGER_DELTA}",
               "actualValue":"${if (holdsBattery) "Occupied" else "Available"}",
               "eventNotificationType":"${BatterySwapWire.NOTIFICATION_HARD_WIRED}",
               "component":{"name":"${BatterySwapWire.COMPONENT_CONNECTOR}","evse":{"id":$slotId,"connectorId":1}},
               "variable":{"name":"${BatterySwapWire.VARIABLE_AVAILABILITY_STATE}"}}]}
        """.trimIndent()

        private fun transactionEventPayload(transactionId: String, slotId: Int, eventType: String) = """
            {"eventType":"$eventType","timestamp":"$AT_TEXT",
             "triggerReason":"${BatterySwapWire.TRIGGER_REASON_CABLE_PLUGGED_IN}","seqNo":0,
             "transactionInfo":{"transactionId":"$transactionId"},
             "evse":{"id":$slotId,"connectorId":1}}
        """.trimIndent()

        private fun batterySwapPayload(
            eventType: String,
            batteries: List<SlotBattery>,
            requestId: Int = REQUEST_ID,
        ): String {
            val data = batteries.joinToString(",") {
                """{"evseId":${it.slotId},"serialNumber":"${it.serialNumber}","soC":$SOC,"soH":$SOH}"""
            }
            val requestIdField = if (corruptBatterySwapPayload) "" else """"requestId":$requestId,"""
            return """
                {"eventType":"$eventType",$requestIdField
                 "idToken":{"idToken":"${TOKEN.idToken}","type":"${TOKEN.type}"},
                 "batteryData":[$data]}
            """.trimIndent()
        }

        private fun chargingEvent(eventType: String, slotId: Int) = ChargingEvent(
            eventType = eventType,
            triggerReason = BatterySwapWire.TRIGGER_REASON_CABLE_PLUGGED_IN,
            seqNo = 0,
            slotId = SlotId(slotId),
            chargingState = null,
            stoppedReason = null,
            idToken = null,
            at = AT,
        )
    }

    private companion object {
        const val STATION = "CS-AUDIT-SELF"
        const val REQUEST_ID = 42
        const val SECOND_REQUEST_ID = 43
        const val TX_IN = "TX-IN"
        const val TX_OUT = "TX-OUT"
        const val SOC = 20.0
        const val SOH = 80.0
        const val ACCEPTED_TOKEN_INFO = """{"idTokenInfo":{"status":"Accepted"}}"""

        val TOKEN = IdToken("RFID-0001", "ISO14443")
        val AT: Instant = FixedClockConfig.FIXED_NOW
        val AT_TEXT: String = FixedClockConfig.FIXED_NOW_TEXT
    }
}
