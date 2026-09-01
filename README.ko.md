<p align="center">
  <img src="docs/assets/hero.png" alt="SwapVe" width="100%">
</p>

<h1 align="center">SwapVe</h1>

<p align="center">
  <b>OCPP 2.1 Battery Swap(Block S) 라이브러리와 시험 도구 — JVM 용.</b><br>
  코덱·세션 계층, 교환 도메인 모델, 스테이션 시뮬레이터, 그리고 참조 CSMS 구현.
</p>

<p align="center">
  <a href="README.md">English</a> · <b>한국어</b>
</p>

<p align="center">
  <a href="https://github.com/ZANNABI-LAB/swapve/actions/workflows/ci.yml"><img src="https://github.com/ZANNABI-LAB/swapve/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="license"></a>
  <img src="https://img.shields.io/badge/OCPP-2.1%20Edition%202-informational" alt="OCPP 2.1">
  <img src="https://img.shields.io/badge/Kotlin-2.1-7F52FF" alt="Kotlin">
  <img src="https://img.shields.io/badge/JDK-17-orange" alt="JDK 17">
</p>

---

## 무엇을 제공하는가

**네 가지를 따로 가져다 쓸 수 있습니다.** 전부 쓸 필요가 없고, 그렇게 설계돼 있습니다.

| 무엇 | 모듈 | 가져다 쓰는 경우 |
|---|---|---|
| **OCPP 2.1 코덱 · 세션** | `ocpp-core` | OCPP-J 프레임과 **공식 스키마 181개** 검증이 필요할 때. 프레임워크를 모릅니다 |
| **Battery Swap 도메인** | `swap-domain` | 교환 상태머신·슬롯 모델·불변식이 필요할 때. I/O 가 없습니다 |
| **시뮬레이터 + 제어 콘솔** | `station-sim`·`sim-console` | **자기 CSMS 를 Block S 로 시험**할 때. 장애 F1~F6 을 버튼 하나로 겁니다 |
| **참조 CSMS** | `csms` | 위의 것들이 어떻게 맞물리는지 볼 때. 적합성 케이스가 이것을 대상으로 돕니다 |

> **완제품 CSMS 를 약속하지 않습니다.** `csms` 는 제품이 아니라 참조 구현입니다.
> 코덱·스키마 층은 **Java 에서도 호출됩니다** — 세션 층은 Kotlin 전용입니다
> (짐작이 아니라 실측입니다: [LAYERS §4](docs/LAYERS.md)).
>
> **생성된 메시지 DTO 가 없습니다.** 페이로드는 Jackson 의 `JsonNode` / `ObjectNode` 이고,
> 맞고 틀림은 옮겨 적은 클래스가 아니라 **공식 스키마**가 판정합니다. 실수가 아니라 라이선스의
> 결과입니다 — OCA 스키마는 CC BY-ND 4.0 이라 코드로 옮기면 파생물이 됩니다. 표준과 어긋날 수
> 없는 검증기를 얻는 대신, 필드를 손으로 읽고 쌓는 값을 치릅니다.

**Maven Central 에 있습니다.** 둘 중 필요한 것만 가져가면 됩니다.

```kotlin
dependencies {
    implementation("io.github.zannabi-lab:ocpp-core:0.3.0")   // 코덱 · 스키마 검증 · 세션
    implementation("io.github.zannabi-lab:swap-domain:0.3.0") // 교환 상태머신, 의존성 0
}
```

<sub>판 사이의 변경은 [CHANGELOG.md](CHANGELOG.md) 에 있습니다. 주 버전이 `0` 인 동안에는
부 버전 사이에도 공개 API 가 바뀔 수 있습니다 — 이 저장소 밖의 소비자가 그 모양을 아직
시험해 보지 않았기 때문입니다.</sub>

**쓰는 모습은 이렇습니다.** 프레이밍 · 스키마 검증 · 타임아웃 · Part 4 §4.1.1 의
"연결당 CALL 하나" 규칙은 라이브러리가 갖습니다. 넘겨줄 것은 전송 함수와 핸들러입니다.

