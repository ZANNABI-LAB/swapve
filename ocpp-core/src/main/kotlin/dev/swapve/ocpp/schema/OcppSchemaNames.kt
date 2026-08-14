package dev.swapve.ocpp.schema

import dev.swapve.ocpp.rpc.MessageType

/**
 * OCPP action ↔ 공식 스키마 파일 이름 매핑 (Part 4 §4.1.6, §4.2.4).
 *
 * 파일 이름 규칙은 메시지 타입마다 다르다. **접미사를 붙이는 주체는 우리이고,
 * 프레임에 실려 오는 것은 접미사가 없는 action 이다.**
 *
 * | 타입 | 규칙 | 예 |
 * |---|---|---|
 * | `CALL`(2) | `action + "Request"` | `"BatterySwap"` → `BatterySwapRequest` |
 * | `CALLRESULT`(3) | `action + "Response"` | `"BatterySwap"` → `BatterySwapResponse` |
 * | `SEND`(6) | `action` 그대로 | `"NotifyPeriodicEventStream"` |
 * | `CALLERROR`(4) / `CALLRESULTERROR`(5) | 페이로드 스키마 없음 | — |
 *
 * `SEND` 메시지는 애초에 `Request`/`Response` 로 끝나지 않는 이름이라 접미사를 붙이지 않는다
 * (Part 4 §4.2.4).
 */
object OcppSchemaNames {

    /** `CALL` 페이로드가 따라야 할 스키마 이름 (Part 4 §4.1.6). */
    fun forCall(action: String): String = action + REQUEST_SUFFIX

    /**
     * `CALLRESULT` 페이로드가 따라야 할 스키마 이름 (Part 4 §4.1.6).
     *
     * **CALLRESULT 프레임에는 action 이 없다.** messageId 로만 원래 CALL 과 대응되므로
     * (Part 4 §4.1.6), 호출자가 pending CALL 테이블에서 찾아낸 [callAction] 을 넘겨야 한다.
     */
    fun forCallResult(callAction: String): String = callAction + RESPONSE_SUFFIX

    /** `SEND` 페이로드가 따라야 할 스키마 이름. 접미사가 붙지 않는다 (Part 4 §4.2.4). */
    fun forSend(action: String): String = action

    /**
     * 메시지 타입별 스키마 이름. 검증 대상이 아닌 타입(`CALLERROR`/`CALLRESULTERROR`)이면 `null`.
     *
     * [action] 은 `CALLRESULT` 의 경우 **원래 CALL 의 action** 이다.
     */
    fun forMessage(type: MessageType, action: String): String? = when (type) {
        MessageType.CALL -> forCall(action)
        MessageType.CALL_RESULT -> forCallResult(action)
        MessageType.SEND -> forSend(action)
        // 페이로드가 아니라 errorCode/errorDescription/errorDetails 구조다 (Part 4 §4.2.3).
        MessageType.CALL_ERROR, MessageType.CALL_RESULT_ERROR -> null
    }

    private const val REQUEST_SUFFIX = "Request"
    private const val RESPONSE_SUFFIX = "Response"
}
