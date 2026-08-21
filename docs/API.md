# The swap REST API — the client contract for standard use case S02

> **The app is not implemented. The contract is designed.**
>
> OCPP 2.1 already defines the app scenario:
>
> > **S02 - Battery Swap Remote Start**: *"EV Driver requests CSMS to initiate a battery swap
> > **via a smartphone app, e.g. by scanning a QR code** or selecting the appropriate station
> > in the app."*
>
> That is, `app → CSMS → RequestBatterySwap → station` is **the standard use case**.
> This document is the contract for the first of those arrows.

---

## ⚠️ Read this first — this API is not production

| Not here | Why |
|---|---|
| App permission model · API keys · JWT · CORS policy | Caller identity is closed with Basic, but per-app permissions, token lifetimes, and browser policy are not there yet |
| Rate limiting · audit trail | Same as above |
| Metrics dashboard · UI | A settled scope decision — **querying over REST is the boundary** |
| Localization (i18n) | A settled scope decision — with no app implemented there is no real requirement |
| Paging · list queries (`GET /api/swaps`) | There is no consumer. Endpoints are not cut for consumers that do not exist |

What is closed is written down as closed. The WebSocket uses security profile 1 Basic, this REST
API uses its own separate Basic, and `sim-console` binds to loopback by default. What remains are
operational boundaries: mTLS and certificate management, credential rotation, rate limiting, an
operational audit log, and the H2 file database.

> Authorization is not simply absent. **OCPP-level authorization (`idToken`) works** — an
> unauthorized token means `RequestBatterySwap` never goes out (S02.FR.03). REST Basic
> establishes the identity of **the API caller**; OCPP `idToken` authorization decides the token
> being swapped for. They are different layers.

---

## Authentication

`/api/*` passes through HTTP Basic. The `/ocpp/**` WebSocket handshake does not go through this
filter — it uses the station credentials in `csms.security.stations` instead.

```yaml
csms:
  api:
    security:
      enabled: true
      realm: swapve-api
      users:
        - username: operator
          password-hash: "$2a$10$replace-with-bcrypt-hash"
```

Generate the hash with something like Spring Security Crypto's
`BCryptPasswordEncoder(10).encode("api-password")`. If `users` is empty the server still starts,
but every `/api/*` request answers 401.

```bash
curl -u operator:api-password localhost:8080/api/metrics/swaps
```

Failure responses leak no reason.

```http
HTTP/1.1 401
WWW-Authenticate: Basic realm="swapve-api", charset="UTF-8"
Content-Type: application/json; charset=utf-8

{"error":"UNAUTHORIZED"}
```

---

## Endpoints

| Method | Path | What it does |
|---|---|---|
| `POST` | `/api/swaps` | Start a swap — fires `RequestBatterySwapRequest` (S02) |
| `GET` | `/api/swaps/{id}` | The progress of one swap |
| `GET` | `/api/metrics/swaps` | Success rate · duration · failure reasons (criterion S5) |
| `GET` | `/api/stations/{stationId}/charging-transactions` | That station's charging transactions (S04) |
| `GET` | `/api/stations/{stationId}/charging-transactions/{transactionId}` | One charging transaction |

> **Charging is not a sub-resource of a swap.** Charging an incoming battery continues for days
> after the swap has ended. See the section on it below.

### The swap identifier `{id}` is `{stationId}:{requestId}`

A swap is **unique only under the composite key `(stationId, requestId)`** — `requestId` is unique
only within a station's scope. Both go into one token.

That the colon is safe is not a coincidence. **Part 4 §3.1.1 forbids a colon in a station
identifier**, and that rule is not prose but an executing check — the handshake **rejects** an
identifier containing one (`StationIdentityTest`). So splitting on the first colon is unambiguous.

```
CS001:1734829911     →  swap 1734829911 at station CS001
```

---

## `POST /api/swaps` — start a swap

Exactly what the app gets from the QR code: **at which station**, **for whom**.

