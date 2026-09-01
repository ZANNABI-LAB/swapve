# Layer boundaries — for people using this as a library

> The question this document answers: **how much can you take, and what do you take on if you do.**
>
> The short answer: the **codec and schema layers of `ocpp-core` know nothing about I/O or
> coroutines.** The **session layer above them is coroutine-only**, and you supply the transport.
> `swap-domain` never leaves the Kotlin standard library.
>
> **From Java**, L1 and L2 work. L3 does not — measured, not assumed ([§4](#4-can-you-use-it-from-java--measured)).

---

## 1. There are four layers, and the build checks the boundaries

```
   ┌──────────────────────────────────────────────────────────┐
   │  your transport adapter  (WebSocket · test harness · …)   │
   └───────────────────────────┬──────────────────────────────┘
                               │  suspend (String) -> Unit
   ┌───────────────────────────┴──────────────────────────────┐
   │  L3  session      dev.swapve.ocpp.session                 │  ← coroutine-only
   │      OcppSession · InboundCallLedger · StationSerializer  │     Clock injected
   └───────────────────────────┬──────────────────────────────┘
   ┌───────────────────────────┴──────────────────────────────┐
   │  L2  validation   dev.swapve.ocpp.schema                  │  ← pure functions
   │      OcppPayloadValidator (181 official schemas)          │
   ├──────────────────────────────────────────────────────────┤
   │  L1  framing      dev.swapve.ocpp.rpc                     │  ← pure functions
   │      OcppFrameCodec : String ⇄ OcppFrame                  │
   └──────────────────────────────────────────────────────────┘

   ┌──────────────────────────────────────────────────────────┐
   │  D   domain       dev.swapve.swap        (swap-domain)    │  ← stdlib only
   │      SwapStateMachine · Slot · SwapTransaction            │     zero dependencies
   └──────────────────────────────────────────────────────────┘
```

**`swap-domain` does not sit under `ocpp-core`.** Neither knows about the other — there is no
dependency between their main sources. Joining the protocol to the domain is `csms`'s job.
(Only `ocpp-core`'s *tests* use `swap-domain`: proving that state can be rebuilt from the event log
requires a state machine.)

The boundaries are not comments — they are **checks that run in `./gradlew build`**.

| Check | Module | What it prevents |
|---|---|---|
| `checkNoFrameworkImports` | `ocpp-core` | Imports of Spring · Netty · servlet · `java.net.http` · `javax/jakarta.websocket`, and **`Instant.now()` · `System.currentTimeMillis` · `System.nanoTime`** |
| `checkNoExternalDependencies` | `swap-domain` | Any compile dependency beyond `kotlin-stdlib` (plus `org.jetbrains:annotations`) |
| `checkNoForbiddenDependencies` | `station-sim` · `sim-console` | Dependencies outside the JDK (one per module) |
| `checkNoKotlinSources` | `java-compat` | Kotlin sources — it stops the Java-compatibility gate from quietly becoming meaningless ([§4](#4-can-you-use-it-from-java--measured)) |

---

## 2. The contract at each layer

| | L1 framing | L2 schema | L3 session | D domain |
|---|---|---|---|---|
| **Package** | `ocpp.rpc` | `ocpp.schema` | `ocpp.session` | `swap` |
| **Coroutines** | none | none | **required** (`suspend`) | none |
| **I/O** | none | reads classpath resources (schemas loaded once) | **none** — delegated to `transmit` | none |
| **Current time** | never reads it | never reads it | injected `Clock` | never reads it |
| **Mutable state** | none | compiled-schema cache | yes (the state of one connection) | none — transitions are pure |
| **Runtime deps** | Jackson | Jackson + `json-schema-validator` | the above + `kotlinx-coroutines-core` | none |

### L1 — the codec knows nothing about I/O

`OcppFrameCodec` only moves between strings and frames. It attaches to any transport.

```kotlin
val codec = OcppFrameCodec()

when (val outcome = codec.decode(text)) {
    is DecodeOutcome.Decoded   -> handle(outcome.frame)
    is DecodeOutcome.Ignored   -> {}                         // a type not in the table — ignore the whole message
    is DecodeOutcome.Malformed -> respondCallError(outcome)   // it carries the errorCode
}

val line: String = codec.encode(OcppFrame.Call(messageId, "Heartbeat", payload))
```

**It answers with values instead of throwing.** A broken frame is not an exception but a
`DecodeOutcome.Malformed`, carrying the `RpcErrorCode` that OCPP-J requires (Part 4 §4.2.3).
`Ignored` exists separately because from 2.1 onward you **do not answer an unknown message type
with a CALLERROR** (errata 2026-06 §4.1/§4.3) — so that adding types later does not break this.

### L2 — schema validation is a pure function too

```kotlin
val validator = OcppPayloadValidator()          // compiles and caches the 181 schemas
when (val v = validator.validateCall("BootNotification", payload)) {
    PayloadValidation.Valid      -> …
    is PayloadValidation.Invalid -> v.violations   // every violation, plus a representative errorCode
    else                         -> …             // NotApplicable — see below
}
```

**An unknown action yields `Invalid`, not `NotApplicable`** — with `NotImplemented` in `errorCode`,
because Part 4 §4.3 requires that code for *"Requested Action is not known by receiver"*.
`NotApplicable` appears only for **frame kinds that have no corresponding schema at all**
(CALLERROR and the like).

⚠️ **Share the instance.** Constructing one per session reparses all 181 schemas that many times.
`OcppFrameCodec` is stateless, so sharing or recreating it are equally fine.

### L3 — the session is coroutine-only

This layer honours the requirements of OCPP-J Part 4 §4.1: response correlation, at most one
in-flight CALL per connection (SHALL NOT), timeouts, idempotent retransmission. **The price is
that it requires coroutines.**

```kotlin
val session = OcppSession(
    stationId = "CS001",
    transmit  = { text -> withContext(Dispatchers.IO) { socket.send(text) } },  // ← the transport is yours
    onCall    = { id, call -> router.handle(id, call) },                        //   suspend (String) -> Unit
    eventSink = eventSink,
    ledger    = ledger,          // keyed by stationId — outlives the session
    serializer = serializer,     // likewise
    clock     = clock,           // it never reads the wall clock itself
)

socket.onText { text -> scope.launch { session.receive(text) } }
val result: OcppResult = session.call(OcppCall("RequestBatterySwap", payload))
```

The public API is four members — `suspend call()` · `suspend send()` · `suspend receive()` ·
`close()`. **`call()` does not throw.** Timeouts and dropped connections alike come back as
`OcppResult` values.

**There is no transport SPI interface.** It takes a function, because an interface with exactly one
implementation is not extensibility — it is debt.

**Consumers that are not written in Kotlin use `OcppSessionsAsync`**, which is the same session
behind `CompletableFuture`s and an `Executor` you own. What that costs, and the one thing it
cannot give back, is measured in [§4](#4-can-you-use-it-from-java--measured).

### D — the domain is a pure state machine

```kotlin
val transition: SwapTransition = SwapStateMachine.transition(state, event)
val rebuilt:   SwapTransaction = SwapStateMachine.replay(initial, events)   // rebuilt from the log
```

The existence of `replay` is what makes the audit (`auditTest`) possible — it compares state
rebuilt from the event log against the in-memory registries.

---

## 3. What you take on when you use the session layer

`OcppSession` represents **one connection**. It does nothing outside that connection.

| Yours to handle | Why it is outside the session |
|---|---|
| Establishing the WebSocket · subprotocol negotiation · TLS · authentication | That is the transport's job. `csms`'s `OcppWebSocketHandler` is the reference implementation |
| Reconnection · backoff | The session never reconnects. A new connection gets a new session |
| The coroutine scope and its lifetime | It does offer `close()`, but you own the scope |
| Serializing concurrent sends | `transmit` must be thread-safe. `csms` uses `ConcurrentWebSocketSessionDecorator` |
| Keeping `ledger` and `serializer` **alive longer than the session** | Both are keyed by `stationId`. For idempotency to survive a reconnect, they have to be held outside (failure scenario F6) |

### Which entry point — measured against our own consumers

There are three, and the right one depends on **who owns the lifetime of the two things that must
outlive a connection**: the idempotency ledger and the per-station serializer. Both are keyed by
`stationId`, and rebuilding either on reconnect makes a retransmission look new — a battery swap
counted twice.

| Use | When | Who does it here |
|---|---|---|
| `OcppSession(...)` directly | Something already owns object lifetimes — a DI container, or a process that holds exactly one station | `csms` (Spring holds the ledger and serializer as beans) and `station-sim` (one station, nothing to share) |
| `OcppSessions.open(...)` | Kotlin, no framework. It holds the four shared pieces so reconnecting is correct by construction | `OcppSessionsAsync` is built on it |
| `OcppSessionsAsync` | The consumer is not written in Kotlin — see [§4](#4-can-you-use-it-from-java--measured) | `java-compat` |

**The constructor stays public deliberately.** The question of closing it was raised and settled
by looking at what actually uses it: both production consumers in this repository call it, and
both have a reason that `OcppSessions` cannot serve — the lifetime is already owned elsewhere.
Closing it would not push those callers toward the safer path; it would only make them rebuild it.

---

## 4. Can you use it from Java — measured

**This was measured rather than guessed.** The `java-compat` module holds **20 tests written
purely in Java**, and they run as part of `./gradlew build`. That module contains no Kotlin at
all — if it did, `checkNoKotlinSources` would break the build (the gate was confirmed to go red).

| Layer | From Java | Basis for the verdict |
|---|---|---|
| **L1 framing** | ✅ **works** | `CodecFromJavaTest` — encode, decode, round-trip from Java |
| **L2 schema validation** | ✅ **works** | `SchemaValidationFromJavaTest` — against the 181 official schemas |
| **L3 session** | ✅ **works through `OcppSessionsAsync`** | `SessionFromJavaTest` — a session is opened, an inbound CALL is answered, an outbound CALL completes with its result. Not one line of reflection, and not one `kotlin.*` import |
| L3 session, **without** that entry point | ❌ **does not work** | `RawSessionIsKotlinOnlyTest` — see below |

### Five points of friction, all workable

1. **Default arguments are invisible.** Kotlin's `OcppFrameCodec()` becomes
   `new OcppFrameCodec(new ObjectMapper())` in Java; `validate(frame)` becomes `validate(frame, null)`.
2. **Enum constants keep their names.** `RpcErrorCode.RpcFrameworkError` — not UPPER_SNAKE.
3. **A `data object` is a singleton.** Compare with `PayloadValidation.Valid.INSTANCE`.
4. **A `sealed interface` is not friction.** From Java it is just an interface, and JDK 17's
   `instanceof` patterns work on it directly. Because outcomes arrive as values, no `try/catch` is
   needed either.
5. **A Kotlin property is a getter.** `session.stationId` is written `session.getStationId()`, and
   the same holds for what a result carries — `accepted.getMessageId()`. This is the one item on
   the list that reaches L3 as well, and it is where a consumer prototype actually tripped.
   `SessionFromJavaTest` calls both, so the sentence is held down by a test rather than by prose.

The first four are L1 and L2; the fifth applies wherever a Kotlin property is read.

```java
OcppFrameCodec codec = new OcppFrameCodec(new ObjectMapper());
DecodeOutcome outcome = codec.decode(line);
if (outcome instanceof DecodeOutcome.Decoded decoded) { … }
```

### What blocked L3 — the wall was one value class

An attempt to write a Java test that stands up a session **failed at compile time**, for a reason
that sits in front of `suspend`. The first diagnosis of it was wrong, and the correction is worth
keeping because it changed what the fix had to be.

**What was written first:** *"Kotlin's default arguments mean only the compiler-internal
constructor is exposed."* **That is not the cause.** Default arguments alone leave the
all-arguments constructor `public`, and Java calls it.

**What is actually the cause:** `callTimeout` is a `kotlin.time.Duration`, which is a **value
class**. A function taking one is name-mangled to avoid a signature clash — but **a constructor
cannot be mangled, because its name is `<init>`**. So Kotlin drops the all-arguments constructor
to `private` and exposes only ones demanding a `DefaultConstructorMarker`. The same applies to
`OcppSessions`, which takes a `Duration` too. Separately, the accessor for `DEFAULT_CALL_TIMEOUT`
is mangled to `getDEFAULT_CALL_TIMEOUT-UwyO8pc`, and **a hyphen is not a valid Java identifier**.

**`suspend` was never the wall.** Past the constructor, `open`, `call` and `receive` are ordinary
public methods with no value-class parameter, and a Java implementation of a `suspend` function
type is a `Function2`/`Function3` that returns its value directly. This was confirmed by a
prototype that reached through the private constructor by reflection and then drove a full
session — inbound CALL answered, outbound CALL correlated — from Java alone.

What that cost the consumer was the call site: implementing `Continuation`, comparing against
`COROUTINE_SUSPENDED`, and unwrapping `kotlin.Result.Failure` every time. **`OcppSessionsAsync`
is that list, written once.**

`RawSessionIsKotlinOnlyTest` pins both halves — that no public constructor is Java-callable, and
that the reason is the value class. If either changes, the entry point's rationale changes with
it.

### What the entry point costs, and what it cannot give back

`OcppSessionsAsync` takes an `Executor` and returns `CompletableFuture`s. Every callback returns
one too, rather than there being a blocking overload of each: a blocking implementation wraps its
result in `completedFuture`, and that line states in the consumer's own code that the callback
holds the calling thread.

| | A Kotlin caller | A Java caller through the entry point |
|---|---|---|
| Waiting for a response | Suspends. **Costs no thread** | Costs the thread that waits. **On JDK 21+, pass `Executors.newVirtualThreadPerTaskExecutor()` and that thread is virtual**, which closes the gap. Nothing in this library pins one — the only `synchronized` blocks guard in-memory collections and do no I/O |
| Cancellation | Inherited. A parent's cancellation kills the call | **Not inherited.** Java has no ambient context, so a future is cancelled only by `cancel()` or `close()` |
| Structured concurrency | Free — a child cannot outlive its parent | Absent. `close()` stands in for it: closing one session cancels that session's work, closing the `OcppSessionsAsync` cancels every session's. Without it, a handler future that never completes would hold that session's request queue open for good |
| Arrival order of `receive` | **Yours to keep** | **Kept for requests**, because a future invites being fired several at once. Responses (CALLRESULT · CALLERROR · CALLRESULTERROR) deliberately skip that queue — they are correlated by `messageId`, so they have no order to keep, and putting one behind a slow request handler would time out an outbound `call` the peer had already answered |

The library still owns no threads: `close()` cancels the coroutines it started and leaves the
executor alone, so the ownership rule in §3 is unchanged — only stated in a type Java already has.

## 5. Why it is split this way

- **So the transport can change later.** The codec and session must not know about WebSockets for
  the same code to run against a test harness or any other transport.
- **So tests never wait on real time.** That is why the wall-clock ban is baked into
  `checkNoFrameworkImports` — timeout tests finish instantly on virtual time.
- **So the domain is not contaminated by the protocol.** `swap-domain` knows nothing about OCPP,
  which is why the state-machine tests run with no JSON and no network.

Detaching the session layer from coroutines into a full Sans-I/O design was considered but is
**not being done now** — there is no consumer, and the codec and schema layers already have that
property. The trigger would be *"a library consumer appears, or the test tooling is pushed as a
product in its own right"*. The reason to wait is that abstraction without a consumer is guesswork.
