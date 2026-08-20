package dev.swapve.ocpp.rpc

/**
 * OCPP-J Message Type Number (Part 4 §4.1.3, Table 3).
 *
 * `CALLRESULTERROR` (5) and `SEND` (6) are new in OCPP 2.1.
 */
enum class MessageType(val number: Int) {
    /** A request — a message whose name ends in `...Request`. */
    CALL(2),

    /** A response — a message whose name ends in `...Response`. */
    CALL_RESULT(3),

    /** The request could not be handled. */
    CALL_ERROR(4),

    /** (2.1) The response could not be handled. */
    CALL_RESULT_ERROR(5),

    /** (2.1) A one-way message that expects no response. */
    SEND(6),
    ;

    companion object {
        private val byNumber = entries.associateBy(MessageType::number)

        /**
         * `null` for a number outside the table. The caller must then **ignore the whole
         * message**.
         *
         * Part 4 §4.1.3 with errata 2026-06 §4.1 — the original text said "ignore the message
         * payload"; the errata corrected the intent to the entire message. So it is not
         * answered with a `MessageTypeNotSupported` CALLERROR, a code the same errata
         * deprecated in §4.3.
         */
        fun ofNumber(number: Int): MessageType? = byNumber[number]
    }
}
