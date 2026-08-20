package dev.swapve.csms.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.swapve.csms.event.JdbcOcppEventLog
import dev.swapve.csms.support.FixedClockConfig
import dev.swapve.ocpp.rpc.MessageType
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.schema.PayloadValidation
import dev.swapve.ocpp.session.OcppEventRecord
import dev.swapve.station.StationSimulator
import dev.swapve.station.SwapOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import kotlin.test.assertTrue

/**
 * ★ **S1 의 나머지 절반** — 오간 **모든 메시지가 공식 스키마 검증을 통과한다**.
 *
 * 양쪽 이벤트 로그의 원문을 전부 다시 꺼내 공식 스키마에 건다. 검사 대상이 우리가 만든
 * 객체가 아니라 **실제로 소켓을 지난 바이트**라는 것이 요점이다.
 *
 * 세션 계층도 같은 검증을 이미 하지만 (M2·M4), 그것은 "받은 것"에 대한 검사다. 여기서는
 * **보낸 것까지 포함해 양방향 전량**을 확인한다 — 한쪽이 조용히 헐거운 페이로드를 내보내고
 * 상대가 그것을 관대하게 받아 주는 상황이 남지 않게.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig::class)
class SchemaCrossCheckTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var csmsEventLog: JdbcOcppEventLog

    @Autowired
    private lateinit var validator: OcppPayloadValidator

    private val mapper = ObjectMapper()

    @ParameterizedTest(name = "{0} — 오간 모든 메시지가 공식 스키마를 통과한다")
    @EnumSource(SwapOrder::class)
    fun `양방향 전 메시지가 공식 스키마를 통과한다`(order: SwapOrder) {
        val stationId = uniqueStationId("CS-SCHEMA-${order.name}")
        val simulator = runScenario(order, stationId, 4301 + order.ordinal)

        val stationSide = simulator.eventLog.of(stationId)
        val csmsSide = csmsEventLog.of(stationId)

        assertTrue(stationSide.isNotEmpty(), "스테이션 쪽 기록이 없다")
        assertTrue(csmsSide.isNotEmpty(), "CSMS 쪽 기록이 없다")

        // 같은 대화를 양쪽이 각자 기록했으므로 건수가 같아야 한다. 어긋나면 유실이 있었다는 뜻이다.
        assertTrue(
            stationSide.size == csmsSide.size,
            "오간 메시지 수가 다르다: 스테이션 ${stationSide.size} 건, CSMS ${csmsSide.size} 건",
        )

        val checked = (stationSide + csmsSide).sumOf { record -> validate(record) }
        assertTrue(checked > 0, "검증한 메시지가 없다")
    }

    @ParameterizedTest(name = "{0} — 오류 프레임이 하나도 오가지 않는다")
    @EnumSource(SwapOrder::class)
    fun `CALLERROR 가 오가지 않는다`(order: SwapOrder) {
        val stationId = uniqueStationId("CS-SCHEMA-CLEAN-${order.name}")
        val simulator = runScenario(order, stationId, 4311 + order.ordinal)

        val frames = (simulator.eventLog.of(stationId) + csmsEventLog.of(stationId)).map { typeOf(it) }
        assertTrue(
            frames.none { it == MessageType.CALL_ERROR || it == MessageType.CALL_RESULT_ERROR },
            "오류 프레임이 있다: $frames",
        )
    }

    /**
     * 기록 한 건을 검증한다.
     *
     * @return 실제로 스키마에 건 건수. `CALLERROR`/`CALLRESULTERROR` 는 페이로드 스키마가
     *   없으므로 0 이다 (Part 4 §4.2.3).
     */
    private fun validate(record: OcppEventRecord): Int {
        val frame = mapper.readTree(record.payload)
        return when (typeOf(record)) {
            MessageType.CALL -> {
                assertValid(record, validator.validateCall(frame.action(), frame.get(3)))
                1
            }

            MessageType.SEND -> {
                assertValid(record, validator.validateSend(frame.action(), frame.get(3)))
                1
            }

            MessageType.CALL_RESULT -> {
                // CALLRESULT 에는 action 이 없다. 짝이 되는 CALL 의 action 을 세션이 기록해 두었다
                // (Part 4 §4.1.6, M4 `OcppSession.actionOf`).
                val action = requireNotNull(record.action) { "CALLRESULT 의 짝을 찾지 못했다: ${record.payload}" }
                assertValid(record, validator.validateCallResult(action, frame.get(2)))
                1
            }

            // 페이로드 스키마가 없는 프레임이다.
            MessageType.CALL_ERROR, MessageType.CALL_RESULT_ERROR, null -> 0
        }
    }

    private fun assertValid(record: OcppEventRecord, validation: PayloadValidation) {
        assertTrue(
            validation !is PayloadValidation.Invalid,
            "${record.direction} ${record.action} 이 공식 스키마를 통과하지 못했다: " +
                "${(validation as? PayloadValidation.Invalid)?.errorDescription}\n원문: ${record.payload}",
        )
    }

    private fun typeOf(record: OcppEventRecord): MessageType? =
        MessageType.ofNumber(mapper.readTree(record.payload).get(0).asInt())

    private fun JsonNode.action(): String = get(2).asText()

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

    private fun uniqueStationId(prefix: String): String = "$prefix-${System.nanoTime()}"
}
