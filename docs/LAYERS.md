# 층 경계 — 라이브러리로 쓰려는 사람을 위한 문서

> 이 문서가 답하는 질문: **어디까지 가져다 쓸 수 있고, 그러면 무엇을 떠안게 되는가.**
>
> 짧은 답: `ocpp-core` 의 **코덱·스키마 층은 I/O 도 코루틴도 모릅니다.**
> 그 위의 **세션 층은 코루틴 전용**이고, 전송은 여러분이 넣습니다.
> `swap-domain` 은 Kotlin 표준 라이브러리 밖으로 나가지 않습니다.
>
> **Java 에서는** L1·L2 가 됩니다. L3 는 안 됩니다 — 짐작이 아니라 실측입니다 ([§4](#4-java-에서-쓸-수-있습니까--실측-2026-08-20)).

---

## 1. 층은 넷이고, 경계는 빌드가 검사합니다

```
   ┌──────────────────────────────────────────────────────────┐
   │  여러분의 전송 어댑터  (WebSocket · 테스트 하네스 · 무엇이든)  │
   └───────────────────────────┬──────────────────────────────┘
                               │  suspend (String) -> Unit
   ┌───────────────────────────┴──────────────────────────────┐
   │  L3  세션        dev.swapve.ocpp.session                  │  ← 코루틴 전용
   │      OcppSession · InboundCallLedger · StationSerializer  │     Clock 주입
   └───────────────────────────┬──────────────────────────────┘
   ┌───────────────────────────┴──────────────────────────────┐
   │  L2  스키마 검증  dev.swapve.ocpp.schema                   │  ← 순수 함수
   │      OcppPayloadValidator (공식 스키마 181개)               │
   ├──────────────────────────────────────────────────────────┤
   │  L1  프레이밍     dev.swapve.ocpp.rpc                      │  ← 순수 함수
   │      OcppFrameCodec : String ⇄ OcppFrame                  │
   └──────────────────────────────────────────────────────────┘

   ┌──────────────────────────────────────────────────────────┐
   │  D   도메인       dev.swapve.swap        (swap-domain)     │  ← stdlib 만
   │      SwapStateMachine · Slot · SwapTransaction            │     의존성 0
   └──────────────────────────────────────────────────────────┘
```

**`swap-domain` 은 `ocpp-core` 아래에 있지 않습니다.** 둘은 서로를 모릅니다 —
main 소스에 의존이 없습니다. 프로토콜과 도메인을 잇는 것은 `csms` 의 일입니다.
(`ocpp-core` 의 *테스트*만 `swap-domain` 을 씁니다. 이벤트 로그로 상태를 재구성할 수 있음을
증명하는 데 상태머신이 필요해서입니다 — 이벤트 로그.)

경계는 주석이 아니라 **`./gradlew build` 에서 도는 검사**입니다.

| 검사 | 모듈 | 무엇을 막는가 |
|---|---|---|
| `checkNoFrameworkImports` | `ocpp-core` | Spring · Netty · 서블릿 · `java.net.http` · `javax/jakarta.websocket` import, 그리고 **`Instant.now()` · `System.currentTimeMillis` · `System.nanoTime`** |
| `checkNoExternalDependencies` | `swap-domain` | `kotlin-stdlib`(+`org.jetbrains:annotations`) 외의 컴파일 의존 전부 |
| `checkNoForbiddenDependencies` | `station-sim` · `sim-console` | JDK 밖으로 나가는 의존 (각 모듈에 하나씩) |
| `checkNoKotlinSources` | `java-compat` | Kotlin 소스. Java 호환 게이트가 조용히 무의미해지는 것을 막습니다 ([§4](#4-java-에서-쓸-수-있습니까--실측-2026-08-20)) |

---

## 2. 층마다의 계약

| | L1 프레이밍 | L2 스키마 | L3 세션 | D 도메인 |
|---|---|---|---|---|
| **패키지** | `ocpp.rpc` | `ocpp.schema` | `ocpp.session` | `swap` |
| **코루틴** | 없음 | 없음 | **필수** (`suspend`) | 없음 |
| **I/O** | 없음 | 클래스패스 리소스 읽기 (스키마 로드 1회) | **없음** — `transmit` 에 위임 | 없음 |
| **현재 시각** | 안 봄 | 안 봄 | `Clock` 주입 | 안 봄 |
| **가변 상태** | 없음 | 컴파일된 스키마 캐시 | 있음 (연결 하나의 상태) | 없음 — 전이는 순수 함수 |
| **런타임 의존** | Jackson | Jackson + `json-schema-validator` | 위 + `kotlinx-coroutines-core` | 없음 |

### L1 — 코덱은 I/O 를 모릅니다

`OcppFrameCodec` 은 문자열과 프레임 사이를 옮기기만 합니다. 어떤 전송에도 붙습니다.

```kotlin
val codec = OcppFrameCodec()

when (val outcome = codec.decode(text)) {
    is DecodeOutcome.Decoded   -> handle(outcome.frame)
    is DecodeOutcome.Ignored   -> {}                        // 표에 없는 타입 — 메시지 전체를 무시합니다
    is DecodeOutcome.Malformed -> respondCallError(outcome)  // errorCode 가 실려 옵니다
}

val line: String = codec.encode(OcppFrame.Call(messageId, "Heartbeat", payload))
```

**던지지 않고 값으로 답합니다.** 깨진 프레임은 예외가 아니라 `DecodeOutcome.Malformed` 이고,
거기에 OCPP-J 가 요구하는 `RpcErrorCode` 가 실려 옵니다 (Part 4 §4.2.3).
`Ignored` 가 따로 있는 것은 2.1 부터 **모르는 메시지 타입에 CALLERROR 로 답하지 않기**
때문입니다 (errata 2026-06 §4.1/§4.3) — 나중에 OCPP 가 타입을 추가해도 깨지지 않게.

### L2 — 스키마 검증도 순수 함수입니다

```kotlin
val validator = OcppPayloadValidator()          // 181개 스키마를 컴파일해 캐시합니다
when (val v = validator.validateCall("BootNotification", payload)) {
    PayloadValidation.Valid      -> …
    is PayloadValidation.Invalid -> v.violations   // 위반 전량. 대표 errorCode 도 함께
    else                         -> …             // NotApplicable — 아래 참고
}
```

**모르는 action 은 `NotApplicable` 이 아니라 `Invalid` 입니다** — `errorCode` 에
`NotImplemented` 가 실립니다. Part 4 §4.3 이 *"Requested Action is not known by receiver"* 에
그 코드를 요구하기 때문입니다. `NotApplicable` 은 **대응 스키마가 아예 없는 프레임 종류**
(CALLERROR 등)에만 나옵니다.

⚠️ **인스턴스를 공유하세요.** 세션마다 새로 만들면 181개 스키마를 세션 수만큼 다시 파싱합니다.
`OcppFrameCodec` 은 상태가 없어 공유해도 되고 새로 만들어도 됩니다.

### L3 — 세션은 코루틴 전용입니다

이 층은 OCPP-J Part 4 §4.1 의 요구를 지킵니다: 응답 상관, 연결당 in-flight CALL 하나(SHALL NOT),
타임아웃, 재전송 멱등. **그 대가로 코루틴을 요구합니다.**

```kotlin
val session = OcppSession(
    stationId = "CS001",
    transmit  = { text -> withContext(Dispatchers.IO) { socket.send(text) } },  // ← 전송은 여러분이
    onCall    = { id, call -> router.handle(id, call) },                        //   suspend (String) -> Unit
    eventSink = eventSink,
    ledger    = ledger,          // stationId 키 — 세션보다 오래 삽니다
    serializer = serializer,     // 〃
    clock     = clock,           // 벽시계를 직접 보지 않습니다
)

socket.onText { text -> scope.launch { session.receive(text) } }
val result: OcppResult = session.call(OcppCall("RequestBatterySwap", payload))
```

공개 API 는 넷입니다 — `suspend call()` · `suspend send()` · `suspend receive()` · `close()`.
`call()` 은 **예외를 던지지 않습니다.** 타임아웃도 연결 끊김도 전부 `OcppResult` 값입니다.

**전송 SPI 인터페이스를 두지 않았습니다.** 함수 하나를 받습니다 — 구현체가 하나뿐인
인터페이스는 확장이 아니라 부채이기 때문입니다 (설계 원칙).

### D — 도메인은 순수 상태머신입니다

```kotlin
val transition: SwapTransition = SwapStateMachine.transition(state, event)
val rebuilt:   SwapTransaction = SwapStateMachine.replay(initial, events)   // 로그로 재구성
```

`replay` 가 있다는 것이 감사(`auditTest`)의 근거입니다 — 이벤트 로그에서 재구성한 상태를
인메모리 레지스트리와 대조합니다 (이벤트 로그).

---

## 3. 세션 계층을 쓸 때 여러분이 떠안는 것

`OcppSession` 은 **연결 하나**를 나타냅니다. 연결 밖의 일은 하지 않습니다.

| 여러분의 몫 | 왜 세션 밖인가 |
|---|---|
| WebSocket 수립 · subprotocol 협상 · TLS · 인증 | 전송의 일입니다. `csms` 의 `OcppWebSocketHandler` 가 참조 구현입니다 |
| 재접속 · 백오프 | 세션은 재접속을 시도하지 않습니다. 새 연결에는 새 세션을 만듭니다 |
| 코루틴 스코프와 생명주기 | `close()` 는 부릅니다만, 스코프는 여러분이 소유합니다 |
| 동시 송신 직렬화 | `transmit` 이 스레드 안전해야 합니다. `csms` 는 `ConcurrentWebSocketSessionDecorator` 를 씁니다 |
| `ledger` · `serializer` 를 **세션보다 오래 살리기** | 둘 다 키가 `stationId` 입니다. 재접속으로 세션이 바뀌어도 멱등이 이어지려면 밖에서 보관해야 합니다 (실패 시나리오 F6) |

---

## 4. Java 에서 쓸 수 있습니까 — 실측 (2026-08-20)

**짐작하지 않고 재 봤습니다.** `java-compat` 모듈에 **Java 로만 쓴 시험 13건**이 있고,
`./gradlew build` 에서 함께 돕니다. 그 모듈에는 Kotlin 소스가 한 줄도 없습니다 —
있으면 `checkNoKotlinSources` 가 빌드를 깨뜨립니다(게이트가 실제로 빨개지는 것을 확인했습니다).

| 층 | Java 에서 | 판정 근거 |
|---|---|---|
| **L1 프레이밍** | ✅ **된다** | `CodecFromJavaTest` — Java 에서 인코딩·디코딩·왕복 |
| **L2 스키마 검증** | ✅ **된다** | `SchemaValidationFromJavaTest` — 공식 스키마 181개 그대로 |
| **L3 세션** | ❌ **안 된다** | `SessionIsKotlinOnlyTest` — 아래 |

### L1·L2 에서 부딪힌 마찰 네 가지 (전부 우회 가능)

1. **기본 인자가 안 보입니다.** Kotlin 의 `OcppFrameCodec()` 을 Java 는
   `new OcppFrameCodec(new ObjectMapper())` 로 씁니다. `validate(frame)` 도 `validate(frame, null)`.
2. **enum 상수는 이름 그대로입니다.** `RpcErrorCode.RpcFrameworkError` — UPPER_SNAKE 가 아닙니다.
3. **`data object` 는 싱글턴입니다.** `PayloadValidation.Valid.INSTANCE` 로 비교합니다.
4. **`sealed interface` 는 마찰이 아닙니다.** Java 에서 그냥 인터페이스이고, JDK 17 의
   `instanceof` 패턴이 그대로 듣습니다. 결말이 값으로 오는 설계라 `try/catch` 도 필요 없습니다.

```java
OcppFrameCodec codec = new OcppFrameCodec(new ObjectMapper());
DecodeOutcome outcome = codec.decode(line);
if (outcome instanceof DecodeOutcome.Decoded decoded) { … }
```

### L3 는 Java 에서 쓸 수 없습니다 — 벽은 코루틴이 아니었습니다

Java 로 세션을 세우는 시험을 쓰려다 **컴파일 단계에서 막혔습니다.** 이유는 `suspend` 앞에 있었습니다:

- `OcppSession` 의 **모든 생성자가 `DefaultConstructorMarker` 를 요구합니다.** 기본 인자 때문에
  컴파일러 내부용 생성자만 노출되고, Java 가 부를 수 있는 공개 생성자가 **하나도 없습니다.**
- `DEFAULT_CALL_TIMEOUT` 의 접근자 이름이 `getDEFAULT_CALL_TIMEOUT-UwyO8pc` 로 **맹글링**돼
  있습니다. `kotlin.time.Duration` 이 value class 라서입니다. **하이픈은 Java 식별자에 못 씁니다.**

그러니 `suspend` 를 `Continuation` 으로 푸는 문제까지 갈 것도 없습니다. **막는 것은 생성자입니다.**

`SessionIsKotlinOnlyTest` 는 이 사실을 **고정합니다.** 언젠가 `@JvmOverloads` 나
`java.time.Duration` 오버로드로 벽이 사라지면 그 시험이 빨개지고, 그때 고칠 것은 코드가 아니라
**이 절의 판정**입니다.

### 그래서 라이브러리 방향을 바꾸는가 — 바꾸지 않습니다

이 결과가 아프지 않은 이유는 **층을 이미 갈라 두었기 때문**입니다. Java 소비자가 원하는 것은
대개 **코덱과 스키마 검증**(JVM 진영에 2.1 코덱이 사실상 없다는 것이 배포 근거입니다 —
배포 계획은 B07 입니다). 그 층은 **열려 있습니다.**

세션 층까지 Java 에 열려면 방법은 있습니다 — 생성자에 `@JvmOverloads`, `callTimeout` 을
`java.time.Duration` 으로 받는 오버로드, `suspend` 를 감싼 블로킹 파사드. **지금 하지 않습니다.**
소비자가 없는 상태에서 공개 API 를 넓히면 되돌릴 때 breaking change 가 되고,
그 값은 실제 요구가 나타난 뒤에 재는 게 맞습니다. 트리거는 **B29** 와 같습니다.

## 5. 왜 이렇게 갈랐는가

- **전송을 나중에 바꿀 수 있게.** 코덱과 세션이 WebSocket 을 몰라야 테스트 하네스든 다른
  전송이든 같은 코드가 돕니다.
- **테스트가 실제 시간을 기다리지 않게.** 벽시계 금지가 `checkNoFrameworkImports` 에 박혀
  있는 이유입니다. 타임아웃 시험이 가상 시간에서 즉시 끝납니다.
- **도메인이 프로토콜에 오염되지 않게.** `swap-domain` 은 OCPP 를 모릅니다. 그래서 상태머신
  시험이 JSON 도 네트워크도 없이 돕니다.

세션 계층을 코루틴에서 떼어내 완전한 Sans-I/O 로 만드는 안은 검토했으나 **지금은 하지
않습니다** — 소비자가 없고, 코덱·스키마 층은 이미 그 성질을 갖고 있습니다.
트리거는 *"라이브러리 소비자가 생기거나 시험 도구를 독립 제품으로 밀 때"* 입니다 —
지금 하지 않는 이유는 소비자가 없는 상태에서의 추상화는 추측이기 때문입니다.
