package dev.swapve.csms.devicemodel

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.ocpp.schema.OcppPayloadValidator
import dev.swapve.ocpp.schema.PayloadValidation
import dev.swapve.ocpp.session.OcppCall
import dev.swapve.ocpp.session.OcppResult
import dev.swapve.ocpp.session.StationCommandBus
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.ocpp.swap.VariableReading
import dev.swapve.ocpp.swap.VariableRef
import dev.swapve.ocpp.swap.VariableWrite
import dev.swapve.swap.StationId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 설정하려는 값 하나. `SetVariableDataType` 의 `attributeValue` 는 언제나 문자열이다. */
data class VariableAssignment(val ref: VariableRef, val value: String)

/**
 * `GetVariables` 한 번의 결말.
 *
 * **예외를 던지지 않는다.** 연결이 없는 것도 결과다 (`RemoteSwapStarter` 와 같은 태도).
 */
sealed interface DeviceModelQuery {

    /** 스테이션이 답했다. 항목별 상태는 [readings] 안에 있다 — 일부만 `UnknownVariable` 일 수 있다. */
    data class Answered(val readings: List<VariableReading>) : DeviceModelQuery {
        fun valueOf(ref: VariableRef): String? =
            readings.firstOrNull { it.ref.identity == ref.identity && it.isAccepted }?.value

        fun readingOf(ref: VariableRef): VariableReading? =
            readings.firstOrNull { it.ref.identity == ref.identity }
    }

    /** 그 스테이션의 연결이 없거나 응답이 오지 않았다. */
    data class Unreachable(val stationId: StationId, val result: OcppResult) : DeviceModelQuery
}

/** `SetVariables` 한 번의 결말. */
sealed interface DeviceModelUpdate {

    /**
     * 스테이션이 답했다.
     *
     * **`Accepted` 라는 보장이 아니다.** `MaxSoc < TargetSoC` 처럼 스테이션이 거부한 설정도
     * 여기로 온다 (S04.FR.06/10) — 거부는 오류가 아니라 답이다.
     */
    data class Answered(val results: List<VariableWrite>) : DeviceModelUpdate {
        val isAllAccepted: Boolean get() = results.isNotEmpty() && results.all { it.isAccepted }

        fun resultOf(ref: VariableRef): VariableWrite? =
            results.firstOrNull { it.ref.identity == ref.identity }
    }

    data class Unreachable(val stationId: StationId, val result: OcppResult) : DeviceModelUpdate
}

/**
 * 스테이션의 디바이스 모델을 조회·설정한다 (PLAN §4.9, M9).
 *
 * ### 왜 CSMS 에 이것이 필요한가
 *
 * **재고 판정은 스테이션이 한다** (PLAN §4.5, S02.FR.04). CSMS 는 몰라도 된다. 다만 알고
 * 싶을 때 표준이 정한 수단이 있고, 그 중 하나가 `BatteryCartridge` 의 `SoC` 를
 * `GetVariablesRequest` 로 묻는 것이다 (**S04.FR.12**). 여기가 그 수단이다.
 *
 * ### ★ `MaxSoc ≥ TargetSoC` 를 여기서 막지 않는다
 *
 * S04.FR.06/10 의 제약을 **CSMS 가 미리 검사하지 않는다.** 근거 셋은 `ocpp-core` 의
 * `DeviceModelVariables` KDoc 에 있다:
 *
 * 1. 표준이 판정의 자리를 `SetVariableResultType.attributeStatus` 하나로 정해 두었다.
 *    그 값을 채우는 쪽은 스테이션이다.
 * 2. 디바이스 모델의 소유자가 스테이션이다. CSMS 가 아는 `TargetSoC` 는 마지막으로 조회한
 *    값이고, 그 사이 다른 경로로 바뀌었을 수 있다. 낡은 값으로 미리 막으면 정상 설정이 막힌다.
 * 3. 앞에서 걸러 버리면 스테이션이 **실제로** 무엇을 받아들였는지가 기록에 남지 않는다.
 *
 * 그래서 이 클래스는 보내고, 답을 받고, 그대로 돌려준다. 거부를 감추지 않는다.
 *
 * ### 세션 객체를 만지지 않는다 (PLAN §11.5)
 *
 * 발신은 M4 의 [StationCommandBus] 로만 한다. 이 클래스는 `stationId` 만 알고, 그 스테이션의
 * 세션이 이 JVM 에 있는지조차 모른다 — `RemoteSwapStarter` 와 같은 규칙이다.
 *
 * ### REST 로 노출하지 않는다
 *
 * 소비자가 없다 (PLAN §11.0). 앱은 스테이션의 설정 변수를 만지지 않고, 운영 도구는 아직
 * 없다. 필요해지면 이 클래스를 부르는 얇은 컨트롤러 하나가 생길 자리다.
 */