```kotlin
val sessions = OcppSessions(Clock.systemUTC(), eventSink = myEventLog)

// 세션은 연결 하나당 하나. 재접속하면 같은 스테이션으로 open() 을 다시 부릅니다 —
// 멱등 원장이 그대로 이어져서, 재전송된 교환이 두 번 세어지는 것을 막습니다.
val session = sessions.open(
    stationId = "CS001",
    transmit = { line ->
        if (socket.isOpen) { socket.send(line); TransmitOutcome.Delivered }
        else TransmitOutcome.Gone("socket closed")
    },
    onCall = { stationId, call -> InboundResponse.Respond(answerFor(call)) },
)

// 들어온 줄은 도착 순서대로:  session.receive(line)

when (val result = session.call(OcppCall("RequestBatterySwap", payload))) {
    is OcppResult.Accepted -> result.payload
    is OcppResult.Rejected -> result.knownErrorCode
    is OcppResult.InvalidResponse,
    is OcppResult.TimedOut,
    is OcppResult.NotConnected -> null   // 던지지 않습니다 — 모든 결말이 값입니다
}
```

연결을 맺고, 끊기면 다시 붙이고, `receive` 를 도착 순서대로 부르는 것은 소비자 몫입니다.
**코덱 · 스키마 층은 Java 에서 부를 수 있고, 그 위의 세션 층은 Kotlin 전용입니다**(코루틴).
`swap-domain` 은 의존성이 아예 없지만 식별자가 Kotlin `value class` 라 Java 에서는
`constructor-impl` 형태로 맹글링돼 보입니다 — **Kotlin 소비자를 위해 쓰였습니다.**
→ [docs/LAYERS.md](docs/LAYERS.md) §4

## 빠른 시작

태그가 붙은 릴리스마다 **`swapve-<버전>.zip`** 이 함께 올라갑니다. CSMS·시뮬레이터·콘솔이
이미 빌드된 채로 들어 있고, JDK 17 만 있으면 됩니다.

```bash
unzip swapve-0.3.0.zip && cd swapve-0.3.0
java -jar csms/csms.jar --csms.security.profile=NONE   # 터미널 A
./station-sim/bin/station-sim --station-id CS001       # 터미널 B — 교환 1건을 완주하고 끝난다
./sim-console/bin/sim-console                          # 선택 — 브라우저에서 조종한다
```

소스에서 직접 빌드하려면 아래를 따릅니다.

필요한 것은 **JDK 17 과 git 뿐**입니다. Gradle 은 래퍼가 받아옵니다.

```bash
git clone https://github.com/ZANNABI-LAB/swapve.git && cd swapve
./gradlew build          # 전체 시험 + 모듈 경계 검증
```

**터미널 A** — 관제 서버. `Started CsmsApplicationKt` 가 보이면 준비된 것입니다.

```bash
./gradlew :csms:bootRun --args="--csms.security.profile=NONE --csms.api.security.enabled=false"
```

**터미널 B** — 시뮬레이터가 붙어 **교환 1건을 완주**하고 끝납니다.

```bash
./gradlew :station-sim:run --args="--csms-url ws://localhost:8080/ocpp --station-id CS001 --request-id 1001"
```

```
station-sim → ws://localhost:8080/ocpp/CS001 (order In-Out, 2 batteries)
Exchange complete: requestId=1001, 42 messages exchanged
```

**터미널 C** — 앱이 보게 될 것을 그대로 조회합니다.

```bash
curl localhost:8080/api/swaps/CS001:1001      # 교환 1건 — 양쪽 배터리의 SoC/SoH
curl localhost:8080/api/metrics/swaps         # 성공률 · 소요시간 · 실패 사유
curl localhost:8080/api/stations/CS001/charging-transactions   # 들어온 배터리의 충전 (S04)
```