```http
POST /api/swaps
Content-Type: application/json

{
  "stationId": "CS001",
  "idToken": { "idToken": "RFID-0001", "type": "ISO14443" }
}
```

**It does not accept a `requestId`.** In S02 the **CSMS** issues that number. Letting the app
choose leaks the rule to the client and blurs where the responsibility in **S02.FR.02** lies —
the station has to reuse the same value in the `BatterySwapRequest` that follows.

`idToken` is a `(idToken, type)` **value object**, not a foreign key into a local user table
(leaving room for roaming). A roaming token is not in our database in the first place.

### Outcomes and HTTP statuses

| Outcome | HTTP | Body | What the app does |
|---|---|---|---|
| Station answered `Accepted` | **201 Created** + `Location` | `outcome: ACCEPTED` + `swap` | Go to the swap screen |
| Station answered `Rejected` | **200 OK** | `outcome: REJECTED_BY_STATION` + `reasonCode` | **Show the reason to the user** |
| Unauthorized `idToken` | **403 Forbidden** | `error: NOT_AUTHORIZED` | Check the token |
| Station not connected | **503 Service Unavailable** | `error: SERVICE_UNAVAILABLE` | Retry later |
| Station did not answer | **504 Gateway Timeout** | `error: GATEWAY_TIMEOUT` | **Retry with care** ↓ |
| Station answered CALLERROR / violated the schema | **502 Bad Gateway** | `error: BAD_GATEWAY` | Retrying changes nothing |
| `stationId` or `idToken` missing | **400 Bad Request** | `error: INVALID_REQUEST` | Fix the request |

> **Why the 5xx cases are not collapsed into one.** Because the app has to do different things.
> With no connection you simply wait for the station to come back (503), but **when there is no
> answer (504) the swap may actually have opened** — the request went out and only the response
> was lost. Answering both the same way hides that difference from the app.

### ★ Why `Rejected` is not an error

**Having no battery is not a system failure.** The station decides inventory (**S02.FR.04**), and
the CSMS need not know it. This is the scenario of the official conformance case
`TC_S_102_CSMS` and of failure scenario **F1**.

Answering 5xx makes the app treat it as retryable and shows the user *"server error"* — when the
truth is the ordinary operational state *"this station has no battery to give right now."*

```jsonc
// 200 OK
{
  "outcome": "REJECTED_BY_STATION",
  "stationId": "CS001",
  "requestId": 1734829911,
  "swap": null,                        // no swap opened — there is nothing to query
  "reasonCode": "NoBatteryAvailable",  // verbatim from the station (§4.9.1)
  "reason": "NO_BATTERY_AVAILABLE",    // present only when it resolved to a predefined reason
  "additionalInfo": null,
  "rejectedAt": "2026-08-18T09:30:00.000Z"
}
```

`reasonCode` is **what the station sent, verbatim**; `reason` is that resolved through the
appendix `reason_codes.csv`. A value not in the table is not discarded — `reason` is then `null`
and the original survives.

### `Accepted` — 201

```jsonc
// 201 Created
// Location: /api/swaps/CS001:1734829911
{
  "outcome": "ACCEPTED",
  "stationId": "CS001",
  "requestId": 1734829911,
  "swap": { /* ↓ SwapView. its self equals the Location */ },
  "reasonCode": null, "reason": null, "additionalInfo": null, "rejectedAt": null
}
```

### `403` — S02.FR.03

> **S02.FR.03**: the CSMS **SHALL NOT** send `RequestBatterySwap` with an unauthorized `idToken`.

**It was not sent and refused — it was never sent.** No frame goes on the wire.

```jsonc
// 403 Forbidden
{
  "error": "NOT_AUTHORIZED",
  "message": "인가되지 않은 idToken 이라 RequestBatterySwap 을 보내지 않았다 (S02.FR.03)",
  "stationId": "CS001",
  "idTokenStatus": "Unknown"     // not Invalid — it is simply not on the authorized list
}
```

<sub>The `message` field is currently Korean: *"the idToken was not authorized, so
RequestBatterySwap was not sent (S02.FR.03)."* Message localization is still open, together with
the docs.</sub>

