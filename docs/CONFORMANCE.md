# Verification — gates, conformance, success criteria

> The *"Verification"* section of the README is the summary; this document is the whole of it.
> Why the gates are split at all is explained in the root `build.gradle.kts`.

## The invariant audit prints how many items it examined

`auditTest` does not just print pass/fail. **A bare "passed" is indistinguishable from a check
that never ran.**

```
──────────────────────────────────────────────────────────────────────
 불변식 감사 — 성공 기준 S4
 스테이션 20대 · 교환 60건 · 메시지 2520건
──────────────────────────────────────────────────────────────────────
 항목                                 근거                검사  판정
 배터리 수량 보존                          불변식           60건 교환  ✅ 통과
 슬롯 이중 예약 0                         불변식        240개 슬롯점유  ✅ 통과
 유실 메시지 0                           불변식       1260건 CALL  ✅ 통과
 (stationId, requestId) 유일          불변식          60개 상관키  ✅ 통과
 교환/충전 분리                           생명주기 분리      240개 슬롯  ✅ 통과
 이벤트 로그 순서                          이벤트 로그      20대 스테이션  ✅ 통과
 로그 재구성 ↔ 레지스트리                     이벤트 로그       540개 대조  ✅ 통과
 공식 스키마 위반 0                        스키마 정본     2520건 메시지  ✅ 통과
 CALLERROR 0                        적합성        2520건 프레임  ✅ 통과
──────────────────────────────────────────────────────────────────────
```

<sub>The audit output is currently Korean. Reading down the rows: *invariant audit — success
criterion S4; 20 stations · 60 swaps · 2,520 messages.* Columns are **item · basis · examined ·
verdict**. The items are: battery count conserved · zero double-booked slots · zero lost messages ·
`(stationId, requestId)` unique · swap and charging kept separate · event-log ordering · state
rebuilt from the log matches the registries · zero official-schema violations · zero CALLERRORs.
Log message localization is still open, together with the docs.</sub>

> **The audit is itself tested.** `InvariantAuditTest` feeds in logs with one invariant
> deliberately broken at a time and requires the corresponding row to turn red (it runs in L1).
> Without that, "all items passed" is a claim; with it, it is a verdict.

## Success criteria S1–S7 — where and how each is verified

The project's success criteria, restated as **commands you can run**.

