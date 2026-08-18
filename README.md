<p align="center">
  <img src="docs/assets/hero.png" alt="SwapVe — OCPP 2.1 battery swapping server" width="100%">
</p>

# SwapVe

> **Open-source OCPP 2.1 battery swapping server (CSMS) for the JVM.**
> Implements the Battery Swap functional block of OCPP 2.1 (IEC 63584-210),
> verified against the OCA conformance test cases.

[![status](https://img.shields.io/badge/status-MVP%20complete%20(M0–M10)-brightgreen)]()
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)]()
[![OCPP](https://img.shields.io/badge/OCPP-2.1%20Edition%202-informational)]()

---

## 무엇인가

배터리 교환 스테이션(BSS)을 관리하는 **CSMS**와, 이를 시험하기 위한 **스테이션 시뮬레이터**입니다.

OCPP 2.1이 2025년 1월 **Battery Swap 기능 블록(Block S)** 을 정식 편입했습니다.
전동킥보드·전기자전거 같은 2·3륜과 일반 EV의 배터리 교환을 표준으로 다룹니다.
조사 시점 기준, **서버 측에서 이 블록을 구현한 오픈소스가 확인되지 않습니다.**

| 프로젝트 | 언어 | OCPP | Battery Swap |
|---|---|---|---|
| [SteVe](https://github.com/steve-community/steve) | Java | 1.6만 | ❌ |
| [CitrineOS](https://github.com/citrineos/citrineos) | TypeScript | 2.0.1 전용 | ❌ |
| [MaEVe](https://github.com/thoughtworks/maeve-csms) | Go | 1.6J + 2.0.1 | ❌ |
| [EVerest libocpp](https://github.com/EVerest/libocpp) | C++ | 1.6/2.0.1/2.1 | 충전기 측 |
| **SwapVe** | **Kotlin** | **2.1** | **✅ 서버 측** |

## 무엇이 아닌가

**프로덕션 시스템이 아닙니다.** 없는 것을 먼저 적습니다 — 자세한 내용은 [docs/API.md](docs/API.md).

- ⚠️ **인증·인가가 없습니다.** 로그인도 API 키도 JWT도 없고, WebSocket은 URL 경로의
  식별자만으로 스테이션을 받습니다(`authMethod = NONE`). **그대로 인터넷에 노출하면 안 됩니다.**
- **재시작하면 대부분 증발합니다.** 영속되는 것은 `OUT_TIMED_OUT` 장부 하나뿐입니다
  (H2 파일). 나머지는 인메모리이고, 이벤트 로그에서 재구성 가능한 파생 상태입니다.
- **스마트차징·요금·로밍·수평 확장은 범위 밖입니다** ([PLAN §3](docs/PLAN.md)).
  구현하지 않았을 뿐 **봉쇄하지도 않았습니다** ([PLAN §11](docs/PLAN.md)).
- **대시보드·UI가 없습니다.** 조회는 REST까지입니다 ([PLAN §10 결정 #2](docs/PLAN.md)).
- 아직 미구현인 적합성 경로가 있습니다 — `GetBaseReport(FullInventory)` / `TC_S_104_CS`
  ([BACKLOG B03](BACKLOG.md)).

---

## 5분 안에 돌려보기

필요한 것은 **JDK 17과 git뿐**입니다. Gradle은 래퍼가 받아옵니다.

```bash
git clone https://github.com/ZANNABI-LAB/swapve.git
cd swapve
./gradlew build          # 전체 시험 + 모듈 경계 검증
```

터미널 **A** — 관제 서버를 띄웁니다. `Started CsmsApplicationKt` 가 보이면 준비된 것입니다.

```bash
./gradlew :csms:bootRun          # ws://localhost:8080/ocpp/{stationId}
```

터미널 **B** — 시뮬레이터가 붙어 **교환 1건을 완주**하고 끝납니다.

```bash
./gradlew :station-sim:run \
  --args="--csms-url ws://localhost:8080/ocpp --station-id CS001 --request-id 1001"
```

```
station-sim → ws://localhost:8080/ocpp/CS001 (순서 In-Out, 배터리 2 개)
교환 완주: requestId=1001, 오간 메시지 42 건
```

같은 시각 터미널 A에는 이렇게 남습니다:

```
OcppMessageRouter : 교환 사건: station=CS001 eventType=BatteryIn  requestId=1001 → HalfIn
OcppMessageRouter : 교환 사건: station=CS001 eventType=BatteryOut requestId=1001 → Completed
```

터미널 **C** — 앱이 보게 될 것을 그대로 조회합니다.

```bash
curl localhost:8080/api/swaps/CS001:1001      # 교환 1건 — 양쪽 배터리의 SoC/SoH
curl localhost:8080/api/metrics/swaps         # 성공률 · 소요시간 · 실패 사유
curl localhost:8080/api/stations/CS001/charging-transactions   # 들어온 배터리의 충전 (S04)
```

역순 교환(`Out-In`)도 표준이고, 그대로 돕니다 ([PLAN §4.6](docs/PLAN.md)):

```bash
./gradlew :station-sim:run \
  --args="--csms-url ws://localhost:8080/ocpp --station-id CS001 --swap-order Out-In --request-id 1002"
```

**앱처럼 CSMS 쪽에서 교환을 시작하려면** (표준 유즈케이스 S02) 시뮬레이터를 **대기 모드**로
띄운 뒤 REST로 겁니다. 상관 번호(`requestId`)는 이때 CSMS가 발번하고, 스테이션이 그것을
그대로 승계합니다(S02.FR.02).

```bash
# 터미널 B — 스스로 시작하지 않고 RequestBatterySwap 을 기다린다
./gradlew :station-sim:run \
  --args="--csms-url ws://localhost:8080/ocpp --station-id CS001 --remote-start"

# 터미널 C — 앱이 QR 을 찍은 셈
curl -X POST localhost:8080/api/swaps -H 'Content-Type: application/json' \
     -d '{"stationId":"CS001","idToken":{"idToken":"RFID-0001","type":"ISO14443"}}'
```

계약의 전문은 [docs/API.md](docs/API.md) 에 있습니다.

**막히면?** 인가 토큰은 `csms/src/main/resources/application.yml` 의 `authorized-id-tokens`
목록에 있어야 하고(기본값 `RFID-0001`), 배터리 일련번호는 `known-battery-serials` 에 있어야
합니다. 목록 밖 배터리는 표준이 정한 방식으로 거부됩니다 ([PLAN §4.8](docs/PLAN.md)).

<details>
<summary><b>실측 기록</b> — 위 절차를 실제로 따라 해서 잰 시간 (2026-08-18)</summary>

| 단계 | 걸린 시간 | 관측한 것 |
|---|---|---|
| `./gradlew build` | 54s ~ 1m25s | 시험 279건 + 모듈 경계 검증 3종 통과 |
| `./gradlew :csms:bootRun` | 명령 후 ~20s (서버 자체 기동은 2.8s) | `Started CsmsApplicationKt` · `Tomcat started on port 8080` |
| `./gradlew :station-sim:run …` | 13s | `교환 완주: requestId=1001, 오간 메시지 42 건` |
| CSMS 쪽 확인 | — | `BatteryIn → HalfIn`, `BatteryOut → Completed` |

**Gradle 의존성이 이미 받아져 있는 상태에서 잰 값입니다.** 처음 클론한 뒤 첫 `./gradlew build`
는 Gradle 배포판과 의존성을 내려받는 시간이 더 붙습니다 — 그 시간은 회선에 달려 있어
여기 적지 않습니다. 그 부분을 빼면 **3분 안쪽**입니다.

Out-In 순서(`--swap-order Out-In`)와 S02 대기 모드(`--remote-start`)도 같은 방식으로
실행을 확인했습니다.

`curl` 세 줄도 별도 환경에서 실행해 확인했습니다. 위 절차를 그대로 밟은 뒤:

- `/api/swaps/CS001:1001` → `status: COMPLETED`, `batteriesIn` 2개(`BAT-USED-1/2`, SoC 12·13%)와
  `batteriesOut` 2개(`BAT-FULL-3/4`, SoC 95%)가 **양쪽 다** 실립니다 ([PLAN §11.2](docs/PLAN.md))
- `/api/metrics/swaps` → `successRate: 1.0`, 소요시간 백분위, `failures.byScenario`가
  F1·F2·F3·F5로 구분 (F4·F6은 멱등이라 실패로 세지 않습니다)
- `/api/stations/CS001/charging-transactions` → 슬롯별 충전 트랜잭션

같은 엔드포인트를 실제 Spring 컨텍스트에서 호출하는 `SwapApiTest` · `SwapMetricsApiTest` ·
`ChargingApiTest` 가 매 빌드마다 이를 다시 확인합니다.
</details>

---

## 검증 방법 — 게이트 세 개

세 게이트는 **서로 다른 질문에 답합니다.** 그래서 하나로 합치지 않았습니다 ([PLAN §7.3](docs/PLAN.md)).

| 게이트 | 명령 | 무엇을 보장하나 |
|---|---|---|
| **L1 단위** | `./gradlew build` | 프레임 왕복 · **공식 스키마 181개 검증** · 상태머신 전이와 불변식 · REST 계약. **모듈 경계 검증 3종**(`ocpp-core`/`swap-domain` 에 프레임워크·외부 의존이 새어 들지 않았는가)이 함께 돕니다 |
| **L2 표준 적합성** | `./gradlew conformanceTest` | 공식 케이스 **`TC_S_102_CSMS` · `TC_S_103_CSMS`** 와 실패 시나리오 **F1~F6**. 시험 대상은 CSMS이고 시뮬레이터가 시험계 역할을 합니다 |
| **L3 부하 + 감사** | `./gradlew auditTest` | **스테이션 20대 동시 접속** 후 불변식 감사. 감사는 **이벤트 로그에서 상태를 재구성해** 인메모리 레지스트리와 대조합니다 |

`auditTest` 는 통과/실패만 찍지 않고 **항목마다 몇 건을 검사했는지** 출력합니다.
"통과"만 있으면 검사를 안 한 것과 구분되지 않기 때문입니다.

```
──────────────────────────────────────────────────────────────────────
 불변식 감사 — 성공 기준 S4 (PLAN §2 · §5.3 · §11.1)
 스테이션 20대 · 교환 60건 · 메시지 2520건
──────────────────────────────────────────────────────────────────────
 항목                                 근거                검사  판정
 배터리 수량 보존                          §5.3          60건 교환  ✅ 통과
 슬롯 이중 예약 0                         §5.3       240개 슬롯점유  ✅ 통과
 유실 메시지 0                           §5.3      1260건 CALL  ✅ 통과
 (stationId, requestId) 유일          §5.3         60개 상관키  ✅ 통과
 교환/충전 분리                           §5.1         240개 슬롯  ✅ 통과
 이벤트 로그 순서                          §11.1       20대 스테이션  ✅ 통과
 로그 재구성 ↔ 레지스트리                     §11.1        540개 대조  ✅ 통과
 공식 스키마 위반 0                        §6 원칙2     2520건 메시지  ✅ 통과
 CALLERROR 0                        §7.1       2520건 프레임  ✅ 통과
──────────────────────────────────────────────────────────────────────
```

> **감사 자체도 시험받습니다.** 불변식을 하나씩 일부러 깨뜨린 로그를 넣어 해당 항목이 실제로
> 빨개지는지 확인하는 시험이 `InvariantAuditTest` 입니다 (L1에서 돕니다). 이것이 없으면
> "전항목 통과"는 주장이고, 있으면 판정입니다.

### 성공 기준 S1~S7 — 어디서 어떻게 검증되나

[PLAN §2](docs/PLAN.md)의 성공 기준을 **실행 가능한 명령**으로 옮긴 표입니다.

| # | 기준 | 검증 명령 | 시험 |
|---|---|---|---|
| **S1** | 공식 스키마를 통과하는 메시지로 S03 교환 1건 완주 | `./gradlew build` | `SwapEndToEndTest` · `SchemaCrossCheckTest` · `ProtocolContractTest` |
| **S2** | ★ 공식 적합성 `TC_S_102_CSMS` · `TC_S_103_CSMS` | `./gradlew conformanceTest` | `TcS102CsmsTest` · `TcS103CsmsTest` |
| **S3** | 실패 시나리오 F1~F6 | `./gradlew conformanceTest` | `FailureScenarioTest` |
| **S4** | 스테이션 20대 동시 → 불변식 감사 전항목 | `./gradlew auditTest` | `LoadAuditTest` (+ 감사 자체의 시험 `InvariantAuditTest`) |
| **S5** | 성공률·소요시간·실패 사유가 REST로 조회 | `./gradlew build` | `SwapMetricsApiTest` · `SwapApiTest` · `ChargingApiTest` |
| **S6** | 위 전부가 zannabi-code 게이트로 자동 검증 | 아래 "개발 방식" | `.zannabi/runs/` 증거 디렉토리 · [`.github/workflows/ci.yml`](.github/workflows/ci.yml) |
| **S7** | README만 읽고 5분 내 실행 | 위 "5분 안에 돌려보기" | 실측 — 빌드 → 서버 기동 → 교환 1건 완주 |

---

## 적합성 (Conformance)

OCPP 2.1 Part 6는 **시험 대상이 CSMS인** Battery Swap 테스트 케이스를 정의합니다.
이 프로젝트의 합격 기준은 그것입니다.

### Battery Swap 케이스 (Part 6, p.1366–1369)

| 케이스 | 내용 | 상태 |
|---|---|---|
| `TC_S_102_CSMS` | Remote Start — 배터리 부족 (`Rejected` / `NoBatteryAvailable`) | ✅ `TcS102CsmsTest` |
| `TC_S_103_CSMS` | Remote Start — 전체 교환 시퀀스 (배터리 2개 세트) | ✅ `TcS103CsmsTest` |
| `TC_S_104_CS` | 디바이스 모델 전체 재고 보고 (`GetBaseReport(FullInventory)`) | ❌ 미구현 ([B03](BACKLOG.md)) |

> 시험 대상(System under test)이 CS인 케이스(p.948–954)는 **시뮬레이터의 명세**로 씁니다
> ([PLAN §7.2](docs/PLAN.md)) — `BootedBatterySwapping` · `AuthorizedBatterySwapping` ·
> `EVConnectedPreSessionBatterySwapping` · `EnergyTransferStartedBatterySwapping` ·
> `EVDisconnectedBatterySwapping` 다섯 재사용 상태를 그대로 연기합니다.
>
> **OCTT 공식 인증은 받지 않았습니다** — 유료이고 OCA 승인 시험소를 거쳐야 합니다
> ([BACKLOG B17](BACKLOG.md)). 여기 있는 것은 Part 6 케이스의 **자체 구현**입니다.

### OCPP-J 전송 계층 (Part 4 Edition 2 §3)

| 항목 | 요구 | 상태 |
|---|---|---|
| §3.1.1 연결 URL — 식별자 48자 이하, 콜론 불가, 퍼센트 디코딩 | SHALL | ✅ 핸드셰이크에서 거절 |
| §3.1.1 신원을 URL에만 의존하지 않기 | RECOMMENDED | ⚠️ `StationPrincipal(authMethod=NONE)` — 자리만 마련 |
| §3.1.2 `Sec-WebSocket-Protocol`로 버전 협상 | SHALL | ✅ `ocpp2.1` 미제시 시 연결 거절 |
| §3.3 101 응답에 선택한 하나를 실어 회신 | SHALL | ✅ |
| §3.4 RFC 7692 압축 (`permessage-deflate`) | **SHALL** | ✅ 협상됨 |

**§3.4 압축에 대한 기록.** 적합성 항목이라 추측하지 않고 관측했습니다.
`WebSocketHandshakeTest`가 101 응답의 `Sec-WebSocket-Extensions` 헤더를 직접 읽으며,
실제로 `permessage-deflate;client_max_window_bits=15`가 돌아옵니다.
확장 협상의 주체는 서블릿 컨테이너(내장 Tomcat)이고 Spring이 클라이언트 요청을 그대로
통과시키므로, **애플리케이션 코드로 켜거나 끄는 자리는 없습니다.** 만약 언젠가 꺼진 것이
관측된다면 손댈 곳은 내장 컨테이너 선택 또는 앞단 리버스 프록시이며, 그때 이 시험이 먼저
실패합니다.

**§3.1.1 이중 확인에 대한 기록.** 스펙은 연결 URL만으로 스테이션을 식별하지 말라고 권고합니다.
현재 구현은 경로에서 식별자를 얻지만, 그 사실이 `StationPrincipal.authMethod = NONE`으로
남습니다. 보안 프로파일 2/3을 붙일 때 바뀌는 것은 이 값 하나이고, 핸들러는 이미 문자열이
아니라 `StationPrincipal`을 받고 있습니다 ([PLAN §11.4](docs/PLAN.md)).
인증서 발급·CSR·키 저장소는 범위 밖입니다.

---

## 구성

```
swapve/
├─ ocpp-core/      OCPP-J 프레이밍 · 공식 스키마 검증 · 세션 계층   (프레임워크 무관)
├─ swap-domain/    Battery Swap 도메인 · 교환 상태머신 · 슬롯 모델  (I/O 없음)
├─ csms/           관제 서버 — WebSocket · REST API · 지표
└─ station-sim/    스테이션 시뮬레이터 — 슬롯 · 배터리 · 장애 주입
```

`ocpp-core` 와 `swap-domain` 은 프레임워크를 모릅니다. `String` ↔ 도메인 객체 변환만 합니다.
덕분에 전송 계층을 나중에 교체할 수 있고, 테스트가 I/O 없이 돕니다. **그 경계는 주석이 아니라
빌드 검사입니다** — `checkNoFrameworkImports` · `checkNoExternalDependencies` ·
`checkNoForbiddenDependencies` 가 `./gradlew build` 에서 함께 돕니다.

읽을 순서를 하나 고른다면:

1. **[docs/PLAN.md](docs/PLAN.md)** — 프로토콜 명세(§4), 도메인 설계(§5), 검증 전략(§7).
   스펙 원문 대조로 계획서를 정정한 이력이 §0에 남아 있습니다
2. **[docs/API.md](docs/API.md)** — 앱이 호출할 REST 계약. **없는 것(인증)을 먼저 적었습니다**
3. **[BACKLOG.md](BACKLOG.md)** — 범위 밖으로 밀어낸 것들과 그것을 꺼낼 트리거

| M | 내용 | 상태 |
|---|---|---|
| M0 | 뼈대 + zannabi 연동 | ✅ |
| M1 | `ocpp-core` 프레이밍 코덱 | ✅ |
| M2 | `ocpp-core` 스키마 검증 + CALLERROR 정책 | ✅ |
| M3 | `swap-domain` 교환 상태머신 | ✅ |
| M4 | ★ `ocpp-core` 세션 계층 | ✅ |
| M5 | `csms` WebSocket + S01 Authorize + Boot/Heartbeat | ✅ |
| M6 | `station-sim` + S03 교환 1건 완주 | ✅ |
| M7 | ★ `TC_S_102/103_CSMS` 적합성 + 실패 F1~F6 + S02 발신 | ✅ |
| M8 | 교환 REST API + 지표 ([docs/API.md](docs/API.md)) | ✅ |
| M9 | S04 충전 트랜잭션 수신·기록 + 디바이스 모델 변수 | ✅ |
| M10 | 부하 + 불변식 감사 + README + CI | ✅ |

---

## 앱 계약 — 표준이 앱 시나리오를 이미 정의해 두었습니다

> **S02 - Battery Swap Remote Start**: *"EV Driver requests CSMS to initiate a battery swap
> **via a smartphone app, e.g. by scanning a QR code** or selecting the appropriate station in the app."*

즉 `앱 → CSMS → RequestBatterySwap → 스테이션`이 **표준 유즈케이스**입니다.
앱 구현은 범위 밖이지만 **그 계약은 설계했습니다**.

| 메서드 | 경로 | 하는 일 |
|---|---|---|
| `POST` | `/api/swaps` | 교환 시작 — `RequestBatterySwapRequest` 발사 (S02) |
| `GET` | `/api/swaps/{id}` | 진행 상태 · **양쪽 배터리의 SoC/SoH** · 장부 불균형 |
| `GET` | `/api/metrics/swaps` | 성공률 · 소요시간 분포 · **F1~F6별 실패 사유** |
| `GET` | `/api/stations/{id}/charging-transactions` | 들어온 배터리의 충전 (S04) |

읽어 볼 만한 설계 결정 세 가지 — 자세한 근거는 **[docs/API.md](docs/API.md)** 에 있습니다.

- **배터리 부족(`NoBatteryAvailable`)은 오류가 아니라 200입니다.** 재고 판정은 스테이션이
  하고(S02.FR.04), 그건 시스템 장애가 아니라 정상적인 운영 상태입니다. 5xx로 답하면 앱이
  재시도 대상으로 오해합니다.
- **지표에 Micrometer를 쓰지 않았습니다.** 필요한 값이 전부 기존 기록에서 파생 계산되기
  때문입니다 — 미등록 배터리 거부(F3)와 재접속 재전송(F6)은 지표용 기록이 아예 없고
  **이벤트 로그의 원문**에서 계산됩니다. 카운터를 심으면 진실의 원본이 둘이 됩니다.
- ⚠️ **인증·인가가 없습니다.** 위 "무엇이 아닌가" 참조. 숨기지 않고 문서 첫머리에 적어 두었습니다.

---

## OCPP 스펙과 스키마

- `schemas/` 에는 **OCA 공식 JSON Schema가 원문 그대로** 들어 있습니다 (수정하지 않음).
  © Open Charge Alliance, **CC BY-ND 4.0**. 자세한 내용은 [NOTICE](NOTICE) 참조.
- **스펙 문서(PDF)는 이 저장소에 포함되지 않습니다.**
  [openchargealliance.org/download-ocpp](https://openchargealliance.org/download-ocpp/) 에서
  **무료로** 받을 수 있습니다 (계정 불필요). 받은 뒤 `docs/spec/` 에 두면 됩니다.

---

## 개발 방식

이 프로젝트는 [**zannabi-code**](https://github.com/ZANNABI-LAB/zannabi-code) —
검증 우선 외부 러너 — 로 개발합니다. 모든 완료 선언에 기계 검증 가능한 증거를 요구합니다.

```bash
zannabi run "<작업>" --cwd . \
  --gate "unit:./gradlew test" \
  --gate "conformance:./gradlew conformanceTest" \
  --gate "audit:./gradlew auditTest" --budget 3
```

같은 세 게이트가 [GitHub Actions](.github/workflows/ci.yml) 에서도 그대로 돕니다.

---

## 라이선스

[Apache License 2.0](LICENSE) — 단, `schemas/` 는 예외입니다 ([NOTICE](NOTICE)).

---

<sub>"OCPP" and "Open Charge Point Protocol" are managed by the Open Charge Alliance.
This project is not affiliated with, nor endorsed by, the OCA.</sub>