---

## `GET /api/swaps/{id}` — progress

```jsonc
// 200 OK
{
  "id": "CS001:1734829911",
  "self": "/api/swaps/CS001:1734829911",
  "stationId": "CS001",
  "requestId": 1734829911,
  "status": "COMPLETED",
  "idToken": { "idToken": "RFID-0001", "type": "ISO14443" },

  "authorizedAt": "2026-08-18T09:30:00.000Z",   // when authorization landed (not when a battery moved)
  "startedAt":    "2026-08-18T09:30:12.345Z",   // when the first battery moved
  "endedAt":      "2026-08-18T09:31:42.345Z",
  "durationMillis": 90000,                  // null while in progress

  "batteriesIn": [
    { "slotId": 1, "serialNumber": "BAT-USED-1", "soC": 23.0, "soH": 85.0 },
    { "slotId": 2, "serialNumber": "BAT-USED-2", "soC": 45.0, "soH": 87.0 }
  ],
  "batteriesOut": [
    { "slotId": 3, "serialNumber": "BAT-FULL-3", "soC": 80.0, "soH": 95.0 },
    { "slotId": 4, "serialNumber": "BAT-FULL-4", "soC": 85.0, "soH": 78.0 }
  ],

  "ledgerImbalance": null                   // non-null only for OUT_TIMED_OUT
}
```

### Status

Exactly the states of the domain state machine. It is **order-agnostic**, so the usual
in-first order and the out-first order (`SwapOrder = "Out-In"`) reach the same terminals.

```
                    AUTHORIZED          ← start approved. requestId fixed
                    ╱         ╲
              HALF_IN         HALF_OUT  ← one half has opened
              ╱     ╲             │
     COMPLETED   OUT_TIMED_OUT  COMPLETED
```

| Status | Meaning | `batteriesIn` | `batteriesOut` | Terminal |
|---|---|---|---|---|
| `AUTHORIZED` | The start was approved. No battery has moved yet | `[]` | `[]` | |
| `HALF_IN` | The used battery came in | present | `[]` | |
| `HALF_OUT` | The fresh battery went out (Out-In order) | `[]` | present | |
| `COMPLETED` | **count in = count out** | present | present | ✅ |
| `OUT_TIMED_OUT` | The offered battery was never taken | orphan | `[]` | ⚠️ |

> There is no `IDLE`. That means "nothing has happened yet", which is not a state of a swap.
> A swap event arriving without authorization (**F5**) is recorded as an anomaly but **does not
> open a swap**, so it is not queryable here (404). That fact lives in the metrics under
> `failures.byScenario.F5`.

### ★ Battery data is emitted for both sides

`serialNumber` · `soC` · `soH` are preserved for **both the incoming and the outgoing** batteries,
because the standard names the basis for pricing:

> *"the price can depend, for example, on **the difference between the state of charge of the
> old and new batteries**"* (Part 2 S. Ch.1)

Billing is out of scope, but discarding the values here would stop billing from ever being **a
pure calculation over existing data**. Throwing information away is not a scope decision — it is
an irreversible premise.

### ★ `OUT_TIMED_OUT` — the ledger imbalance is made visible

> **S03.FR.06**: *"Situation needs to be reported, because CSMS ends up with an
> **orphan BatteryIn for which a BatteryOut is missing**."*

It is not quietly flattened into "failed". Compensating requires knowing **how many are orphaned,
and which batteries they are**.

```jsonc
{
  "status": "OUT_TIMED_OUT",
  "batteriesIn":  [ /* orphaned batteries — no matching BatteryOut */ ],
  "batteriesOut": [],
  "ledgerImbalance": {
    "orphanCount": 2,
    "orphanBatteries": [
      { "slotId": 1, "serialNumber": "BAT-USED-1", "soC": 23.0, "soH": 85.0 },
      { "slotId": 2, "serialNumber": "BAT-USED-2", "soC": 45.0, "soH": 87.0 }
    ],
    "persisted": true      // this debt also survives in H2 (the only derived state that is persisted)
  }
}
```

