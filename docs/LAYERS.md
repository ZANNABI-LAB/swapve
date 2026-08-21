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

---

## 4. Can you use it from Java — measured

**This was measured rather than guessed.** The `java-compat` module holds **13 tests written
purely in Java**, and they run as part of `./gradlew build`. That module contains no Kotlin at
all — if it did, `checkNoKotlinSources` would break the build (the gate was confirmed to go red).

| Layer | From Java | Basis for the verdict |
|---|---|---|
| **L1 framing** | ✅ **works** | `CodecFromJavaTest` — encode, decode, round-trip from Java |
| **L2 schema validation** | ✅ **works** | `SchemaValidationFromJavaTest` — against the 181 official schemas |
| **L3 session** | ❌ **does not work** | `SessionIsKotlinOnlyTest` — see below |

### Four points of friction in L1 and L2 (all workable)

1. **Default arguments are invisible.** Kotlin's `OcppFrameCodec()` becomes
   `new OcppFrameCodec(new ObjectMapper())` in Java; `validate(frame)` becomes `validate(frame, null)`.
2. **Enum constants keep their names.** `RpcErrorCode.RpcFrameworkError` — not UPPER_SNAKE.
3. **A `data object` is a singleton.** Compare with `PayloadValidation.Valid.INSTANCE`.
4. **A `sealed interface` is not friction.** From Java it is just an interface, and JDK 17's
   `instanceof` patterns work on it directly. Because outcomes arrive as values, no `try/catch` is
   needed either.

```java
OcppFrameCodec codec = new OcppFrameCodec(new ObjectMapper());
DecodeOutcome outcome = codec.decode(line);
if (outcome instanceof DecodeOutcome.Decoded decoded) { … }
```

### L3 cannot be used from Java — and the wall was not coroutines

An attempt to write a Java test that stands up a session **failed at compile time**, for a reason
that sits in front of `suspend`:

- **Every constructor of `OcppSession` demands a `DefaultConstructorMarker`.** Default arguments
  mean only the compiler-internal constructor is exposed, so there is **no public constructor Java
  can call at all**.
- The accessor for `DEFAULT_CALL_TIMEOUT` is **mangled** to `getDEFAULT_CALL_TIMEOUT-UwyO8pc`,
  because `kotlin.time.Duration` is a value class. **A hyphen is not valid in a Java identifier.**

So the question of unwrapping `suspend` into a `Continuation` never even arises. **The constructor
is what blocks you.**

`SessionIsKotlinOnlyTest` **pins this finding.** If the wall ever falls — to `@JvmOverloads` or a
`java.time.Duration` overload — that test goes red, and what needs fixing is not the code but
**the verdict in this section**.

### Does this change the library's direction — no

The finding does not hurt, because **the layers were already split**. What a Java consumer usually
wants is **the codec and schema validation** (the near-absence of a 2.1 codec on the JVM is the
reason for publishing at all — the procedure is in [PUBLISHING.md](PUBLISHING.md)). That layer
**is open**.

Opening the session layer to Java is possible — `@JvmOverloads` on the constructors, an overload
taking `callTimeout` as a `java.time.Duration`, a blocking facade over `suspend`. **Not now.**
Widening a public API with no consumer makes the walk-back a breaking change, and that cost is
better weighed once a real requirement appears. The trigger is **an actual Java consumer**.

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
