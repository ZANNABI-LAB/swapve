<p align="center">
  <img src="docs/assets/hero.png" alt="SwapVe" width="100%">
</p>

<h1 align="center">SwapVe</h1>

<p align="center">
  <b>An OCPP 2.1 Battery Swap (Block S) library and test tooling for the JVM.</b><br>
  Codec and session layers, a swap domain model, a station simulator — and a reference CSMS.
</p>

<p align="center">
  <b>English</b> · <a href="README.ko.md">한국어</a>
</p>

<p align="center">
  <a href="https://github.com/ZANNABI-LAB/swapve/actions/workflows/ci.yml"><img src="https://github.com/ZANNABI-LAB/swapve/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="license"></a>
  <img src="https://img.shields.io/badge/OCPP-2.1%20Edition%202-informational" alt="OCPP 2.1">
  <img src="https://img.shields.io/badge/Kotlin-2.1-7F52FF" alt="Kotlin">
  <img src="https://img.shields.io/badge/JDK-17-orange" alt="JDK 17">
</p>

---

## What you get

**Four things you can take separately.** You are not expected to use all of them — that is the design.

| What | Module | Reach for it when |
|---|---|---|
| **OCPP 2.1 codec + session** | `ocpp-core` | You need OCPP-J framing and validation against the **181 official JSON schemas**. It knows nothing about frameworks |
| **Battery Swap domain** | `swap-domain` | You need the swap state machine, slot model, and invariants. No I/O at all |
| **Simulator + control console** | `station-sim`·`sim-console` | You want to **exercise your own CSMS against Block S**. Failure scenarios F1–F6 are one button each |
| **Reference CSMS** | `csms` | You want to see how the pieces fit. The conformance cases run against this |

> **This does not promise a finished CSMS.** `csms` is a reference implementation, not a product.
> The codec and schema layers **are callable from Java** — the session layer is Kotlin-only.
> That is measured, not assumed: see [LAYERS §4](docs/LAYERS.md).
>
> **There are no generated message DTOs.** Payloads are Jackson `JsonNode` / `ObjectNode`, and
> correctness is decided by the **official schema** rather than by a transcribed class. That is a
> licensing consequence, not an oversight — the OCA schemas are CC BY-ND 4.0, so putting them into
> code would make a derivative. You gain a validator that cannot drift from the standard; you pay
> for it by reading and building fields by hand.

**On Maven Central.** Take either module on its own.

```kotlin
dependencies {
    implementation("io.github.zannabi-lab:ocpp-core:0.3.0")   // codec · schema validation · session
    implementation("io.github.zannabi-lab:swap-domain:0.3.0") // the swap state machine, zero dependencies
}
```

<sub>Changes between versions are in [CHANGELOG.md](CHANGELOG.md). While the major version is `0`
the public API may still change between minor versions — no consumer outside this repository has
tested its shape yet.</sub>

**What using it looks like.** The library owns framing, schema validation, timeouts, and the
one-CALL-in-flight rule of Part 4 §4.1.1. You supply a transport function and a handler.

```kotlin
val sessions = OcppSessions(Clock.systemUTC(), eventSink = myEventLog)

// One session per connection. After a reconnect, call open() again for the same station:
// the idempotency ledger carries across, which is what stops a retransmitted swap from
// being counted twice.
val session = sessions.open(
    stationId = "CS001",
    transmit = { line ->
        if (socket.isOpen) { socket.send(line); TransmitOutcome.Delivered }
        else TransmitOutcome.Gone("socket closed")
    },
    onCall = { stationId, call -> InboundResponse.Respond(answerFor(call)) },
)

// Feed inbound lines in arrival order:  session.receive(line)

when (val result = session.call(OcppCall("RequestBatterySwap", payload))) {
    is OcppResult.Accepted -> result.payload
    is OcppResult.Rejected -> result.knownErrorCode
    is OcppResult.InvalidResponse,
    is OcppResult.TimedOut,
    is OcppResult.NotConnected -> null   // never thrown — every outcome is a value
}
```

Opening the connection, reconnecting, and calling `receive` in arrival order stay yours.
**The codec and schema layers are callable from Java; the session layer above is Kotlin-only**
(coroutines). `swap-domain` has no dependencies at all, but its identifiers are Kotlin
`value class`es, which Java sees as mangled `constructor-impl` names — it is written for Kotlin
consumers. → [docs/LAYERS.md](docs/LAYERS.md) §4