`persisted` is not hidden, because `false` would mean a debt that disappears the moment the
process dies. An operator has to know that difference.

### A swap that does not exist — 404

```jsonc
// 404 Not Found
{ "error": "NOT_FOUND", "message": "그런 교환이 없다: CS001:9999", "stationId": null, "idTokenStatus": null }
```

<sub>The `message` field is currently Korean: *"no such swap: CS001:9999."*</sub>

**A malformed identifier is also 404**, not 400. Answering 400 would separate *"well-formed but
absent"* from *"malformed"*, and that difference becomes a way to probe for station identifiers.
From the app's side both mean *"there is no such swap."*

---

## `GET /api/metrics/swaps` — metrics (criterion S5)

> Criterion S5: *"success rate, duration, and failure reasons queryable over REST."*

```jsonc
// 200 OK
{
  "generatedAt": "2026-08-18T09:30:00.000Z",

  "swaps": {
    "attempted": 7,        // starts that actually reached a station (opened swaps + rejected starts)
    "completed": 2,
    "inProgress": 3,       // AUTHORIZED / HALF_IN / HALF_OUT
    "failed": 2,           // OUT_TIMED_OUT + starts the station rejected
    "blockedStarts": 1     // ★ attempts never sent (S02.FR.03) — not counted in attempted
  },
  "successRate": 0.2857142857142857,   // completed / attempted. null when there were no attempts

  "duration": {
    "completed":   { "count": 2, "minMillis": 30000, "meanMillis": 60000,
                     "maxMillis": 90000, "p50Millis": 30000, "p95Millis": 90000 },
    "outTimedOut": { "count": 1, "minMillis": 45000, "meanMillis": 45000,
                     "maxMillis": 45000, "p50Millis": 45000, "p95Millis": 45000 }
  },

  "failures": {
    "total": 4,
    "byScenario":  { "F1": 1, "F2": 1, "F3": 1, "F5": 1 },
    "byReasonCode": { "NoBatteryAvailable": 1, "BatteryUnknown": 1 },
    "byAnomalyReason": { "NOT_AUTHORIZED": 1 },
    "rejectedAuthorizations": 1
  },

  "idempotency": {
    "byScenario": { "F4": 1, "F6": 1 },
    "stateMachineIgnores": 1,
    "byIgnoreReason": { "DUPLICATE_BATTERY_IN": 1 },
    "sessionReplays": 1
  },

  "ledger": { "openImbalances": 3, "orphanBatteries": 6 }
}
```

### The denominator of the success rate is stated

`attempted` means **starts that actually reached a station**: every opened swap plus every start
the station rejected.

**`blockedStarts` — attempts stopped by S02.FR.03 — is not in the denominator.** Counting
something whose `RequestBatterySwap` never went on the wire as a swap attempt would depress the
success rate by exactly that much. What an authorization policy blocked is not a failed swap; it
is **a swap that never started**.

### Duration — a mean alone is of little use

Swap durations are not symmetrically distributed. Most finish in tens of seconds while a few drag
on for reasons of the user's own. In such a distribution a single mean becomes *"a value that is
neither fast nor slow, and that nobody experienced."*

- **Percentiles are nearest-rank** — sort, then take the `ceil(p/100 × n)`-th value as is. The
  reason for not interpolating is **to point at a value that actually occurred**. An interpolated
  number is no swap's duration.
- **With no samples, only `count` is 0 and the rest are `null`.** They are not filled with `0` —
  *"it finished in 0 ms"* and *"nothing finished"* are entirely different facts.
- **Completions and pickup timeouts are not mixed.** The time on an `OUT_TIMED_OUT` is not "the
  swap took this long" but "the user did not collect it for this long."

### Failure reasons — "12 failures" is not information

They are counted along two axes. One axis alone cannot tell whether *"3 rejections"* meant no
battery or an unknown battery.

| Axis | Values |
|---|---|
| `byScenario` | F1–F6 of the failure scenarios |
| `byReasonCode` | The reasons in the appendix `reason_codes.csv` |