> 위 절차는 로컬 데모라 인증을 둘 다 낮춰 띄웁니다. **기본 운영값은 WebSocket `BASIC` +
> REST Basic** 입니다 → [docs/CONFIGURATION.md](docs/CONFIGURATION.md)

<details>
<summary><b>역순 교환 · 앱처럼 CSMS 가 시작하기(S02) · 제어 콘솔</b></summary>

역순 교환(`Out-In`)도 표준이고, 그대로 돕니다 (교환 순서):

```bash
./gradlew :station-sim:run --args="--csms-url ws://localhost:8080/ocpp --station-id CS001 --swap-order Out-In --request-id 1002"
```

**앱처럼 CSMS 쪽에서 교환을 시작하려면** (표준 유즈케이스 S02) 시뮬레이터를 대기 모드로
띄운 뒤 REST 로 겁니다. 상관 번호(`requestId`)는 이때 CSMS 가 발번하고, 스테이션이 그것을
그대로 승계합니다(S02.FR.02).

```bash
# 터미널 B — 스스로 시작하지 않고 RequestBatterySwap 을 기다린다
./gradlew :station-sim:run --args="--csms-url ws://localhost:8080/ocpp --station-id CS001 --remote-start"

# 터미널 C — 앱이 QR 을 찍은 셈
curl -X POST localhost:8080/api/swaps -H 'Content-Type: application/json' \
     -d '{"stationId":"CS001","idToken":{"idToken":"RFID-0001","type":"ISO14443"}}'
```

**화면으로 조종하려면** 제어 콘솔을 띄웁니다. CLI 를 **대체하지 않고 얹는 것**입니다:

```bash
./gradlew :sim-console:run --args="--port 8090 --csms-url ws://localhost:8080/ocpp"
```

`localhost:8090` 에서 **붙이기 → 교환 시작**. **F1~F6 버튼**은 실패 시나리오 의
실패 시나리오를 그대로 겁니다 — "배터리 부족을 눌러서 재현"이 버튼 하나입니다.

- 화면은 **정적 HTML 한 장**이고 외부 CDN·폰트·프레임워크를 링크하지 않습니다. HTTP 서버도
  JDK 내장 `com.sun.net.httpserver` 입니다 — **네트워크 없는 곳에서도 뜹니다**
- **F1(배터리 부족)은 개시 주체가 CSMS 인 시나리오라**(S02.FR.04) 콘솔이 대기 상태로
  들어갑니다. 화면에 뜨는 `curl` 한 줄을 그대로 치면 거부 사유가 화면에 남습니다
- 제어 API 도 있습니다 — `POST /api/stations` · `POST /api/stations/{id}/swap`
  (본문 `{"fault":"F3"}` 으로 장애 주입) · `DELETE /api/stations/{id}` · `GET /api/state`

콘솔은 **시험계를 조종하는 화면**이지 관제 서버가 아닙니다.
</details>

<details>
<summary><b>실측 기록</b> — 위 절차를 실제로 따라 해서 잰 시간 (2026-08-21)</summary>

| 단계 | 걸린 시간 | 관측한 것 |
|---|---|---|
| `./gradlew build` | 54s ~ 1m25s | 시험 368건 + 모듈 경계 검증 5종 통과 |
| `./gradlew :csms:bootRun` | 명령 후 ~20s (서버 자체 기동은 2.8s) | `Started CsmsApplicationKt` · `Tomcat started on port 8080` |
| `./gradlew :station-sim:run …` | 13s | `Exchange complete: requestId=1001, 42 messages exchanged` |
| CSMS 쪽 확인 | — | `BatteryIn → HalfIn`, `BatteryOut → Completed` |

**Gradle 의존성이 이미 받아져 있는 상태에서 잰 값입니다.** 처음 클론한 뒤 첫 `./gradlew build`
는 Gradle 배포판과 의존성을 내려받는 시간이 더 붙습니다. 그 부분을 빼면 **3분 안쪽**입니다.