@Component
class DeviceModelClient(
    private val commandBus: StationCommandBus,
    private val validator: OcppPayloadValidator,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 변수를 조회한다 (S04.FR.12).
     *
     * @param refs 조회할 변수들. `Timeout` 은 **인스턴스까지** 정해야 한다 — 그러지 않으면
     *   스테이션이 `UnknownVariable` 로 답한다 (PLAN §4.9 주의 1).
     */
    suspend fun get(stationId: StationId, refs: List<VariableRef>): DeviceModelQuery {
        require(refs.isNotEmpty()) { "조회할 변수가 없다 (스키마 minItems: 1)" }

        val payload = objectNode().apply {
            val array = putArray("getVariableData")
            refs.forEach { ref -> ref.writeTo(array.addObject()) }
        }

        val result = send(stationId, BatterySwapWire.GET_VARIABLES, payload)
        if (result !is OcppResult.Accepted) return DeviceModelQuery.Unreachable(stationId, result)

        val readings = result.payload.path("getVariableResult").mapNotNull { VariableReading.read(it) }
        return DeviceModelQuery.Answered(readings)
    }

    /** 변수 하나를 조회하는 지름길. */
    suspend fun get(stationId: StationId, ref: VariableRef): DeviceModelQuery = get(stationId, listOf(ref))

    /**
     * 변수를 설정한다 (S04.FR.05/06/10).
     *
     * 여러 항목을 한 번에 보내면 스테이션이 **항목별로** 판정한다. `TargetSoC` 와 `MaxSoc` 를
     * 함께 올릴 때 그 순서까지 우리가 정할 수는 없으므로, 순서가 중요하면 호출을 나눈다 —
     * 우리가 스테이션의 적용 순서를 짐작해 페이로드를 꾸미는 것이야말로 틀리기 쉬운 추측이다.
     */
    suspend fun set(stationId: StationId, assignments: List<VariableAssignment>): DeviceModelUpdate {
        require(assignments.isNotEmpty()) { "설정할 변수가 없다 (스키마 minItems: 1)" }

        val payload = objectNode().apply {
            val array = putArray("setVariableData")
            assignments.forEach { assignment ->
                array.addObject().apply {
                    put("attributeValue", assignment.value)
                    assignment.ref.writeTo(this)
                }
            }
        }

        val result = send(stationId, BatterySwapWire.SET_VARIABLES, payload)
        if (result !is OcppResult.Accepted) return DeviceModelUpdate.Unreachable(stationId, result)

        val results = result.payload.path("setVariableResult").mapNotNull { VariableWrite.read(it) }
        results.filterNot { it.isAccepted }.forEach { rejected ->
            // 거부는 오류가 아니라 스테이션의 답이다. 삼키지 않고 남긴다.
            log.info(
                "스테이션이 변수 설정을 받아들이지 않았다: station={} variable={} status={} reason={} {}",
                stationId, rejected.ref, rejected.status.wireValue,
                rejected.reasonCode, rejected.additionalInfo.orEmpty(),
            )
        }
        return DeviceModelUpdate.Answered(results)
    }

    /** 변수 하나를 설정하는 지름길. */
    suspend fun set(stationId: StationId, ref: VariableRef, value: String): DeviceModelUpdate =
        set(stationId, listOf(VariableAssignment(ref, value)))

    /**
     * 보내기 전에 **우리가 만든 요청을 공식 스키마로 자기 검증**한다 (PLAN §6 설계원칙 2).
     *
     * 손으로 필드를 짐작하다 틀리면 스테이션이 아니라 여기서 터진다.
     */
    private suspend fun send(stationId: StationId, action: String, payload: ObjectNode): OcppResult {
        val validation = validator.validateCall(action, payload)
        if (validation is PayloadValidation.Invalid) {
            error("우리가 만든 ${action}Request 가 공식 스키마를 통과하지 못했다: ${validation.errorDescription}")
        }

        val result = commandBus.send(stationId.value, OcppCall(action, payload))
        if (result !is OcppResult.Accepted) {
            log.warn("{} 를 보내지 못했다: station={} result={}", action, stationId, result)
        }
        return result
    }

    private fun objectNode(): ObjectNode = JsonNodeFactory.instance.objectNode()
}