| # | Scenario | Counted from | Is it a failure |
|---|---|---|---|
| **F1** | No battery — station answers `Rejected`/`NoBatteryAvailable` | the rejection record | ✅ |
| **F2** | Pickup timeout → `OUT_TIMED_OUT` | the swap status | ✅ |
| **F3** | Unknown battery — rejected via `customData` | **the raw event log** ↓ | ✅ |
| **F5** | Order violation — arrives without authorization | the anomaly record | ✅ |
| **F4** | Duplicate `BatteryIn` (new messageId) | the state machine's ignore record | ❌ **idempotent** |
| **F6** | Reconnect retransmission (same messageId) | **duplicate receipt in the event log** ↓ | ❌ **idempotent** |

**F4 and F6 are not failures.** Retransmission and duplicate delivery happen normally, and **not
being applied twice** is the correct behaviour. Counting them as failures would worsen the success
rate for no reason; not counting them at all would leave no explanation for why the ledger did not
grow. Hence the separate `idempotency` block.

**The two are distinguished, because they were caught at different layers.** F4 passed the
idempotency ledger and was caught by the **state machine** on `(stationId, requestId)`; F6 was
answered by the **session's idempotency ledger** with the stored response, without ever calling the
layer above.

`rejectedAuthorizations` counts attempts arriving with an unauthorized token. **It mixes blocked
S02 remote starts with rejected S01 local authorizations** — the authorization record does not
retain the origin of the attempt. If that distinction is ever needed, the place to fix is that
record, not the metric.

### `ledger` answers a different question

Every number above concerns **the swaps this process has seen**, so a restart counts from zero.
Only `ledger` reads from H2, so it **survives restarts** (of the swap statuses, only
`OUT_TIMED_OUT` is persisted). It is **the total of debts to be compensated**, not the number of
failures in this observation window. The two differing does not make either wrong.

---

## `GET /api/stations/{stationId}/charging-transactions` — charging transactions (S04)

> Charging transactions, per a settled scope decision: **received and recorded only.** No smart
> charging, no tariffs.

```jsonc
// 200 OK — every charging transaction at this station
[
  {
    "self": "/api/stations/CS001/charging-transactions/0KA9L2M3N4P5",
    "stationId": "CS001",
    "transactionId": "0KA9L2M3N4P5",
    "slotId": 1,                          // which slot (EVSE)
    "batterySerialNumber": "BAT-USED-1",  // which battery (null when unknown — see below)
    "status": "SUSPENDED",                // CONNECTED | CHARGING | SUSPENDED | ENDED | UNKNOWN
    "socPercent": 50.0,                   // last reported SoC (S04.FR.04). null when absent
    "startedAt": "2026-08-18T09:30:00.000Z",
    "updatedAt": "2026-08-18T10:00:00.000Z",
    "eventCount": 6
  }
]
```

`GET /api/stations/{stationId}/charging-transactions/{transactionId}` returns the same object
singly, and **404** when absent. The list answers **200 with an empty array** even for a station
that does not exist — *"no charging is running at that station"* is a normal answer, and there is
no station resource yet.

### It is not a sub-resource of a swap

No path like `/api/swaps/{id}/charging` was cut, because **charging an incoming battery continues
for days after the swap ends**. Hanging it under the swap would erase the name for that battery's
charging the moment the swap turns `COMPLETED`. Charging is bound to a slot at a station, so the
path lives there.

