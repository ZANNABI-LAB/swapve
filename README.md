<p align="center">
  <img src="docs/assets/hero.png" alt="SwapVe — OCPP 2.1 battery swapping server" width="100%">
</p>

# SwapVe

> **Open-source OCPP 2.1 battery swapping server (CSMS) for the JVM.**
> Implements the Battery Swap functional block of OCPP 2.1 (IEC 63584-210),
> verified against the OCA conformance test cases.

[![status](https://img.shields.io/badge/status-M8%20swap%20API%20%26%20metrics-yellow)]()
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)]()
[![OCPP](https://img.shields.io/badge/OCPP-2.1%20Edition%202-informational)]()

---

## 현재 상태

**M8 — 교환 REST API + 지표 (성공 기준 S5).** 공식 적합성 케이스 `TC_S_102_CSMS`·
`TC_S_103_CSMS`와 실패 시나리오 F1~F6이 통과하고(M7), 그 위에 **앱이 소비할 교환 API**가
얹혔습니다. 표준이 정의한 S02(*"스마트폰 앱에서 QR을 찍어 교환을 개시"*)의 CSMS 측 계약입니다.

```bash
./gradlew build             # 전체 시험 + 모듈 경계 검증
./gradlew conformanceTest   # ★ 공식 적합성 + 실패 시나리오 F1~F6
./gradlew :csms:bootRun     # ws://localhost:8080/ocpp/{stationId}
./gradlew :station-sim:run --args="--csms-url ws://localhost:8080/ocpp --station-id CS001 --swap-order Out-In"
```

```bash
# 교환 시작 → RequestBatterySwap 발사 (S02)
curl -X POST localhost:8080/api/swaps -H 'Content-Type: application/json' \
     -d '{"stationId":"CS001","idToken":{"idToken":"RFID-0001","type":"ISO14443"}}'
curl localhost:8080/api/swaps/CS001:1734829911   # 진행 상태 · 양쪽 배터리 SoC/SoH
curl localhost:8080/api/metrics/swaps            # 성공률 · 소요시간 · 실패 사유
```

- 📄 **[구현 계획서 (docs/PLAN.md)](docs/PLAN.md)** — 프로토콜 명세, 도메인 설계, 검증 전략
- 🔌 **[앱 계약 (docs/API.md)](docs/API.md)** — 교환 REST API. **인증이 없다는 사실 포함**
- 📋 **[BACKLOG.md](BACKLOG.md)** — 범위 밖으로 밀어낸 것들과 그 트리거

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
| M9~M10 | [PLAN §8](docs/PLAN.md) | |

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
덕분에 전송 계층을 나중에 교체할 수 있고, 테스트가 I/O 없이 돕니다.

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

읽어 볼 만한 설계 결정 세 가지 — 자세한 근거는 **[docs/API.md](docs/API.md)** 에 있습니다.

- **배터리 부족(`NoBatteryAvailable`)은 오류가 아니라 200입니다.** 재고 판정은 스테이션이
  하고(S02.FR.04), 그건 시스템 장애가 아니라 정상적인 운영 상태입니다. 5xx로 답하면 앱이
  재시도 대상으로 오해합니다.
- **지표에 Micrometer를 쓰지 않았습니다.** 필요한 값이 전부 기존 기록에서 파생 계산되기
  때문입니다 — 미등록 배터리 거부(F3)와 재접속 재전송(F6)은 지표용 기록이 아예 없고
  **이벤트 로그의 원문**에서 계산됩니다. 카운터를 심으면 진실의 원본이 둘이 됩니다.
- ⚠️ **인증·인가가 없습니다.** 로그인도 API 키도 JWT도 없습니다. 범위 밖이며,
  **그대로 인터넷에 노출하면 안 됩니다.** 숨기지 않고 문서 첫머리에 적어 두었습니다.

지표 대시보드와 UI는 만들지 않습니다 ([PLAN §10 결정 #2](docs/PLAN.md) — REST 조회까지).

---

## 적합성 (Conformance)

OCPP 2.1 Part 6는 **시험 대상이 CSMS인** Battery Swap 테스트 케이스를 정의합니다.
이 프로젝트의 합격 기준은 그것입니다.

| 케이스 | 내용 |
|---|---|
| `TC_S_102_CSMS` | Remote Start — 배터리 부족 (`Rejected` / `NoBatteryAvailable`) |
| `TC_S_103_CSMS` | Remote Start — 전체 교환 시퀀스 (배터리 2개 세트) |

> "표준을 준수한다"를 주장이 아니라 **통과 여부로 판정 가능한 형태**로 만드는 것이 목표입니다.

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
zannabi run "<작업>" --cwd . --gate "unit:./gradlew test" --budget 3
```

---

## 라이선스

[Apache License 2.0](LICENSE) — 단, `schemas/` 는 예외입니다 ([NOTICE](NOTICE)).

---

<sub>"OCPP" and "Open Charge Point Protocol" are managed by the Open Charge Alliance.
This project is not affiliated with, nor endorsed by, the OCA.</sub>
