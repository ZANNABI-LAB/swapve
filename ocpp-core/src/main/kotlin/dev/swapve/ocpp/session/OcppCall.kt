package dev.swapve.ocpp.session

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.swapve.ocpp.rpc.RpcErrorCode

/**
 * 보내려는(또는 받은) 요청 하나 — `(action, payload)`.
 *
 * **messageId 가 없다.** 발번은 전송 시점에 [dev.swapve.ocpp.rpc.MessageIds] 가 한다
 * (Part 4 §4.1.4). 호출자가 id 를 만들어 넘기게 두면
 * 재접속을 넘나드는 유일성 규칙을 호출자마다 다시 지켜야 한다.
 *
 * 재전송(타임아웃 후 다시 보내기)은 원본과 **같은 messageId 를 써도 된다(MAY)** 는 것이
 * 스펙이지만, 지금은 재전송 자체를 세션이 하지 않는다 — 타임아웃을 호출자에게 알리고 끝낸다.
 */
data class OcppCall(
    val action: String,
    val payload: ObjectNode,
) {
    init {
        require(action.isNotBlank()) { "action 은 비어 있을 수 없다 (Part 4 §4.1.6)" }
    }
}

/**
 * CALL 하나의 결말.
 *
 * **예외를 던지지 않는다.** 응답 실패도, 타임아웃도, 연결 없음도 전부 값이다 — 호출자가
 * `try/catch` 없이 `when` 하나로 전 경우를 다루게 하려는 것이고, 특히 "연결이 없다"는
 * 정상 운영 중 늘 일어나는 일이지 예외적 사건이 아니다.
 */
sealed interface OcppResult {

    /** CALLRESULT 를 받았고 공식 스키마도 통과했다. */
    data class Accepted(val messageId: String, val payload: ObjectNode) : OcppResult

    /** CALLERROR 를 받았다. 상대가 요청을 처리하지 못했다 (Part 4 §4.2.3). */
    data class Rejected(
        val messageId: String,
        val errorCode: String,
        val errorDescription: String,
        val errorDetails: ObjectNode,
    ) : OcppResult {
        /** 표(Part 4 §4.3)에 있는 코드면 해당 값, 아니면 `null`. 원문은 [errorCode] 에 남아 있다. */
        val knownErrorCode: RpcErrorCode? get() = RpcErrorCode.parse(errorCode)
    }

    /**
     * CALLRESULT 는 받았지만 공식 스키마를 통과하지 못했다.
     *
     * 세션이 상대에게 `CALLRESULTERROR`(타입 5) 로 알린 뒤 이 값을 돌려준다 (Part 4 §4.2.5).
     */
    data class InvalidResponse(
        val messageId: String,
        val errorCode: RpcErrorCode,
        val errorDescription: String,
    ) : OcppResult

    /**
     * 정해진 시간 안에 응답도 오류도 오지 않았다 (Part 4 §4.1.1).
     *
     * 이 시점부터 그 messageId 의 pending 은 정리된다. 뒤늦게 도착하는 CALLRESULT 는
     * 짝이 없는 응답으로 취급되어 기록만 남고 버려진다.
     */
    data class TimedOut(val messageId: String) : OcppResult

    /**
     * 그 스테이션의 연결이 없다.
     *
     * **예외가 아니라 결과다.** 스테이션은 언제든 끊긴다.
     */
    data class NotConnected(val stationId: String) : OcppResult
}

/**
 * 수신한 CALL 에 대해 상위 계층이 내놓는 답.
 *
 * 상위 계층은 프레임도 messageId 도 모른다. **무엇으로 답할지**만 정하고, 그걸 어떤 프레임에
 * 실어 언제 보낼지는 세션이 정한다.
 */
sealed interface InboundResponse {

    /** CALLRESULT 로 답한다. */
    data class Respond(val payload: ObjectNode) : InboundResponse {
        companion object {
            /** *"Empty response by CSMS to confirm receipt"* — `BatterySwapResponse` 같은 빈 응답. */
            fun empty(): Respond = Respond(JsonNodeFactory.instance.objectNode())
        }
    }

    /** CALLERROR 로 답한다 (Part 4 §4.2.3). */
    data class Fail(
        val errorCode: RpcErrorCode,
        val errorDescription: String,
        val errorDetails: ObjectNode = JsonNodeFactory.instance.objectNode(),
    ) : InboundResponse
}

/**
 * 수신한 CALL 을 처리한다.
 *
 * 이 함수는 **같은 스테이션에 대해 직렬로만 호출된다** ([StationSerializer]). 그러니 구현이
 * 스테이션 단위 락을 또 잡을 필요가 없다.
 *
 * 예외를 던지면 그 messageId 의 멱등 기록이 지워지고 상대에게
 * [RpcErrorCode.InternalError] 가 회신된다 — 즉 **재전송하면 다시 처리된다.**
 */
typealias OcppCallHandler = suspend (stationId: String, call: OcppCall) -> InboundResponse

/**
 * 수신한 SEND 를 처리한다 (Part 4 §4.2.4).
 *
 * 돌려줄 것이 없다. **SEND 에는 CALLRESULT/CALLERROR 로 응답해서는 안 된다(SHALL NOT).**
 */
typealias OcppSendHandler = suspend (stationId: String, send: OcppCall) -> Unit