For the same reason this response carries **no field pointing back to a swap**. This battery is
not bound to any swap. (`ChargingApiTest` pins this as *"charging of an incoming battery is still
queryable after the swap completes."*)

### `status` is not the raw `chargingState`

The value list of `chargingState` may be extended by the standard (`SuspendedEV` and so on), while
what a consumer needs is three things — **charging · stopped · finished**. It is translated once at
the boundary, exactly like `SwapView.status`.

**`SUSPENDED` is not finished.** It means delivery stopped on reaching `MaxSoc` (S04.FR.06); the
battery is still in the slot and the transaction is alive. The end comes when the battery is taken
out (`TxStopPoint = EVConnected`, S04.FR.09).

### `batterySerialNumber` being `null` is not a bug

The only moment the CSMS learns which battery is in which slot is when `BatterySwapRequest`
(`BatteryIn`) carries `(evseId, serialNumber)` — **that one moment and no other**. Neither
`TransactionEvent` nor `NotifyEvent` carries a serial number. So for a battery that was already
seated at boot, **the CSMS genuinely does not know**, and it does not invent what it does not know.
To find out, ask with `GetVariables`.

### There are no writes

A charging transaction is a fact the station creates, not something the CSMS directs. Smart
charging and tariffs are out of scope (a settled scope decision). Hence there is no `POST` and no
`PATCH`.

### The device model is not on REST

`GetVariables`/`SetVariables` (`TargetSoC` · `MaxSoc` · `BatteryCartridge.SoC` …) are reachable
only through `DeviceModelClient` inside the CSMS and are **not exposed over HTTP**, because there
is no consumer — an app does not touch a station's configuration variables, and there is no
operator tool yet. When one is needed, this is where a thin controller calling that class belongs.

---

## Design notes

### Micrometer / Actuator was not used for the metrics

**Not using it is the simpler option:**

1. **Every value needed is derived from records that already exist.** The success rate is the
   status distribution of the swap store; durations are differences between timestamps already in
   those statuses; failure reasons live in the rejection, anomaly, and idempotency records and in
   **the raw event log**. The **one rule** imposed on that event log is *"derived state must be
   computable from this log"*, and the metrics are exactly where that rule is checked.
   - **F3 and F6 are the proof.** Neither has a record kept for the metrics' sake — both are
     computed from the raw text of the `customData` rejection and of the duplicated CALL. Had only
     the interpretation been kept instead of the original, they could not be counted.
2. **A separate counter creates a second source of truth.** The moment `Counter.increment()` is
   sprinkled through the code, the ledger (H2) and the counters (in memory) start diverging across
   restarts, exceptions, and retransmissions. The metrics then stop explaining the system and
   become **something that itself needs explaining**.
3. **There is no consumer.** A settled scope decision excluded dashboards. Micrometer's values
   matter when there is a scrape target such as Prometheus, and this project has none.
4. **No dependency is added.**

**When this would change:** time series — trends over time — are a different story. This
calculation is always "everything so far", so it cannot answer a windowed query. The place to
attach it would be the same one, and because the event log is kept, **retroactive computation is
possible.**

### The sending logic was not rebuilt

Sending S02 (`RequestBatterySwapRequest`) was already finished earlier — step 1 of
`TC_S_103_CSMS` is the CSMS sending, so it could not come after conformance. This API lays a REST
entry point on top of that, and all the controller does is one `when` moving the result into HTTP.

The authorization decision (S02.FR.03), the request's self-validation against the official schema,
and dispatch through `StationCommandBus` all live in the layer below. **Adding REST touched not one
line of the OCPP path.**

---

## Tests

Every contract in this document is pinned by an executing test.

| Test | What it confirms |
|---|---|
| `SwapApiTest` | The whole path against **a simulator connected over a real WebSocket**. No mocks |
| `SwapMetricsApiTest` | One run of a scenario mixing success, failure, and idempotency, then success rate, duration, and per-reason aggregation |
| `DurationSummaryTest` | The pure calculation of percentiles, means, and empty samples |
| `SwapIdsTest` | Composite key ↔ URL token |
| `ChargingApiTest` | Charging queries — slot, battery, and SoC are readable, and **charging survives the swap completing** |

```bash
./gradlew :csms:test --tests 'dev.swapve.csms.api.*'
```

Whether the `POST` really emitted a `RequestBatterySwapRequest` is confirmed by **the frame the
simulator received**, not by the CSMS's return value. Response bodies are read as raw JSON field
names rather than back through DTOs — reading them back through our own types would let the tests
pass even after a field is renamed, and the thing that breaks then is the app.