`curl` 세 줄도 별도 환경에서 실행해 확인했습니다:

- `/api/swaps/CS001:1001` → `status: COMPLETED`, `batteriesIn` 2개(SoC 12·13%)와
  `batteriesOut` 2개(SoC 95%)가 **양쪽 다** 실립니다 (과금 여지)
- `/api/metrics/swaps` → `successRate: 1.0`, 소요시간 백분위, `failures.byScenario` 가
  F1·F2·F3·F5 로 구분 (F4·F6 은 멱등이라 실패로 세지 않습니다)
- `/api/stations/CS001/charging-transactions` → 슬롯별 충전 트랜잭션

같은 엔드포인트를 실제 Spring 컨텍스트에서 호출하는 `SwapApiTest` · `SwapMetricsApiTest` ·
`ChargingApiTest` 가 매 빌드마다 이를 다시 확인합니다.
</details>

## 무엇이 아닌가

**프로덕션 시스템이 아닙니다.** 없는 것을 먼저 적습니다.

- **닫힌 경계와 남은 경계가 다릅니다.** WebSocket 은 기본 `BASIC`, REST 는 별도 Basic,
  `sim-console` 은 loopback 바인딩입니다. mTLS·자격증명 회전·운영용 감사·속도 제한은 없습니다.
- **단일 인스턴스입니다.** 수평 확장은 봉쇄하지 않았습니다 — 직렬화와 멱등 원장이 모두
  `stationId` 로 잡혀 있고, 그것이 분산 시 파티셔닝 키가 됩니다. 다만 구현하지는
  않았습니다. 재시작은 견딥니다 — 이벤트 로그(H2)에 OCPP 원문이
  남고 기동 시 파생 레지스트리를 그 로그에서 복원합니다(`EventLogRecovery`).
  다만 보존 창(복구 7일 · 감사 30일) 밖은 재구성 대상이 아닙니다.
- **스마트차징·요금·로밍·수평 확장은 범위 밖입니다** (범위).
  구현하지 않았을 뿐 **봉쇄하지도 않았습니다** (확장 여지).
- **대시보드·UI 가 없습니다.** 조회는 REST 까지입니다.

## 왜 Block S 인가

OCPP 2.1 이 2025년 1월 **Battery Swap 기능 블록(Block S)** 을 정식 편입했습니다.
전동킥보드·전기자전거 같은 2·3륜과 일반 EV 의 배터리 교환을 표준으로 다룹니다.

**"2.1 을 지원한다"는 이 프로젝트의 차별점이 아닙니다.** 재검증(2026-08)에서 2.1 에 손대는
구현이 여럿 확인됐고, 그중 하나가 Block S 를 갖고 있었습니다. 우리가 하는 주장은 전보다 좁습니다
— **서버 측 Block S 구현은 오픈소스로 하나 존재하고, 스테이션 측 구현과 Block S 를 걸어 볼 수
있는 시험 도구는 이것 말고 확인되지 않습니다.**

