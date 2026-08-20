package dev.swapve.ocpp.rpc

import java.util.UUID

/**
 * Issues the `messageId` carried on CALL and SEND frames.
 *
 * Part 4 §4.1.4 — a messageId MUST differ from every value this sender has already used toward
 * the same station identifier, and **reconnecting does not reset that**. A local counter cannot
 * satisfy it: counters rewind on restart and collide across instances.
 *
 * A random UUID does, with 122 random bits, and its 36 characters are exactly the limit the
 * spec allows ([OcppFrameCodec.MAX_MESSAGE_ID_LENGTH]).
 */
object MessageIds {

    /**
     * A fresh messageId.
     *
     * **Carries no order.** Ids are for correlating a CALLRESULT back to its CALL, nothing
     * more — when sequence matters, read `OcppEventRecord.seq`.
     */
    fun newId(): String = UUID.randomUUID().toString()
}
