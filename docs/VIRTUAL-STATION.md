# The virtual station — what each simulator call actually does

> The question this document answers: **which of the simulator's calls are real, and which are
> convenience.**
>
> `StationSimulator` is not a script player. `runSwap()` has no behaviour of its own — it calls
> `authorize()`, `insertBatteries()`, `reportChargingStarted()`, `removeBatteries()` in an order
> chosen by `SwapOrder`, and nothing else. Every one of those is separately callable, and calling
> them in a different order, or leaving one out, is a supported thing to do. That is the difference
> between a station you can drive and a recording you can replay.

---

## 1. Three layers of operation

The public surface splits into three kinds of call. The split is not enforced by types — it is a
statement about what a call *is*, and it is what tells you whether an operation is worth reaching
for individually.

**Acts** — physical events. An act changes a fact inside the station (a slot gains or loses a
battery, a battery's SoC rises, the connection opens or closes) and then announces the consequence
on the wire. The announcement follows the change; it does not substitute for it. An act does **not**
check that the surrounding scenario makes sense — see [§3](#3-there-is-no-ordering-gate-and-that-is-deliberate).

**Reports** — protocol statements. A report tells the CSMS something and changes no fact inside the
station. `authorize()` sends `AuthorizeRequest` and no slot moves. `reportBatteryOutTimeout()`
reports that a dispensed battery was never collected and deliberately leaves the slot occupied and
the charging transaction open — *the battery is still inside the station*, and the whole point of
S03.FR.06 is that the CSMS is left holding an orphan `BatteryIn`. A report does **not** make the
thing it reports true.

**Scripts** — ordered bundles of the first two. A script has no behaviour that isn't in the calls it
makes. It exists so the common path is one line. A script does **not** give you anything you cannot
assemble yourself, and it is never the only way to reach a state.

Why you need the distinction: when you want to test a CSMS against something the happy path doesn't
produce, you need to know **which call is the smallest unit that moves the world** and which call is
merely a macro over four other calls. Reaching for an act gets you a real, isolated event. Reaching
for a script gets you the whole sequence or nothing.

A fourth group, **observations**, is read-only and covered in [§4](#4-observation-is-derived-from-the-event-log).

## 2. Where every operation sits

All 24 public functions and 4 public properties of `StationSimulator`, with nothing left out.

### Acts — change a fact, then announce it

| Operation | What changes inside the station | What goes on the wire |
|---|---|---|
| `connect()` | transport opens | WebSocket handshake to `{csmsUrl}/{stationId}`, `ocpp2.1` negotiated |
| `disconnect()` | transport drops; session, slots, and `requestId` survive | nothing — the socket just closes |
| `reconnect()` | transport reopens on the same session and slots | handshake only |
| `close()` | session closes and transport drops; the simulator is finished | nothing |
| `boot()` | a charging transaction opens on every occupied slot | `BootNotification` → `NotifyEvent` per slot → `SecurityEventNotification(StartupOfTheDevice)` → `TransactionEvent(Started)` per occupied slot |
| `reboot()` | connection drops; every slot's transaction id, `seqNo`, and suspension are cleared — **batteries stay put** | disconnect, reconnect, then the boot sequence with `LocalReset` and `ResetOrReboot`, opening **new** transaction ids (S04.FR.11) |
| `insertBatteries()` | `config.insertSlots` gain `config.incomingBatteries`; a transaction opens per slot | `NotifyEvent(Occupied)` + `TransactionEvent(Started)` per slot, then one `BatterySwap(BatteryIn)` carrying the whole set |
| `removeBatteries()` | `config.dispenseSlots` lose their batteries; their transactions close | `TransactionEvent(Ended)` + `NotifyEvent(Available)` per slot, then one `BatterySwap(BatteryOut)` — note the order is the reverse of insertion, per `TC_S_103_CSMS` |
| `advanceCharging(slotId, byPercent)` | the battery's SoC rises by `byPercent`, capped at `BatterySwapCtrlr.MaxSoc`; at the cap the slot becomes suspended | `TransactionEvent(Updated, MeterValuePeriodic)` with measurand `SoC`; on reaching the cap, one more with `EnergyLimitReached` / `SuspendedEVSE`. The transaction stays **open** |

### Reports — say something, change nothing

| Operation | What changes inside the station | What goes on the wire |
|---|---|---|
| `authorize()` | nothing | `Authorize`; raises if the CSMS answers anything but `Accepted` |
| `reportChargingStarted()` | nothing but the transaction `seqNo` | `TransactionEvent(Updated, ChargingStateChanged, Charging)` per inserted slot |
| `reportBatteryOutTimeout()` | **nothing** — the uncollected batteries stay in their slots with their transactions open | one `BatterySwap(BatteryOutTimeout)` carrying the uncollected batteries, so the CSMS knows *which* ones are orphaned (F2, S03.FR.06) |
| `resendLastBatterySwap(sameMessageId)` | nothing | the last outbound `BatterySwap` frame again. `true` re-sends the identical frame straight down the transport (F6, tests the CSMS's idempotency ledger); `false` re-sends the payload under a fresh messageId (F4, tests the CSMS's duplicate-`BatteryIn` handling) |
| `reportFullInventory()` | nothing | waits for an accepted `GetBaseReport`, then `NotifyReport` pages of 3 with rising `seqNo` and `tbc` on all but the last (B03) |

### Scripts — ordered bundles, no behaviour of their own

| Operation | Expands to |
|---|---|
| `runSwap()` | `authorize()`, then `insertBatteries()` → `reportChargingStarted()` → `removeBatteries()` (`IN_OUT`) or `removeBatteries()` → `insertBatteries()` → `reportChargingStarted()` (`OUT_IN`) |
| `bootAndSwap()` | `boot()` then `runSwap()` |
| `runRemoteSwap()` | `awaitRemoteStart()`, then insert/remove in `SwapOrder` order. **No `authorize()`** — under S02 the CSMS already authorized and the station only answered `Accepted` |
| `chargeUntilMaxSoc(slotId, stepPercent)` | `advanceCharging(slotId, stepPercent)` in a loop until the slot suspends; returns every SoC it reported |

### Observations — read-only

| Operation | Reads |
|---|---|
| `config` | the `StationSimConfig` this simulator was built from |
| `eventLog` | every frame sent and received, verbatim ([§4](#4-observation-is-derived-from-the-event-log)) |
| `isConnected` | whether the transport is open; `false` rather than throwing when there is none |
| `subprotocol` | the negotiated subprotocol, expected to be `ocpp2.1`. **Throws if not connected** — unlike `isConnected`, this asks the transport |
| `slotState(slotId)` | `EMPTY` or `HOLDS_BATTERY`, in domain vocabulary — `Available`/`Occupied` never leaves the wire boundary |
| `batteryAt(slotId)` | the battery in the slot, or `null` |
| `chargingTransactionAt(slotId)` | the slot's charging transaction id, or `null`. Unrelated to the swap's `requestId` |
| `isChargingSuspended(slotId)` | whether the slot hit `MaxSoc` and stopped. Says nothing about the transaction, which is still open |
| `repliesTo(messageId)` | how many inbound frames carry that messageId — how a resend is confirmed to have been answered |
| `awaitRemoteStart()` | suspends until an inbound `RequestBatterySwap` is **accepted**, then returns its `requestId`. Sends nothing; a rejected request does not wake it |

## 3. There is no ordering gate, and that is deliberate

Every runtime check in `StationSimulator` inspects a **physical fact about the station**, never the
order in which you called things. The complete list:

| Check | What it asks |
|---|---|
| `connect()`, `reconnect()`: `transport == null` | is there already a connection |
| `insertBatteries()`: `slot.battery == null` | is the slot empty |
| `advanceCharging()`, `removeBatteries()`: `checkNotNull(slot.battery)` | is there a battery to charge or dispense |
| `reportBatteryOutTimeout()`: `pending.isNotEmpty()` | is there actually an uncollected battery |
| `requireTransaction(slot)`: `checkNotNull(slot.transactionId)` | is a charging transaction running on this slot |
| `reportFullInventory()`: `pages.isNotEmpty()` | is there anything to report |
| `slotOf()`, `connectedTransport()` | does the slot exist; is there a connection |

Two more checks compare a **status the CSMS sent back** — `Accepted` from `BootNotification`, and
`Accepted` from `Authorize`. Those read the other party's answer. Neither is a gate on our own call
sequence.

So nothing stops you from calling `insertBatteries()` without ever calling `authorize()`. **That is
F5.** The whole of the F5 driver in `sim-console` is one line:

```kotlin
// 인가를 건너뛴다. authorize() 를 부르지 않는 것이 이 시나리오의 전부다.
FaultScenario.F5 -> simulator.insertBatteries()
```

The station does not object, because a real station with a stuck relay or a bad firmware build
would not object either. Refusing to send it would mean the CSMS can never be tested against it.

What the CSMS does with it is the actual test: it **answers normally and records the anomaly**.
`BatterySwapResponse` has no field for refusing, so the response goes back clean and an
`AnomalyReason.NOT_AUTHORIZED` is written alongside it; the log replay likewise declines to open a
swap that arrived without authorization. `FailureScenarioTest` asserts both halves — that the
anomaly is recorded, *and* that the `BatterySwapResponse` came back with no CALLERROR frames
anywhere in the exchange.

The same reasoning covers the other configuration-driven scenarios: F1 (no battery available) and F3
(unregistered battery) are reproduced by *how the station is configured*, not by a switch in the
simulator. The injection hook, `FaultInjection.failingAt`, exists for the one thing configuration
cannot express — a sequence that dies halfway through, which is F6.

## 4. Observation is derived from the event log

There are no status flags added for the benefit of tests. Everything a test wants to know about what
happened on the wire is derived from `eventLog`, which holds every frame in both directions
verbatim.

`repliesTo(messageId)` is the smallest example: it counts inbound frames carrying a given messageId.
`resendLastBatterySwap(sameMessageId = true)` bypasses `OcppSession` — the session always mints a
fresh messageId, so a true resend cannot go through it — which means there is no pending
continuation to await. Rather than return the moment the bytes leave (and let a test pass even if
the resend never landed), it polls `repliesTo()` until the count rises.

Tests read the log the same way. The F5 test confirms the response came back by filtering the log
for inbound `BatterySwap` frames; the F3 test finds its rejection by reading the `customData` of the
response payload it recovers from the log. Nothing is asserted against a flag that the simulator set
about itself.

## 5. Limits

**It has never been connected to physical hardware or to a third-party CSMS.** Every run in this
repository is this simulator against this CSMS. That is why `openTransport` is an injection point at
all: the JDK has no server-side WebSocket, so a fake CSMS cannot be stood up, and without that seam
the simulator would be testable *only* through the real CSMS — where a red test cannot tell you
which side is wrong.

**Four inbound actions are answered**, and only four: `RequestBatterySwap`, `GetVariables`,
`SetVariables`, `GetBaseReport`. Everything else returns `NotImplemented` per Part 4 §4.3, rather
than pretending.

**`GetBaseReport` accepts `FullInventory` only.** `ConfigurationInventory` and `SummaryInventory` are
different lists — configurable-only and summary-only. Answering all three with the same full list
would not be supporting three report bases; it would be answering two of them wrongly.

**Charging is not modelled.** `advanceCharging(slotId, byPercent)` raises SoC by exactly the step the
caller passed, capped at `MaxSoc`. There is no current, no taper, no temperature, no time-to-full. A
battery inserted above the cap is not pulled down to it — charging does not lower SoC, and rewriting
an observation would be a lie — it simply suspends immediately.

**There is no real time.** Every timestamp comes from the injected `Clock` and every step waits for
the peer's CALLRESULT. There is no `sleep` anywhere, so "ten minutes later" is produced by moving the
clock, and runs are deterministic.

---

> This document is about **simulator operations** — what a call does to the station and what it puts
> on the wire. For **module boundaries** — how much of `ocpp-core` you can take, and what you take on
> when you do — see [LAYERS.md](LAYERS.md).