| 프로젝트 | 역할 | OCPP 2.1 | Block S |
|---|---|---|---|
| [SteVe](https://github.com/steve-community/steve) | CSMS (Java) | ❌ 1.6J 전용 | ❌ |
| [CitrineOS](https://github.com/citrineos/citrineos) | CSMS (TypeScript) | 로드맵의 "기타 주제"에만 | 언급 없음 |
| [MaEVe](https://github.com/thoughtworks/maeve-csms) | CSMS (Go) | ❌ 1.6J + 2.0.1 | ❌ |
| [EVerest libocpp](https://github.com/EVerest/libocpp) | **충전기 측** 라이브러리 (C++) | "개발 중" | 언급 없음 |
| [Java-OCA-OCPP](https://github.com/ChargeTimeEU/Java-OCA-OCPP) | 라이브러리 (Java) | ✅ 2.1 트리 | ✅ **서버 측**, 문서에 없고 배포물에도 없음 |
| Solidstudio VCP | **CS 시뮬레이터** | ✅ 지원함 | 확인 안 됨 |
| ocpp-rs | 라이브러리 + 시뮬레이터 (Rust) | ❌ 1.6J / 2.0.1 | ❌ |
| tzi-OCTT | CSMS 검증 pytest 스위트 | ❌ 2.0.1 / 1.6J | — |
| OCTT (공식) | 적합성 시험 도구 — **유료·구독** | ❌ 2.0.1 / 1.6 | — |
| **SwapVe** | **라이브러리 + 시험 도구 + 참조 CSMS** (Kotlin) | ✅ | ✅ |

**❌** 는 미지원으로 확인된 것, **"언급 없음"** 은 문서·이슈에서 찾지 못한 것,
**"확인 안 됨"** 은 판단 근거를 얻지 못한 것입니다 — 뒤의 둘은 *부재의 증거가 아닙니다.*
Java 행이 바로 그 경고가 현실이 된 자리입니다. 그 구현의 Battery Swapping 모듈은 어느 README
에도 없고 배포된 아티팩트에도 없습니다. 소스 트리를 읽어 찾았고, 서버로 컴파일해 교환을 걸어
확인했습니다([CONFORMANCE.md](docs/CONFORMANCE.md#limits-of-self-verification)).
**서버 측을 처리합니다. 스테이션도 아니고, Block S 를 걸어 볼 시험 도구도 아닙니다.**
**반증을 환영합니다.** Block S 메시지(`RequestBatterySwap` · `BatterySwap` 트랜잭션 이벤트)를
다루는 구현을 아신다면 이슈로 알려 주세요.

## 검증 — 게이트 세 개

세 게이트는 **서로 다른 질문에 답합니다.** 그래서 하나로 합치지 않았습니다 (게이트).

| 게이트 | 명령 | 무엇을 보장하나 |
|---|---|---|
| **L1 단위** | `./gradlew build` | 프레임 왕복 · **공식 스키마 181개 검증** · 상태머신 불변식 · REST 계약. **모듈 경계 검증 5종**과 **Java 호환 시험 13건**이 함께 돕니다 |
| **L2 적합성** | `./gradlew conformanceTest` | 공식 케이스 **`TC_S_102_CSMS` · `TC_S_103_CSMS` · `TC_S_104_CS`** 와 실패 시나리오 **F1~F6** |
| **L3 부하·감사** | `./gradlew auditTest` | **스테이션 20대 동시 접속** 후 불변식 감사. **이벤트 로그에서 상태를 재구성해** 레지스트리와 대조합니다 |

`auditTest` 는 통과/실패만 찍지 않고 **항목마다 몇 건을 검사했는지** 출력합니다 —
"통과"만 있으면 검사를 안 한 것과 구분되지 않기 때문입니다. 그리고 **감사 자체도 시험받습니다**
(불변식을 일부러 깨뜨린 로그로 각 항목이 빨개지는지 확인).

> 출력 예시 · 성공 기준 S1~S7 · 적합성 케이스 전문 → **[docs/CONFORMANCE.md](docs/CONFORMANCE.md)**
> **OCTT 공식 인증은 받지 않았고, OCPP 2.1 은 아직 신청할 제도 자체가 없습니다** —
> OCA 의 인증 프로그램과 OCTT 시험 도구는 오늘 기준 **2.0.1 과 1.6** 을 다루고 2.1 지원은
> 향후 과제로 적혀 있습니다. 여기 있는 것은 Part 6 케이스의 **자체 구현**입니다.

**남의 서버를 상대로 시험했습니다.** 시뮬레이터를 **공개 구현 세 곳**에 붙였고, 그중 하나는
OCPP 2.0.1 로 OCA 인증을 받은 구현입니다(그 인증서는 특정 2.0.1 릴리스를 덮고, 여기서 붙은
것은 그 구현의 `latest` 입니다). 핸드셰이크 · 서브프로토콜 협상 · `BootNotification` ·
`NotifyEvent` · `TransactionEvent` · 메시지 상관이 모두 성립했고, 이쪽에서 낸 CALLERROR 를 상대가
제대로 읽었습니다. **그 과정에서 이 저장소의 결함 셋을 찾았습니다** — 셋 다 배포 모듈 밖이었습니다.

**Block S 를 이제 남의 핸들러가 받았습니다.** 세 번째 상대는 배포된 CSMS 가 아니라 JVM
라이브러리이고, 그 Battery Swapping 모듈은 문서에도 배포물에도 없습니다 — 그래도 다른 저자가 쓴,
실제 요청 핸들러를 갖춘 Block S 구현입니다. 로컬 서버로 컴파일해 붙이자 **스테이션이 개시한 교환과
CSMS 가 개시한 교환이 모두 끝까지** 돌았고, 상대가 발번한 `requestId` 를 승계해 상관시켰으며,
응답을 붙잡자 이쪽 CALL 타임아웃이 발동했고, 교환 도중 재접속한 뒤의 재전송에도 응답했습니다.
마지막 실행은 심판의 한계도 함께 보여 줬습니다 — 같은 `messageId` 에 응용 핸들러가 **두 번**
불렸습니다. `InboundCallLedger` 가 존재하는 이유가 정확히 그 경우입니다.

> 상호운용 시험이 덮은 것과 멈춘 자리 →
> **[docs/CONFORMANCE.md](docs/CONFORMANCE.md) § Limits of self-verification**

## 구성

```
swapve/
├─ ocpp-core/      OCPP-J 프레이밍 · 공식 스키마 검증 · 세션 계층   (프레임워크 무관)
├─ swap-domain/    Battery Swap 도메인 · 교환 상태머신 · 슬롯 모델  (I/O 없음)
├─ csms/           관제 서버 — WebSocket · REST API · 지표
├─ station-sim/    스테이션 시뮬레이터 — 슬롯 · 배터리 · 장애 주입      (의존성 0)
├─ sim-console/    시뮬레이터 제어 콘솔 — 데모용 화면 + 제어 API      (JDK 내장 HTTP)
└─ java-compat/    Java 호환 게이트 — 코덱·스키마 층을 Java 로 호출       (시험만)
```

`ocpp-core` 와 `swap-domain` 은 프레임워크를 모릅니다. **그 경계는 주석이 아니라 빌드
검사입니다** — 다섯 개의 `check*` 태스크가 `./gradlew build` 에서 함께 돕니다.
의존 방향은 `sim-console → station-sim → (ocpp-core, swap-domain)` 한 방향이고,
**`csms` 는 이 사슬에 없습니다** — 관제 서버가 스테이션을 조종하는 의존을 만들지 않기 위해서입니다.

## REST API

**읽기 전용 운영 화면이 `/` 에 뜹니다.** 이 CSMS 가 아는 스테이션과, 각각과 오간 프레임을
원문 그대로 보여 줍니다. 시뮬레이터 콘솔과 같은 규칙의 정적 페이지 한 장이며 CDN 도
프레임워크도 링크하지 않습니다. 자세한 것은 [docs/API.md](docs/API.md) 에 있습니다.

<sub>화면 자체는 인증 밖이지만 화면이 읽는 것은 전부 인증 뒤입니다. 기본 설정은
`csms.api.security.enabled: true` 에 사용자가 비어 있어 **모든** `/api` 요청이 거절됩니다 —
`csms.api.security.users` 를 채우거나, 로컬에서 둘러보려면
`--csms.api.security.enabled=false` 로 띄우십시오. 화면이 401 만 보여 주지 않고 그 사실을
직접 말합니다.</sub>

표준이 앱 시나리오를 이미 정의해 두었습니다 — **S02**: *"EV Driver requests CSMS to initiate a
battery swap **via a smartphone app, e.g. by scanning a QR code**"*. 즉
`앱 → CSMS → RequestBatterySwap → 스테이션` 이 표준 유즈케이스입니다. 앱 구현은 범위 밖이지만
**그 계약은 설계했습니다.**

| 메서드 | 경로 | 하는 일 |
|---|---|---|
| `POST` | `/api/swaps` | 교환 시작 — `RequestBatterySwapRequest` 발사 (S02) |
| `GET` | `/api/swaps/{id}` | 진행 상태 · **양쪽 배터리의 SoC/SoH** · 장부 불균형 |
| `GET` | `/api/metrics/swaps` | 성공률 · 소요시간 분포 · **F1~F6별 실패 사유** |
| `GET` | `/api/stations/{id}/charging-transactions` | 들어온 배터리의 충전 (S04) |

전문과 설계 근거(배터리 부족은 왜 5xx 가 아니라 200 인가, 지표에 왜 Micrometer 를 쓰지
않았는가)는 **[docs/API.md](docs/API.md)** 에 있습니다.

## 문서

| 문서 | 내용 |
|---|---|
| [docs/LAYERS.md](docs/LAYERS.md) | **층 경계** — 코덱은 I/O 무관, 세션은 코루틴 전용. 라이브러리로 쓸 때 떠안는 것 |
| [docs/API.md](docs/API.md) | REST 계약 전문과 설계 근거 |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | 스테이션 인증 · REST 인증 · TLS |
| [docs/CONFORMANCE.md](docs/CONFORMANCE.md) | 적합성 케이스 · 성공 기준 S1~S7 · 감사 출력 · 자체 검증의 한계 |
| [docs/PUBLISHING.md](docs/PUBLISHING.md) | Maven Central 배포 절차와 그 앞에 두는 리허설 |

> `docs/` 의 문서는 **영문 단일본**입니다. 1,100줄을 두 언어로 유지하면 반드시 어긋나기
> 때문에 일부러 그렇게 두었습니다. 두 언어로 유지하는 것은 이 README 한 장입니다.

## 개발 방식

설명만으로 완료를 선언하지 않습니다. **게이트 여섯 개가 곧 완료의 정의**이고,
[GitHub Actions](.github/workflows/ci.yml) 에서 푸시마다 전부 돕니다. 한 줄로 합치지
않습니다 — 합치면 "단위는 통과했는데 적합성이 깨졌다"가 초록 체크 하나 뒤에 숨습니다.

```bash
./gradlew --no-build-cache :csms:cleanTest build   # L1  단위 + 모듈 경계 검증
./gradlew --no-build-cache conformanceTest         # L2  TC_S_102/103_CSMS + 실패 F1~F6
./gradlew --no-build-cache auditTest               # L3  스테이션 20대 동시 → 불변식 감사
```

`--no-build-cache` 는 장식이 아닙니다. 출력을 지워도 **빌드 캐시가 복원**하기 때문에,
시험을 실제로 돌리지 않은 게이트가 초록으로 끝납니다. 근거가 된 실측은
[ci.yml](.github/workflows/ci.yml) 머리에 적혀 있습니다.

게이트가 닿지 못하는 곳은 짐작에 맡기지 않고 적어 둡니다 —
[docs/CONFORMANCE.md](docs/CONFORMANCE.md) 의 "자체 검증의 한계" 절입니다.

## 라이선스와 스펙

[Apache License 2.0](LICENSE) — 단, `schemas/` 는 예외입니다 ([NOTICE](NOTICE)).

- `schemas/` 에는 **OCA 공식 JSON Schema 가 원문 그대로** 들어 있습니다 (수정하지 않음).
  © Open Charge Alliance, **CC BY-ND 4.0**.
- **스펙 문서(PDF)는 포함되지 않습니다.**
  [openchargealliance.org/download-ocpp](https://openchargealliance.org/download-ocpp/) 에서
  **무료로** 받아 `docs/spec/` 에 두면 됩니다.

<sub>"OCPP" and "Open Charge Point Protocol" are managed by the Open Charge Alliance.
This project is not affiliated with, nor endorsed by, the OCA.</sub>