## Quick start

Every tagged release carries a **`swapve-<version>.zip`** with the CSMS, the station simulator
and the control console already built — JDK 17 is the only requirement:

```bash
unzip swapve-0.3.0.zip && cd swapve-0.3.0
java -jar csms/csms.jar --csms.security.profile=NONE   # terminal A
./station-sim/bin/station-sim --station-id CS001       # terminal B — one swap, then exits
./sim-console/bin/sim-console                          # optional — drive it from a browser
```

To build it from source instead, all you need is **JDK 17 and git**. The Gradle wrapper fetches
the rest.

```bash
git clone https://github.com/ZANNABI-LAB/swapve.git && cd swapve
./gradlew build          # full test suite + module boundary checks
```

**Terminal A** — the CSMS. You are ready when `Started CsmsApplicationKt` appears.

```bash
./gradlew :csms:bootRun --args="--csms.security.profile=NONE --csms.api.security.enabled=false"
```

**Terminal B** — a simulated station connects and **completes one battery swap**, then exits.

```bash
./gradlew :station-sim:run --args="--csms-url ws://localhost:8080/ocpp --station-id CS001 --request-id 1001"
```

```
station-sim → ws://localhost:8080/ocpp/CS001 (order In-Out, 2 batteries)
Exchange complete: requestId=1001, 42 messages exchanged
```

**Terminal C** — query exactly what an app would see.

```bash
curl localhost:8080/api/swaps/CS001:1001      # one swap — SoC/SoH of both battery sets
curl localhost:8080/api/metrics/swaps         # success rate · duration · failure reasons
curl localhost:8080/api/stations/CS001/charging-transactions   # charging of the returned batteries (S04)
```

> The steps above are a local demo, so both authentication layers are turned down.
> **The operational defaults are Basic over WebSocket and Basic on the REST API**
> → [docs/CONFIGURATION.md](docs/CONFIGURATION.md)

<details>
<summary><b>Reverse-order swaps · CSMS-initiated swaps (S02) · the control console</b></summary>

The reverse order (`Out-In`) is equally standard and works as-is — swap order is direction-agnostic by design:

```bash
./gradlew :station-sim:run --args="--csms-url ws://localhost:8080/ocpp --station-id CS001 --swap-order Out-In --request-id 1002"
```

**To start a swap from the CSMS side, the way an app would** (standard use case S02), run the
simulator in standby and trigger it over REST. The correlation number (`requestId`) is issued by
the CSMS here, and the station adopts it verbatim (S02.FR.02).

```bash
# Terminal B — do not self-start; wait for RequestBatterySwap
./gradlew :station-sim:run --args="--csms-url ws://localhost:8080/ocpp --station-id CS001 --remote-start"

# Terminal C — as if the driver scanned a QR code
curl -X POST localhost:8080/api/swaps -H 'Content-Type: application/json' \
     -d '{"stationId":"CS001","idToken":{"idToken":"RFID-0001","type":"ISO14443"}}'
```

**To drive it from a screen**, start the control console. It **adds to the CLI rather than
replacing it** — everything above still works:

```bash
./gradlew :sim-console:run --args="--port 8090 --csms-url ws://localhost:8080/ocpp"
```

Open `localhost:8090`, then **Connect → Start swap**. The **F1–F6 buttons** fire the failure
failure scenarios — "reproduce an empty-inventory rejection" is one click.

- The page is **a single static HTML file** with no CDN, font, or framework links. The HTTP server
  is the JDK's own `com.sun.net.httpserver` — **it comes up with no network at all**
- **F1 (no battery available) is a CSMS-initiated scenario** (S02.FR.04), so the console goes into
  standby and prints the exact `curl` line to paste; the rejection reason then lands on screen
- There is a control API too — `POST /api/stations` · `POST /api/stations/{id}/swap`
  (body `{"fault":"F3"}` injects a fault) · `DELETE /api/stations/{id}` · `GET /api/state`

The console drives the *test rig*. It is not a CSMS and does not pretend to be one.
</details>

