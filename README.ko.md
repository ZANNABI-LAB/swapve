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

**아직 Maven Central 에 배포되지 않았습니다.** 지금은 소스로 씁니다 ([B07](BACKLOG.md)).

## 빠른 시작

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
station-sim → ws://localhost:8080/ocpp/CS001 (순서 In-Out, 배터리 2 개)
교환 완주: requestId=1001, 오간 메시지 42 건
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

역순 교환(`Out-In`)도 표준이고, 그대로 돕니다 ([PLAN §4.6](docs/PLAN.md)):

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

`localhost:8090` 에서 **붙이기 → 교환 시작**. **F1~F6 버튼**은 [PLAN §5.4](docs/PLAN.md) 의
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
<summary><b>실측 기록</b> — 위 절차를 실제로 따라 해서 잰 시간 (2026-08-18)</summary>

| 단계 | 걸린 시간 | 관측한 것 |
|---|---|---|
| `./gradlew build` | 54s ~ 1m25s | 시험 279건 + 모듈 경계 검증 3종 통과 |
| `./gradlew :csms:bootRun` | 명령 후 ~20s (서버 자체 기동은 2.8s) | `Started CsmsApplicationKt` · `Tomcat started on port 8080` |
| `./gradlew :station-sim:run …` | 13s | `교환 완주: requestId=1001, 오간 메시지 42 건` |
| CSMS 쪽 확인 | — | `BatteryIn → HalfIn`, `BatteryOut → Completed` |

**Gradle 의존성이 이미 받아져 있는 상태에서 잰 값입니다.** 처음 클론한 뒤 첫 `./gradlew build`
는 Gradle 배포판과 의존성을 내려받는 시간이 더 붙습니다. 그 부분을 빼면 **3분 안쪽**입니다.

`curl` 세 줄도 별도 환경에서 실행해 확인했습니다:

- `/api/swaps/CS001:1001` → `status: COMPLETED`, `batteriesIn` 2개(SoC 12·13%)와
  `batteriesOut` 2개(SoC 95%)가 **양쪽 다** 실립니다 ([PLAN §11.2](docs/PLAN.md))
- `/api/metrics/swaps` → `successRate: 1.0`, 소요시간 백분위, `failures.byScenario` 가
  F1·F2·F3·F5 로 구분 (F4·F6 은 멱등이라 실패로 세지 않습니다)
- `/api/stations/CS001/charging-transactions` → 슬롯별 충전 트랜잭션

같은 엔드포인트를 실제 Spring 컨텍스트에서 호출하는 `SwapApiTest` · `SwapMetricsApiTest` ·
`ChargingApiTest` 가 매 빌드마다 이를 다시 확인합니다.
</details>

## 무엇이 아닌가

**프로덕션 시스템이 아닙니다.** 없는 것을 먼저 적습니다.

- **닫힌 경계와 남은 경계가 다릅니다.** WebSocket 은 기본 `BASIC`, REST 는 별도 Basic,
  `sim-console` 은 loopback 바인딩입니다. mTLS(B12)·자격증명 회전·운영용 감사·속도 제한은 없습니다.
- **단일 인스턴스입니다.** 수평 확장은 봉쇄하지 않았지만(§11.5 의 `stationId` 직렬화·TSID)
  구현하지 않았습니다 ([B11](BACKLOG.md)). 재시작은 견딥니다 — 이벤트 로그(H2)에 OCPP 원문이
  남고 기동 시 파생 레지스트리를 그 로그에서 복원합니다(`EventLogRecovery`).
  다만 보존 창(복구 7일 · 감사 30일) 밖은 재구성 대상이 아닙니다.
- **스마트차징·요금·로밍·수평 확장은 범위 밖입니다** ([PLAN §3](docs/PLAN.md)).
  구현하지 않았을 뿐 **봉쇄하지도 않았습니다** ([PLAN §11](docs/PLAN.md)).
- **대시보드·UI 가 없습니다.** 조회는 REST 까지입니다.

## 왜 Block S 인가

OCPP 2.1 이 2025년 1월 **Battery Swap 기능 블록(Block S)** 을 정식 편입했습니다.
전동킥보드·전기자전거 같은 2·3륜과 일반 EV 의 배터리 교환을 표준으로 다룹니다.

**"2.1 을 지원한다"는 이 프로젝트의 차별점이 아닙니다.** 재검증(2026-08)에서 2.1 에 손대는
구현이 여럿 확인됐습니다. 우리가 하는 주장은 더 좁습니다 — **Block S 를 실제로 다루는
오픈소스가 확인되지 않습니다.** 서버 측 구현도, Block S 를 걸어 볼 수 있는 시험 도구도 그렇습니다.

