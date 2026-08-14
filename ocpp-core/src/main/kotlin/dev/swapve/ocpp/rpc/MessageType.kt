package dev.swapve.ocpp.rpc

/**
 * OCPP-J Message Type Number (Part 4 §4.1.3, Table 3).
 *
 * `CALLRESULTERROR`(5)와 `SEND`(6)는 OCPP 2.1에서 신설됐다.
 */
enum class MessageType(val number: Int) {
    /** 요청. `...Request` 로 끝나는 메시지 */
    CALL(2),

    /** 응답. `...Response` 로 끝나는 메시지 */
    CALL_RESULT(3),

    /** 요청 처리 실패 */
    CALL_ERROR(4),

    /** (2.1) 응답 처리 실패 */
    CALL_RESULT_ERROR(5),

    /** (2.1) 응답을 기대하지 않는 단방향 메시지 */
    SEND(6),
    ;

    companion object {
        private val byNumber = entries.associateBy(MessageType::number)

        /**
         * 표에 없는 번호는 `null`. 호출자는 **메시지 전체를 무시**해야 한다.
         *
         * Part 4 §4.1.3 + errata 2026-06 §4.1 — 원문은 "ignore the message payload"였으나
         * errata가 의도는 "메시지 전체 무시"라고 정정했다. 따라서 `MessageTypeNotSupported`
         * CALLERROR로 회신하지 않는다 (해당 코드는 errata 2026-06 §4.3에서 deprecated).
         */
        fun ofNumber(number: Int): MessageType? = byNumber[number]
    }
}
