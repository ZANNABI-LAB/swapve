<p align="center">
  <img src="docs/assets/hero.png" alt="SwapVe — OCPP 2.1 battery swapping server" width="100%">
</p>

# SwapVe

> **Open-source OCPP 2.1 battery swapping server (CSMS) for the JVM.**
> Implements the Battery Swap functional block of OCPP 2.1 (IEC 63584-210),
> verified against the OCA conformance test cases.

[![status](https://img.shields.io/badge/status-M5%20websocket%20endpoint-yellow)]()
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)]()
[![OCPP](https://img.shields.io/badge/OCPP-2.1%20Edition%202-informational)]()

---

## 현재 상태

**M5 — WebSocket 엔드포인트.** CSMS가 실제로 기동해 스테이션 연결을 받습니다.
`ocpp2.1` 서브프로토콜을 협상하고, 부팅·하트비트·S01 인가에 응답합니다.
프레이밍·스키마 검증·멱등은 `ocpp-core` 그대로입니다 — csms는 그것을 조립할 뿐입니다.

```bash
./gradlew test        # 전체 시험
./gradlew :csms:bootRun   # ws://localhost:8080/ocpp/{stationId}
```

- 📄 **[구현 계획서 (docs/PLAN.md)](docs/PLAN.md)** — 프로토콜 명세, 도메인 설계, 검증 전략
- 📋 **[BACKLOG.md](BACKLOG.md)** — 범위 밖으로 밀어낸 것들과 그 트리거

| M | 내용 | 상태 |
|---|---|---|
| M0 | 뼈대 + zannabi 연동 | ✅ |
| M1 | `ocpp-core` 프레이밍 코덱 | ✅ |
| M2 | `ocpp-core` 스키마 검증 + CALLERROR 정책 | ✅ |
| M3 | `swap-domain` 교환 상태머신 | ✅ |
| M4 | ★ `ocpp-core` 세션 계층 | ✅ |
| M5 | `csms` WebSocket + S01 Authorize + Boot/Heartbeat | ✅ |
| M6~M10 | [PLAN §8](docs/PLAN.md) | |

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