| 프로젝트 | 역할 | OCPP 2.1 | Block S |
|---|---|---|---|
| [SteVe](https://github.com/steve-community/steve) | CSMS (Java) | ❌ 1.6J 전용 | ❌ |
| [CitrineOS](https://github.com/citrineos/citrineos) | CSMS (TypeScript) | 로드맵의 "기타 주제"에만 | 언급 없음 |
| [MaEVe](https://github.com/thoughtworks/maeve-csms) | CSMS (Go) | ❌ 1.6J + 2.0.1 | ❌ |
| [EVerest libocpp](https://github.com/EVerest/libocpp) | **충전기 측** 라이브러리 (C++) | "개발 중" | 언급 없음 |
| Solidstudio VCP | **CS 시뮬레이터** | ✅ 지원함 | 확인 안 됨 |
| ocpp-rs | 라이브러리 + 시뮬레이터 (Rust) | ❌ 1.6J / 2.0.1 | ❌ |
| tzi-OCTT | CSMS 검증 pytest 스위트 | ❌ 2.0.1 / 1.6J | — |
| OCTT (공식) | 적합성 시험 도구 — **유료·구독** | ❌ 2.0.1 / 1.6 | — |
| **SwapVe** | **라이브러리 + 시험 도구 + 참조 CSMS** (Kotlin) | ✅ | ✅ |

**❌** 는 미지원으로 확인된 것, **"언급 없음"** 은 문서·이슈에서 찾지 못한 것,
**"확인 안 됨"** 은 판단 근거를 얻지 못한 것입니다 — 뒤의 둘은 *부재의 증거가 아닙니다.*
**반증을 환영합니다.** Block S 메시지(`RequestBatterySwap` · `BatterySwap` 트랜잭션 이벤트)를
다루는 구현을 아신다면 이슈로 알려 주세요.

## 검증 — 게이트 세 개

세 게이트는 **서로 다른 질문에 답합니다.** 그래서 하나로 합치지 않았습니다 ([PLAN §7.3](docs/PLAN.md)).

| 게이트 | 명령 | 무엇을 보장하나 |
|---|---|---|
| **L1 단위** | `./gradlew build` | 프레임 왕복 · **공식 스키마 181개 검증** · 상태머신 불변식 · REST 계약. **모듈 경계 검증 5종**과 **Java 호환 시험 13건**이 함께 돕니다 |
| **L2 적합성** | `./gradlew conformanceTest` | 공식 케이스 **`TC_S_102_CSMS` · `TC_S_103_CSMS` · `TC_S_104_CS`** 와 실패 시나리오 **F1~F6** |
| **L3 부하·감사** | `./gradlew auditTest` | **스테이션 20대 동시 접속** 후 불변식 감사. **이벤트 로그에서 상태를 재구성해** 레지스트리와 대조합니다 |

`auditTest` 는 통과/실패만 찍지 않고 **항목마다 몇 건을 검사했는지** 출력합니다 —
"통과"만 있으면 검사를 안 한 것과 구분되지 않기 때문입니다. 그리고 **감사 자체도 시험받습니다**
(불변식을 일부러 깨뜨린 로그로 각 항목이 빨개지는지 확인).

> 출력 예시 · 성공 기준 S1~S7 · 적합성 케이스 전문 → **[docs/CONFORMANCE.md](docs/CONFORMANCE.md)**
> **OCTT 공식 인증은 받지 않았습니다** — 유료이고 OCA 승인 시험소를 거쳐야 합니다.
> 여기 있는 것은 Part 6 케이스의 **자체 구현**입니다.

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
| [docs/CONFORMANCE.md](docs/CONFORMANCE.md) | 적합성 케이스 · 성공 기준 S1~S7 · 감사 출력 |
| [docs/PUBLISHING.md](docs/PUBLISHING.md) | Maven Central 배포 절차와 그 앞에 두는 리허설 |
| [docs/PLAN.md](docs/PLAN.md) | 프로토콜 명세(§4) · 도메인 설계(§5) · 검증 전략(§7) · 마일스톤 M0~M10(§8). 스펙 원문 대조로 계획서를 정정한 이력이 §0 에 있습니다 |
| [BACKLOG.md](BACKLOG.md) | 범위 밖으로 밀어낸 것들과 그것을 꺼낼 트리거 |

## 개발 방식

[**zannabi-code**](https://github.com/ZANNABI-LAB/zannabi-code) — 검증 우선 외부 러너 — 로
개발합니다. 모든 완료 선언에 기계 검증 가능한 증거를 요구합니다.

```bash
zannabi run "<작업>" --cwd . \
  --gate "unit:./gradlew test" \
  --gate "conformance:./gradlew conformanceTest" \
  --gate "audit:./gradlew auditTest" --budget 3
```

같은 게이트가 [GitHub Actions](.github/workflows/ci.yml) 에서도 그대로 돕니다.

## 라이선스와 스펙

[Apache License 2.0](LICENSE) — 단, `schemas/` 는 예외입니다 ([NOTICE](NOTICE)).

- `schemas/` 에는 **OCA 공식 JSON Schema 가 원문 그대로** 들어 있습니다 (수정하지 않음).
  © Open Charge Alliance, **CC BY-ND 4.0**.
- **스펙 문서(PDF)는 포함되지 않습니다.**
  [openchargealliance.org/download-ocpp](https://openchargealliance.org/download-ocpp/) 에서
  **무료로** 받아 `docs/spec/` 에 두면 됩니다.

<sub>"OCPP" and "Open Charge Point Protocol" are managed by the Open Charge Alliance.
This project is not affiliated with, nor endorsed by, the OCA.</sub>