<details>
<summary><b>Measured run</b> — timings from actually following the steps above (2026-08-21)</summary>

| Step | Took | Observed |
|---|---|---|
| `./gradlew build` | 54s – 1m25s | 368 tests + 5 module boundary checks passed |
| `./gradlew :csms:bootRun` | ~20s after the command (server itself boots in 2.8s) | `Started CsmsApplicationKt` · `Tomcat started on port 8080` |
| `./gradlew :station-sim:run …` | 13s | `Exchange complete: requestId=1001, 42 messages exchanged` |
| Confirmed on the CSMS side | — | `BatteryIn → HalfIn`, `BatteryOut → Completed` |

**Measured with Gradle dependencies already cached.** A first `./gradlew build` on a fresh clone
also downloads the Gradle distribution and dependencies; that part depends on your connection.
Excluding it, the whole thing is **under three minutes**.

The three `curl` calls were verified in a separate environment as well:

- `/api/swaps/CS001:1001` → `status: COMPLETED`, with `batteriesIn` (2, SoC 12/13%) and
  `batteriesOut` (2, SoC 95%) **both** present, which is what keeps billing computable later
- `/api/metrics/swaps` → `successRate: 1.0`, duration percentiles, and `failures.byScenario`
  split across F1·F2·F3·F5 (F4 and F6 are idempotent, so they do not count as failures)
- `/api/stations/CS001/charging-transactions` → charging transactions per slot

`SwapApiTest`, `SwapMetricsApiTest`, and `ChargingApiTest` call the same endpoints inside a real
Spring context on every build.
</details>

## What this is not

**Not a production system.** The absences come first.

- **What is closed and what is still open differ.** WebSocket defaults to `BASIC`, the REST API has
  its own Basic realm, and `sim-console` binds to loopback. There is no mTLS, no credential
  rotation, no rate limiting, no operational audit trail.
- **Single instance.** Horizontal scaling is not blocked — serialization and the idempotency
  ledger are both keyed by `stationId`, which is the partition key a distributed setup would use —
  but it is not implemented. Restarts *are* survived:
  raw OCPP messages persist to an event log (H2) and derived registries are rebuilt from that log at
  startup (`EventLogRecovery`). Data outside the retention windows (7 days recovery · 30 days audit)
  is not reconstructable.
- **Smart charging, tariffs, roaming, and horizontal scaling are out of scope**
  Not implemented — but **not designed out either**.
- **No dashboard or UI.** Reading stops at REST.

## Why Block S

In January 2025, OCPP 2.1 formally absorbed the **Battery Swap functional block (Block S)**,
covering battery exchange for two- and three-wheelers (scooters, e-bikes) as well as full-size EVs.

**"Supports 2.1" is not this project's differentiator.** A re-check in August 2026 found several
implementations working on 2.1, and one of them turned out to carry Block S. The claim here is
narrower than it once was: **one open-source server-side Block S implementation exists, and no
station-side implementation or Block S test tooling was found besides this one.**

