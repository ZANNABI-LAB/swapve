package dev.swapve.ocpp.rpc

import java.util.concurrent.ThreadLocalRandom

/**
 * Issues the `requestId` of an S02 remotely initiated swap.
 *
 * The schema types `RequestBatterySwapRequest.requestId` as an **`integer`**, so there is no
 * room here for a wide, time-sortable value. What the id must avoid is being a local counter —
 * counters rewind on restart and collide across instances.
 *
 * What that leaves, stated plainly rather than implied:
 *
 * - **Not sortable.** Read `OcppEventRecord.seq` when order matters.
 * - **Not globally unique.** A 31-bit space starts colliding in the tens of thousands. It is
 *   safe here because the correlation key is the composite `(stationId, requestId)`, and a
 *   station has only a handful of swaps open at once — a swap finishes in minutes. The spec
 *   asks for no global uniqueness of `requestId`.
 *
 * Widening it is out of scope: it would take a change to the standard.
 */
object SwapRequestIds {

    /** A fresh positive `requestId`. Never `0` — that reads as "absent" rather than as a value. */
    fun newId(): Int = ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE)
}
