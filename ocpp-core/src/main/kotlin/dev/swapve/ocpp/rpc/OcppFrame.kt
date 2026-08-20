package dev.swapve.ocpp.rpc

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * OCPP-J RPC 프레임 (Part 4 §4.2).
 *
 * 스테이션 신원(`stationId`)은 **프레임에 없다.** WebSocket 핸드셰이크에서 정해지는
 * 연결의 속성이다 (Part 4 §4.2 NOTE). 그래서 여기서 다루지 않는다.
 */
sealed interface OcppFrame {

    /** Part 4 §4.1.4 — 최대 36자 */
    val messageId: String

    val type: MessageType

    /**
     * 요청. `[2, "<messageId>", "<action>", {<payload>}]`
     *
     * [action]은 `Request` 접미사를 **뗀** OCPP 메시지 이름이다 (Part 4 §4.1.6).
     * 예: `BatterySwapRequest` → `"BatterySwap"`
     */
    data class Call(
        override val messageId: String,
        val action: String,
        val payload: ObjectNode,
    ) : OcppFrame {
        override val type get() = MessageType.CALL
    }

    /**
     * 응답. `[3, "<messageId>", {<payload>}]`
     *
     * action 필드가 없다. 원래 CALL과는 [messageId]로만 대응된다 (Part 4 §4.1.6).
     */
    data class CallResult(
        override val messageId: String,
        val payload: ObjectNode,
    ) : OcppFrame {
        override val type get() = MessageType.CALL_RESULT
    }

    /**
     * 요청 처리 실패. `[4, "<messageId>", "<errorCode>", "<errorDescription>", {<errorDetails>}]`
     *
     * [errorCode]가 [RpcErrorCode] 열거형이 아니라 `String`인 이유:
     * 표에 없는 값이 와도 **버리지 않고 기록**하기 위해서다 (정보를 버리지 않는다).
     * 해석이 필요하면 [knownErrorCode]를 쓴다.
     */
    data class CallError(
        override val messageId: String,
        val errorCode: String,
        val errorDescription: String,
        val errorDetails: ObjectNode,
    ) : OcppFrame {
        override val type get() = MessageType.CALL_ERROR

        /** 표(Part 4 §4.3)에 있는 코드면 해당 값, 아니면 `null` */
        val knownErrorCode: RpcErrorCode? get() = RpcErrorCode.parse(errorCode)
    }

    /**
     * (2.1) 응답 처리 실패. CALLERROR와 구조가 같다. `[5, ...]`
     *
     * [messageId]는 문제가 된 **CALLRESULT의 messageId**와 같아야 한다 (Part 4 §4.1.4).
     */
    data class CallResultError(
        override val messageId: String,
        val errorCode: String,
        val errorDescription: String,
        val errorDetails: ObjectNode,
    ) : OcppFrame {
        override val type get() = MessageType.CALL_RESULT_ERROR

        val knownErrorCode: RpcErrorCode? get() = RpcErrorCode.parse(errorCode)
    }

    /**
     * (2.1) 응답을 기대하지 않는 단방향 메시지. `[6, "<messageId>", "<action>", {<payload>}]`
     *
     * CALL과 구조는 같지만 [action]에서 접미사를 떼지 않는다 — 애초에 `Request`/`Response`로
     * 끝나지 않는 메시지들이다 (Part 4 §4.2.4). 예: `"NotifyPeriodicEventStream"`
     *
     * 수신측은 SEND에 CALLRESULT/CALLERROR로 **응답해서는 안 된다(SHALL NOT).**
     */
    data class Send(
        override val messageId: String,
        val action: String,
        val payload: ObjectNode,
    ) : OcppFrame {
        override val type get() = MessageType.SEND
    }
}