| Project | Role | OCPP 2.1 | Block S |
|---|---|---|---|
| [SteVe](https://github.com/steve-community/steve) | CSMS (Java) | ❌ 1.6J only | ❌ |
| [CitrineOS](https://github.com/citrineos/citrineos) | CSMS (TypeScript) | roadmap, under "other topics" | not mentioned |
| [MaEVe](https://github.com/thoughtworks/maeve-csms) | CSMS (Go) | ❌ 1.6J + 2.0.1 | ❌ |
| [EVerest libocpp](https://github.com/EVerest/libocpp) | **Charger-side** library (C++) | "in development" | not mentioned |
| [Java-OCA-OCPP](https://github.com/ChargeTimeEU/Java-OCA-OCPP) | Library (Java) | ✅ 2.1 tree | ✅ **server-side**, undocumented and not in its published artifacts |
| Solidstudio VCP | **CS simulator** | ✅ supported | undetermined |
| ocpp-rs | Library + simulator (Rust) | ❌ 1.6J / 2.0.1 | ❌ |
| tzi-OCTT | CSMS verification pytest suite | ❌ 2.0.1 / 1.6J | — |
| OCTT (official) | Conformance test tool — **paid subscription** | ❌ 2.0.1 / 1.6 | — |
| **SwapVe** | **Library + test tooling + reference CSMS** (Kotlin) | ✅ | ✅ |

The markings mean different things. **❌** is confirmed unsupported; **"not mentioned"** means the
topic was not found in the project's docs or issues; **"undetermined"** means no basis was found
either way. The latter two are *not evidence of absence* — the Java row is what that warning looks
like when it comes true. Its Battery Swapping module appears in no README and in no released
artifact; it was found by reading the source tree, and then confirmed by compiling it into a server
and running swaps against it ([CONFORMANCE.md](docs/CONFORMANCE.md#limits-of-self-verification)).
**It handles the server side. It is not a station, and it is not tooling for exercising Block S.**
**Corrections are welcome.** If you know an implementation that handles the Block S messages
(`RequestBatterySwap`, `BatterySwap` transaction events), please open an issue.

## Verification — three gates

The three gates **answer different questions**, which is why they were not merged into one


| Gate | Command | What it guarantees |
|---|---|---|
| **L1 unit** | `./gradlew build` | Frame round-trips · validation against the **181 official schemas** · state machine invariants · REST contracts. **Five module boundary checks** and **13 Java-interop tests** run alongside |
| **L2 conformance** | `./gradlew conformanceTest` | Official cases **`TC_S_102_CSMS` · `TC_S_103_CSMS` · `TC_S_104_CS`** plus failure scenarios **F1–F6** |
| **L3 load + audit** | `./gradlew auditTest` | **20 stations connected concurrently**, then an invariant audit that **rebuilds state from the event log** and compares it against the live registries |

`auditTest` does not just print pass/fail — it prints **how many items each check examined**, because
a bare "passed" is indistinguishable from a check that never ran. And **the audit is itself tested**:
deliberately corrupted logs must turn each item red.

> Sample output · success criteria S1–S7 · full conformance case list →
> **[docs/CONFORMANCE.md](docs/CONFORMANCE.md)**
> **Official OCTT certification has not been obtained, and for OCPP 2.1 there is nothing to
> apply to yet** — the OCA's certification programme and its OCTT test tool cover **2.0.1 and
> 1.6** today, and 2.1 support is stated as future work. What is here is an **independent
> implementation** of the Part 6 cases.

**Tested against someone else's server.** The simulator has been pointed at **three independent
open-source implementations**, one of them OCA-certified for OCPP 2.0.1 (that certificate covers a
specific 2.0.1 release; the build exercised here was its `latest`). The handshake, subprotocol
negotiation, `BootNotification`, `NotifyEvent`, `TransactionEvent` and message correlation all held,
and a CALLERROR raised here was read correctly at the other end. **Three defects in this repository
were found that way** — none of them in the published modules.

**Block S has now been answered by someone else's handler.** The third peer is a JVM library, not a
deployed CSMS, and its Battery Swapping module is undocumented and unpublished — but it is a Block S
implementation with a real request handler, written by another author. Compiled into a local server,
it carried a **station-initiated swap and a CSMS-initiated one end to end**, adopted and correlated
the `requestId` it issued, drove our CALL timeout when it stalled a reply, and answered a
retransmission after a mid-swap reconnect. That last run also showed the limit of the referee: it
ran its application handler **twice** for the same `messageId`, which is the case `InboundCallLedger`
exists to absorb.

> What the interoperability runs covered, and where they stopped →
> **[docs/CONFORMANCE.md](docs/CONFORMANCE.md) § Limits of self-verification**

## Layout

```
swapve/
├─ ocpp-core/      OCPP-J framing · official schema validation · session layer  (framework-agnostic)
├─ swap-domain/    Battery Swap domain · swap state machine · slot model        (no I/O)
├─ csms/           the control server — WebSocket · REST API · metrics
├─ station-sim/    station simulator — slots · batteries · fault injection      (zero dependencies)
├─ sim-console/    simulator control console — demo screen + control API        (JDK's own HTTP)
└─ java-compat/    Java interop gate — calls the codec/schema layers from Java  (tests only)
```

`ocpp-core` and `swap-domain` know nothing about frameworks. **That boundary is a build check, not a
comment** — five `check*` tasks run as part of `./gradlew build`. Dependencies flow one way,
`sim-console → station-sim → (ocpp-core, swap-domain)`, and **`csms` is not in that chain** — the
control server must never gain a dependency that lets it drive a station.

## REST API

**A read-only operations screen is served at `/`.** It lists the stations this CSMS knows and
every frame exchanged with each one, payloads verbatim — a static page with no CDN or framework,
the same rule the simulator console follows. See [docs/API.md](docs/API.md).

<sub>The page itself is outside the API filter, but everything it reads is not. A default install
ships `csms.api.security.enabled: true` with no users configured, which denies **every** `/api`
request — so configure `csms.api.security.users`, or start with
`--csms.api.security.enabled=false` for a local look. The screen says so on its face rather than
just showing a bare 401.</sub>

The standard already defines the app scenario. **S02**: *"EV Driver requests CSMS to initiate a
battery swap **via a smartphone app, e.g. by scanning a QR code**."* So
`app → CSMS → RequestBatterySwap → station` is the standard use case. Building the app is out of
scope, but **its contract is designed**.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/swaps` | Start a swap — sends `RequestBatterySwapRequest` (S02) |
| `GET` | `/api/swaps/{id}` | Progress · **SoC/SoH of both battery sets** · ledger imbalance |
| `GET` | `/api/metrics/swaps` | Success rate · duration distribution · **failure reasons by F1–F6** |
| `GET` | `/api/stations/{id}/charging-transactions` | Charging of the returned batteries (S04) |

The full contract and the reasoning behind it (why "no battery available" is a 200 rather than a 5xx;
why the metrics avoid Micrometer) is in **[docs/API.md](docs/API.md)**.

## Documentation

| Document | Contents |
|---|---|
| [docs/LAYERS.md](docs/LAYERS.md) | **Layer boundaries** — the codec is I/O-agnostic, the session layer is coroutine-only. What you take on when you use this as a library |
| [docs/VIRTUAL-STATION.md](docs/VIRTUAL-STATION.md) | **Simulator operations, layered** — which calls are physical acts, which are protocol reports, which are just scripts (module boundaries are LAYERS.md) |
| [docs/API.md](docs/API.md) | Full REST contract and design record |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | Station authentication · REST authentication · TLS |
| [docs/CONFORMANCE.md](docs/CONFORMANCE.md) | Conformance cases · success criteria S1–S7 · audit output · limits of self-verification |
| [docs/PUBLISHING.md](docs/PUBLISHING.md) | How releases reach Maven Central — and the rehearsal that must pass first |

> The documents under `docs/` are **English only**, deliberately — keeping 1,100 lines in two
> languages guarantees they drift apart. This README is the one page kept in both.

## How this is built

Nothing here is called done on the strength of a description. **Six gates are the definition of
done**, and every one of them runs on every push in
[GitHub Actions](.github/workflows/ci.yml) — never squashed into a single command, so that
"the unit tests passed but conformance broke" cannot hide inside one green check.

```bash
./gradlew --no-build-cache :csms:cleanTest build   # L1  unit + module-boundary checks
./gradlew --no-build-cache conformanceTest         # L2  TC_S_102/103_CSMS + failures F1-F6
./gradlew --no-build-cache auditTest               # L3  20 stations at once, then invariants
```

`--no-build-cache` is not decoration: clearing the outputs is not enough, because the build
cache restores them. A gate that did not actually run the tests is green and has checked
nothing — see the header of [ci.yml](.github/workflows/ci.yml) for the measurement that
settled it.

Where the gates cannot reach is written down rather than left implied —
[docs/CONFORMANCE.md](docs/CONFORMANCE.md) has a "limits of self-verification" section.

## License and specification

[Apache License 2.0](LICENSE) — **except `schemas/`** ([NOTICE](NOTICE)).

- `schemas/` contains the **official OCA JSON schemas verbatim**, unmodified.
  © Open Charge Alliance, **CC BY-ND 4.0**.
- **The specification documents (PDF) are not redistributed here.** Download them **free of charge**
  from [openchargealliance.org/download-ocpp](https://openchargealliance.org/download-ocpp/) and
  place them in `docs/spec/`.

<sub>"OCPP" and "Open Charge Point Protocol" are managed by the Open Charge Alliance.
This project is not affiliated with, nor endorsed by, the OCA.</sub>
