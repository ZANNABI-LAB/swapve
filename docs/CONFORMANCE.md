# 검증 — 게이트 · 적합성 · 성공 기준

> README 의 "검증" 절이 요약이고, 이 문서가 전문입니다.
> 세 게이트의 설계 근거는 [PLAN §7.3](PLAN.md) 에 있습니다.

## 불변식 감사는 몇 건을 검사했는지 출력합니다

`auditTest` 는 통과/실패만 찍지 않습니다. **"통과"만 있으면 검사를 안 한 것과 구분되지
않기 때문**입니다.

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

## 성공 기준 S1~S7 — 어디서 어떻게 검증되나

[PLAN §2](PLAN.md) 의 성공 기준을 **실행 가능한 명령**으로 옮긴 표입니다.

| # | 기준 | 검증 명령 | 시험 |
|---|---|---|---|
| **S1** | 공식 스키마를 통과하는 메시지로 S03 교환 1건 완주 | `./gradlew build` | `SwapEndToEndTest` · `SchemaCrossCheckTest` · `ProtocolContractTest` |
| **S2** | ★ 공식 적합성 `TC_S_102_CSMS` · `TC_S_103_CSMS` · `TC_S_104_CS` | `./gradlew conformanceTest` | `TcS102CsmsTest` · `TcS103CsmsTest` · `TcS104CsTest` |
| **S3** | 실패 시나리오 F1~F6 | `./gradlew conformanceTest` | `FailureScenarioTest` |
| **S4** | 스테이션 20대 동시 → 불변식 감사 전항목 | `./gradlew auditTest` | `LoadAuditTest` (+ 감사 자체의 시험 `InvariantAuditTest`) |
| **S5** | 성공률·소요시간·실패 사유가 REST로 조회 | `./gradlew build` | `SwapMetricsApiTest` · `SwapApiTest` · `ChargingApiTest` |
| **S6** | 위 전부가 zannabi-code 게이트로 자동 검증 | [README 개발 방식](../README.md) | `.zannabi/runs/` 증거 디렉토리 · [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) |
| **S7** | README만 읽고 5분 내 실행 | [README 빠른 시작](../README.md) | 실측 — 빌드 → 서버 기동 → 교환 1건 완주. 제어 콘솔의 경로는 `SimConsoleControlTest` 가 매 빌드마다 확인합니다 |

## 적합성 케이스 (Conformance)

OCPP 2.1 Part 6는 **시험 대상이 CSMS인** Battery Swap 테스트 케이스를 정의합니다.
이 프로젝트의 합격 기준은 그것입니다.

### Battery Swap 케이스 (Part 6, p.1366–1369)

| 케이스 | 내용 | 상태 |
|---|---|---|
| `TC_S_102_CSMS` | Remote Start — 배터리 부족 (`Rejected` / `NoBatteryAvailable`) | ✅ `TcS102CsmsTest` |
| `TC_S_103_CSMS` | Remote Start — 전체 교환 시퀀스 (배터리 2개 세트) | ✅ `TcS103CsmsTest` |
| `TC_S_104_CS` | 디바이스 모델 전체 재고 보고 (`GetBaseReport(FullInventory)`) | ✅ `TcS104CsTest` |

> `TC_S_104_CS`는 앞의 둘과 **시험 대상이 반대**입니다 — 시뮬레이터가 CS 역할로 시험받고,
> CSMS가 시험계(Test System)로서 `GetBaseReport(FullInventory)`를 청한 뒤 나뉘어 오는
> `NotifyReport`를 `requestId`별로 재조립합니다 ([PLAN §7.2](PLAN.md)).
>
> 시험 대상(System under test)이 CS인 케이스(p.948–954)는 **시뮬레이터의 명세**로 씁니다
> ([PLAN §7.2](PLAN.md)) — `BootedBatterySwapping` · `AuthorizedBatterySwapping` ·
> `EVConnectedPreSessionBatterySwapping` · `EnergyTransferStartedBatterySwapping` ·
> `EVDisconnectedBatterySwapping` 다섯 재사용 상태를 그대로 연기합니다.
>
> **OCTT 공식 인증은 받지 않았습니다** — 유료이고 OCA 승인 시험소를 거쳐야 합니다
> ([BACKLOG B17](../BACKLOG.md)). 여기 있는 것은 Part 6 케이스의 **자체 구현**입니다.

### OCPP-J 전송 계층 (Part 4 Edition 2 §3)

| 항목 | 요구 | 상태 |
|---|---|---|
| §3.1.1 연결 URL — 식별자 48자 이하, 콜론 불가, 퍼센트 디코딩 | SHALL | ✅ 핸드셰이크에서 거절 |
| §3.1.1 신원을 URL에만 의존하지 않기 | RECOMMENDED | ✅ Basic username 과 경로 stationId 이중 확인 |
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
현재 기본 구현은 Basic 인증의 username 과 경로 stationId 를 대조하고, 성공한 등록에는
`StationPrincipal.authMethod = BASIC` 이 남습니다. 로컬 실험용 `NONE` 프로파일도 같은 타입에
`authMethod = NONE` 으로 남습니다 ([PLAN §11.4](PLAN.md)).
인증서 발급·CSR·키 저장소는 범위 밖입니다.