| # | Criterion | Command | Tests |
|---|---|---|---|
| **S1** | One complete S03 swap, every message passing the official schemas | `./gradlew build` | `SwapEndToEndTest` · `SchemaCrossCheckTest` · `ProtocolContractTest` |
| **S2** | ★ Official conformance `TC_S_102_CSMS` · `TC_S_103_CSMS` · `TC_S_104_CS` | `./gradlew conformanceTest` | `TcS102CsmsTest` · `TcS103CsmsTest` · `TcS104CsTest` |
| **S3** | Failure scenarios F1–F6 | `./gradlew conformanceTest` | `FailureScenarioTest` |
| **S4** | 20 stations concurrently, then every invariant-audit row | `./gradlew auditTest` | `LoadAuditTest` (plus `InvariantAuditTest`, which tests the audit) |
| **S5** | Success rate, duration, and failure reasons queryable over REST | `./gradlew build` | `SwapMetricsApiTest` · `SwapApiTest` · `ChargingApiTest` |
| **S6** | All of the above verified automatically by the gates | [README — how this is built](../README.md) | [`.zannabi.json`](../.zannabi.json) defines the gates · [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs them |
| **S7** | Running it inside five minutes with only the README | [README — quick start](../README.md) | Measured — build → boot → one swap completed. The control console's routes are checked on every build by `SimConsoleControlTest` |

## Conformance cases

OCPP 2.1 Part 6 defines Battery Swap test cases **whose system under test is the CSMS**. Those are
this project's bar for passing.

### Battery Swap cases (Part 6, pp. 1366–1369)

| Case | What it covers | Status |
|---|---|---|
| `TC_S_102_CSMS` | Remote start — no battery available (`Rejected` / `NoBatteryAvailable`) | ✅ `TcS102CsmsTest` |
| `TC_S_103_CSMS` | Remote start — the full swap sequence (a two-battery set) | ✅ `TcS103CsmsTest` |
| `TC_S_104_CS` | Full device-model inventory report (`GetBaseReport(FullInventory)`) | ✅ `TcS104CsTest` |

> `TC_S_104_CS` **reverses the system under test** relative to the other two — the simulator is
> tested in the CS role, and the CSMS acts as the Test System: it asks for
> `GetBaseReport(FullInventory)` and reassembles the `NotifyReport` messages, which arrive split,
> by `requestId`.
>
> The cases whose system under test is the CS (pp. 948–954) are used instead as **the simulator's
> specification** — it plays the five reusable states verbatim:
> `BootedBatterySwapping` · `AuthorizedBatterySwapping` · `EVConnectedPreSessionBatterySwapping` ·
> `EnergyTransferStartedBatterySwapping` · `EVDisconnectedBatterySwapping`.
>
> **Official OCTT certification has not been obtained** — it is paid and must go through an
> OCA-approved test lab. What is here is an **independent implementation** of the Part 6 cases.

### The OCPP-J transport layer (Part 4 Edition 2 §3)

| Item | Requirement | Status |
|---|---|---|
| §3.1.1 Connection URL — identifier at most 48 chars, no colon, percent-decoded | SHALL | ✅ rejected at the handshake |
| §3.1.1 Do not rely on the URL alone for identity | RECOMMENDED | ✅ Basic username cross-checked against the path stationId |
| §3.1.2 Version negotiated via `Sec-WebSocket-Protocol` | SHALL | ✅ connection refused if `ocpp2.1` is not offered |
| §3.3 Echo the single chosen protocol in the 101 response | SHALL | ✅ |
| §3.4 RFC 7692 compression (`permessage-deflate`) | **SHALL** | ✅ negotiated |

**A note on §3.4 compression.** It is a conformance item, so it was observed rather than assumed.
`WebSocketHandshakeTest` reads the `Sec-WebSocket-Extensions` header of the 101 response directly,
and `permessage-deflate;client_max_window_bits=15` really does come back. Extension negotiation is
owned by the servlet container (embedded Tomcat), and Spring passes the client's request through
untouched, so **there is no place in application code to switch it on or off.** If it is ever
observed to be off, the thing to change is the embedded container or the reverse proxy in front —
and this test would go red first.

**A note on the §3.1.1 cross-check.** The specification recommends not identifying a station by the
connection URL alone. The default implementation compares the Basic username against the path
stationId, and a successful registration records `StationPrincipal.authMethod = BASIC`. The
local-experiment `NONE` profile records `authMethod = NONE` on the same type (for how to configure
it see [CONFIGURATION.md](CONFIGURATION.md)). Certificate issuance, CSRs, and key stores are out of
scope.

## Limits of self-verification

Everything above is this repository checking itself. The CSMS and the simulator are built on the
**same `ocpp-core`**, so a defect in core shows up symmetrically on both sides and the end-to-end
tests stay green. `EnergyLimitReached` was once guessed as `EVCommunicationLost`, and every test
passed, because both ends read the same wrong constant. That is the failure mode this section is
about, and it is worth being precise about which layers have an outside referee and which only have
a stand-in.

| Layer | External referee | What stands in for one |
|---|---|---|
| Payload ↔ schema | ✅ yes | The 181 official JSON schemas under `schemas/`. `SchemaCrossCheckTest` validates every message in both directions; `WireContractTest` looks our own constants up in the schema `enum`s |
| OCPP-J framing · correlation | ⚠️ **partly, three times** | Three third-party implementations have accepted our CALL, CALLRESULT and CALLERROR frames and correlated them by `messageId` (see below). Between those runs, `OcppFrameCodecTest` decodes the Part 4 §4.2.1–4.2.4 example frames verbatim and `ProtocolContractTest` asserts against string literals rather than production constants |
| Timeouts · reconnection | ⚠️ **partly, once** | A third-party JVM CSMS was stood up locally and driven through both a stalled reply and a mid-swap disconnect (see below). Between those runs, `OcppSessionTimeoutTest` pins Part 4 §4.1.1 behaviour, but the reading of the specification is ours and is shared by both ends |

**What the literal assertions buy — and what they do not.** `ProtocolContractTest` and every
conformance case in this document write their expectations as string literals in the test file
instead of referencing `BatterySwapWire` or `BatteryRejectionReason`, because comparing a constant
to itself is not a comparison. This does **not** create an external referee — the literals were
transcribed by us too. What it buys is exactly one thing: the expected value and the production
constant now live in two separate places, so quietly changing the constant turns a test red.

**This was measured, not assumed.** The claim above was once false where it mattered most: an
assertion annotated as guarding a specific trap was comparing the trap's own constant to itself, and
reverting the constant left all six gates green. So the conformance suite was checked by mutation.
Thirteen mutations were applied one at a time, each replacing a constant with **another value the
standard also permits** — `Accepted`↔`Rejected`, `BatteryIn`↔`BatteryOut`, `Started`↔`Ended`,
`EVConnected`→`Charging`, `FullInventory`→`ConfigurationInventory`, `NoAuthorization`→`Central`,
and so on. Every one of the thirteen turned a conformance test red, and the schema validation stayed
green throughout — which is the point, since a permitted-but-wrong value is precisely what a schema
cannot see. Six of the mutated fields (`idToken.type`, `statusInfo.reasonCode` and the `customData`
vendor identifier among them) are not constrained by any schema at all, so the literal assertion is
the **only** check on them.

**Where this is not done yet.** Twelve assertions in the `station-sim` test suite still compare a
wire value against the constant that produced it. They are listed as outstanding rather than fixed
here, because a limits section that reports only the holes already closed is not a limits section.

**Where the literals come from.** The specification PDFs are not in this repository — no tracked
file is one. The literals were transcribed from Part 6 Tool validation into the comments that sit
next to them, and cross-checked against the schema `enum`s where a schema has one. Four fields have
no `enum` to check against: `component.name`, `variable.name`, `SecurityEventNotification.type`, and
`idToken.type` are free-form strings in the schemas. For three of them the comments are no longer the
only evidence — a third-party CSMS read our `Connector`, `AvailabilityState` and `ISO14443` back out
and acted on them (below). `SecurityEventNotification.type` is still comment-only: one peer did not
implement the message at all and the other accepted it without saying what it made of the value. Note also what `WireContractTest` cannot see even where an `enum` exists:
it catches a value the standard does not allow, but not **the wrong choice among allowed values** —
that requires reading the specification text.

**What the interoperability runs showed, and where they stopped.** The simulator has been pointed
at two third-party CSMS implementations by hand: a small OCPP 2.0.1 example server, and the current
image of a certified implementation, which negotiated **`ocpp2.1`** with us. Between them they
accepted the handshake and subprotocol negotiation, `BootNotification`, `NotifyEvent`, `Authorize`,
`TransactionEvent` and `SecurityEventNotification`, correlated every reply by `messageId`, and read
our CALLERROR frames for actions the simulator does not implement. Our own `Connector`,
`AvailabilityState` and `ISO14443` values came back out of their logs, applied. That is the first
evidence for those strings that is not a comment we wrote.

Three things were attempted in those runs and did not complete, and the reason differs in each
case. **All three were later opened against a third peer** — see the section after this one.

- **Block S was delivered and refused.** A `BatterySwap(BatteryOut)` frame reached the certified
  peer, which answered `FormatViolation` with the log line *"No schema found for action
  BatterySwap"*. Its 2.1 support does not include Battery Swap.
- **No swap ran end to end.** `TransactionEvent` passed that peer's schema validation and then
  failed inside its own database on a unique constraint, which it returned as an OCPP
  `InternalError`. The individual messages were confirmed; a whole swap, start to finish, was not.
- **Authorization was never granted.** `Authorize` arrived but the token was not provisioned on the
  peer, so the answer was `Unknown`. The path where a swap opens on an accepted token was not seen.

**Timeouts and reconnection were not exercised at all** in either of those two runs.

**A third peer answered Block S.** The two runs above were against deployed CSMS images. A third
referee was found in a different form: an MIT-licensed JVM OCPP library whose 2.1 tree carries a
**server-side Battery Swapping function with an actual request handler**, not just message types
(the Java row in the comparison table in [the README](../README.md#why-block-s)).
Its published artifacts do not include that module and its build does not run on a current JDK, so
its sources were compiled directly into a small local CSMS and the simulator was pointed at it. That
peer is not certified and is not a product; what it is, is a Block S implementation written by
someone other than us. Four runs were performed:

| Run | Result |
|---|---|
| Station-initiated swap (S01) | Completed. The peer's own handler received `BatterySwap(BatteryIn)` and `BatterySwap(BatteryOut)`, 42 messages end to end |
| CSMS-initiated swap (S02) | The peer sent `RequestBatterySwap`; the simulator answered `Accepted` and **adopted the peer's `requestId`** for the whole swap (S02.FR.02), 38 messages |
| Stalled reply | The peer held a `BatterySwap` reply past our 30-second CALL timeout. The timeout fired and reported the `messageId` it was waiting on |
| Disconnect mid-swap, reconnect, retransmit (F6) | The link dropped before `BatteryOut`; after reconnecting, the same frame was retransmitted **under its original `messageId`** and was answered |

This closes the three entries above. A swap now has run start to finish against an implementation
that is not ours, on an accepted token, with Block S handled rather than refused, and the timeout
and reconnection layer has been exercised from outside for the first time.

**What that peer did with the retransmission is itself a finding.** Its application handler ran
**twice** for the same `(stationId, messageId)`. Part 4 §4.1.4 exists because a station that does not
see a reply retransmits, and the receiver is expected to recognise the repeat rather than book it
again. `InboundCallLedger` is this repository's answer to that, and F6 is the test that pins it; the
peer has no equivalent. This is reported here as an observation about the referee, not as a defect
claim we have raised with anyone.

**Where the cause lies and what is verified are different questions.** Most of what blocked these
runs sits on the other side — a missing schema, a database constraint, an unprovisioned token. One
peer also rejected an `idToken` value that the official schema permits, enforcing a format rule the
specification does not impose, and enforcing it inconsistently across its own message handlers. None
of that is a defect here. **It does not make the affected areas verified.** A reader deciding how
far to trust this repository needs the second answer, not the first, so the entries above stay in
this section regardless of whose code caused them.

**The referees themselves are limited.** One peer is an example server that has not moved in about a
year; disagreeing with it proves little in either direction. The second is certified, but for OCPP
2.0.1, while what we exercised was its 2.1 path on a pre-release image — so the two defects we ran
into there most likely sit outside the scope of its certification. The third is a library rather
than a deployed CSMS: its Battery Swapping module is not mentioned in its own README, is absent from
its published artifacts, and the server we ran it in was written here, so what it proves is that
*another author's reading of Block S accepts our frames* — not that any product does. An outside
implementation being wrong is a real outcome of interoperability testing, and it is not the same as
our being right.

**None of this is repeatable by a gate.** The runs were manual, they needed containers, and nothing
in this repository re-runs them. They are evidence about the days they were performed, not a
standing compatibility claim. Formal certification is a separate matter and is covered above, under
the Battery Swap cases.

**Observation is derived from the log — with three exceptions.** Nothing here is asserted against a
flag the simulator set about itself; every claim is read back out of the event log
([VIRTUAL-STATION.md §4](VIRTUAL-STATION.md#4-observation-is-derived-from-the-event-log)). The first
field outside that rule is `StationSimulator.lastTransmitFailure`, and the reason is structural:
`OcppSession.emitRaw` records before it transmits, so the log entry for a frame that never left is
identical to one for a frame that did; and "no reply came back" cannot substitute, because it does
not distinguish a CALL still in flight or timed out, and SEND and CALLRESULT have no reply at all.
It is read-only in the sense that decides the design: the console does display it and the snapshot
API does carry it, but nothing consults it to *refuse* an operation — not a `check()`, not the
console's `disabledReason`, not an API path — so it reports a transmission failure without becoming
a gate on the call order.

The second is `StationSimulator.lastCallTimeout`, and it exists because the first one cannot say
everything: a frame that went out and drew no answer is not a frame that never left, and the log
cannot separate them either — no CALLRESULT is indistinguishable from a CALL still in flight. It is
cleared as soon as any answer arrives, a rejection included, because the question it holds is
whether an answer came at all. The same read-only rule applies.

The third is `StationSimulator.unsupportedActions`, and its reason is the mirror image: the log
holds the CALLERROR but not what was done with it, so it cannot say whether the run stopped there or
went on. It accumulates rather than holding only the last, because a peer's not knowing an action
does not stop being true the way a dead line does. The same read-only rule applies, and here it
matters more: skipping a later send because the peer refused an earlier one would be the harness
quietly conforming to the implementation it is meant to be checking.
