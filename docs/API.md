# 교환 REST API — 표준 S02 의 클라이언트 계약

> **앱은 구현하지 않는다. 계약은 설계한다.** ([PLAN §3](PLAN.md))
>
> OCPP 2.1 이 앱 시나리오를 이미 정의해 두었다:
>
> > **S02 - Battery Swap Remote Start**: *"EV Driver requests CSMS to initiate a battery swap
> > **via a smartphone app, e.g. by scanning a QR code** or selecting the appropriate station
> > in the app."*
>
> 즉 `앱 → CSMS → RequestBatterySwap → 스테이션` 이 **표준 유즈케이스**다.
> 이 문서는 그 첫 번째 화살표의 계약이다.

---

## ⚠️ 먼저 읽을 것 — 이 API 는 프로덕션이 아니다

| 없는 것 | 왜 |
|---|---|
| **인증·인가 (로그인 · API 키 · JWT · CORS 정책)** | **범위 밖이고 [PLAN §11](PLAN.md) 확장 목록에도 없다.** 누구든 `POST /api/swaps` 로 남의 스테이션에 교환을 걸 수 있다 |
| 요청 속도 제한 · 감사 추적 | 위와 같다 |
| 지표 대시보드 · UI | [PLAN §10 결정 #2](PLAN.md) — **REST 조회까지가 범위**다 |
| 다국어(i18n) | [PLAN §10 결정 #3](PLAN.md) — 앱을 구현하지 않으므로 실질 요구가 없다 |
| 페이징 · 목록 조회 (`GET /api/swaps`) | 소비자가 없다. 없는 소비자를 위해 엔드포인트를 미리 뚫지 않는다 ([PLAN §11.0](PLAN.md)) |

**그대로 인터넷에 노출하면 안 된다.** 숨기지 않고 여기 적어 둔다.

> 인가 자체가 없는 것은 아니다. **OCPP 수준의 인가(`idToken`)는 동작한다** — 인가되지 않은
> 토큰으로는 `RequestBatterySwap` 이 나가지 않는다(S02.FR.03). 없는 것은 **API 호출자**의
> 신원 확인이다. 둘은 다른 층이다.

---

## 엔드포인트

| 메서드 | 경로 | 하는 일 |
|---|---|---|
| `POST` | `/api/swaps` | 교환 시작 — `RequestBatterySwapRequest` 발사 (S02) |
| `GET` | `/api/swaps/{id}` | 교환 1건의 진행 상태 |
| `GET` | `/api/metrics/swaps` | 성공률 · 소요시간 · 실패 사유 (성공 기준 S5) |
| `GET` | `/api/stations/{stationId}/charging-transactions` | 그 스테이션의 충전 트랜잭션 (S04) |
| `GET` | `/api/stations/{stationId}/charging-transactions/{transactionId}` | 충전 1건 |

> **충전은 교환의 하위 자원이 아니다.** 들어온 배터리의 충전은 교환이 끝난 뒤에도 며칠
> 계속되기 때문이다 ([PLAN §5.1](PLAN.md)). 아래 해당 절 참조.

### 교환 식별자 `{id}` 는 `{stationId}:{requestId}` 다

교환은 `(stationId, requestId)` **복합키로만 유일하다** — `requestId` 는 스테이션 범위에서만
유일하기 때문이다 ([PLAN §5.3](PLAN.md)). 그 둘을 한 토큰에 담는다.

콜론이 안전한 이유는 우연이 아니다. **Part 4 §3.1.1 이 스테이션 식별자에 콜론을 금지**하고,
그 규칙은 산문이 아니라 실행되는 검사다 — 핸드셰이크가 콜론이 든 식별자를 **거절**한다
(`StationIdentityTest`). 그래서 첫 콜론 하나로 자르면 모호성이 없다.

```
CS001:1734829911     →  station CS001 의 교환 1734829911
```

---

## `POST /api/swaps` — 교환 시작

앱이 QR 로 얻는 정보 그대로다: **어느 스테이션에서**, **누가**.

```http
POST /api/swaps
Content-Type: application/json

{
  "stationId": "CS001",
  "idToken": { "idToken": "RFID-0001", "type": "ISO14443" }
}
```

**`requestId` 를 받지 않는다.** S02 의 발번 주체는 **CSMS** 다 ([PLAN §4.4](PLAN.md) 표).
앱이 정하게 두면 그 규칙이 클라이언트로 새고, 스테이션이 이어지는 `BatterySwapRequest` 에
같은 값을 써야 한다는 **S02.FR.02** 의 책임 소재가 흐려진다.

`idToken` 은 `(idToken, type)` **값 객체**다 — 로컬 사용자 테이블의 FK 가 아니다
([PLAN §11.3](PLAN.md)). 로밍 토큰은 애초에 우리 DB 에 없다.

### 결말과 HTTP 상태

| 결말 | HTTP | 본문 | 앱이 할 일 |
|---|---|---|---|
| 스테이션이 `Accepted` | **201 Created** + `Location` | `outcome: ACCEPTED` + `swap` | 교환 화면으로 |
| 스테이션이 `Rejected` | **200 OK** | `outcome: REJECTED_BY_STATION` + `reasonCode` | **사유를 이용자에게** |
| 인가되지 않은 `idToken` | **403 Forbidden** | `error: NOT_AUTHORIZED` | 토큰 확인 |
| 스테이션 미연결 | **503 Service Unavailable** | `error: SERVICE_UNAVAILABLE` | 나중에 재시도 |
| 스테이션 무응답 | **504 Gateway Timeout** | `error: GATEWAY_TIMEOUT` | **재시도 주의** ↓ |
| 스테이션이 CALLERROR / 스키마 위반 응답 | **502 Bad Gateway** | `error: BAD_GATEWAY` | 재시도해도 같다 |
| `stationId`·`idToken` 누락 | **400 Bad Request** | `error: INVALID_REQUEST` | 요청 수정 |

> **왜 5xx 를 하나로 뭉치지 않았나.** 앱이 해야 할 일이 서로 다르기 때문이다. 연결이 없으면
> 스테이션이 돌아올 때까지 기다리면 되지만(503), **응답이 없는 경우(504)는 그 교환이 실제로
> 열렸을 수도 있다** — 요청은 나갔고 답만 못 받은 상태다. 같은 상태로 답하면 앱이 그 차이를
> 알 수 없다.

### ★ `Rejected` 가 오류가 아닌 이유

**배터리가 없는 것은 시스템 장애가 아니다.** 재고 판정은 스테이션이 하고
([PLAN §4.5](PLAN.md), **S02.FR.04**), CSMS 는 재고를 몰라도 된다. 이것이 공식 적합성 케이스
`TC_S_102_CSMS` 의 시나리오이자 실패 시나리오 **F1** 이다.

5xx 로 답하면 앱은 재시도 대상으로 오해하고 이용자는 *"서버 오류"* 를 본다 — 실제로는
*"이 스테이션에 지금 내줄 배터리가 없다"* 는 정상적인 운영 상태다.

```jsonc
// 200 OK
{
  "outcome": "REJECTED_BY_STATION",
  "stationId": "CS001",
  "requestId": 1734829911,
  "swap": null,                        // 교환이 열리지 않았다 — 조회할 대상이 없다
  "reasonCode": "NoBatteryAvailable",  // 스테이션이 보낸 원문 (§4.9.1)
  "reason": "NO_BATTERY_AVAILABLE",    // 사전 정의 사유로 해석됐을 때만
  "additionalInfo": null,
  "rejectedAt": "2026-08-18T09:30:00Z"
}
```

`reasonCode` 는 **스테이션이 보낸 원문**이고 `reason` 은 그것을 부록
`reason_codes.csv`([§4.9.1](PLAN.md))로 해석한 결과다. 표에 없는 값이 와도 버리지 않는다 —
그때 `reason` 만 `null` 이고 원문은 남는다.

### `Accepted` — 201

```jsonc
// 201 Created
// Location: /api/swaps/CS001:1734829911
{
  "outcome": "ACCEPTED",
  "stationId": "CS001",
  "requestId": 1734829911,
  "swap": { /* ↓ SwapView. self 가 Location 과 같다 */ },
  "reasonCode": null, "reason": null, "additionalInfo": null, "rejectedAt": null
}
```

### `403` — S02.FR.03

> **S02.FR.03**: CSMS 는 인가되지 않은 `idToken` 으로 `RequestBatterySwap` 을 보내면 안 된다(**SHALL NOT**).

**보내고 나서 거부당한 것이 아니라 아예 보내지 않았다.** 전선에 프레임이 나가지 않는다.

```jsonc
// 403 Forbidden
{
  "error": "NOT_AUTHORIZED",
  "message": "인가되지 않은 idToken 이라 RequestBatterySwap 을 보내지 않았다 (S02.FR.03)",
  "stationId": "CS001",
  "idTokenStatus": "Unknown"     // Invalid 가 아니다 — 우리 목록에 없을 뿐이다 (PLAN §11.3)
}
```

---

## `GET /api/swaps/{id}` — 진행 상태

```jsonc
// 200 OK
{
  "id": "CS001:1734829911",
  "self": "/api/swaps/CS001:1734829911",
  "stationId": "CS001",
  "requestId": 1734829911,
  "status": "COMPLETED",
  "idToken": { "idToken": "RFID-0001", "type": "ISO14443" },

  "authorizedAt": "2026-08-18T09:30:00Z",   // 인가가 난 시각 (배터리 투입 시각이 아니다)
  "startedAt":    "2026-08-18T09:30:12Z",   // 첫 배터리가 오간 시각
  "endedAt":      "2026-08-18T09:31:42Z",
  "durationMillis": 90000,                  // 진행 중이면 null

  "batteriesIn": [
    { "slotId": 1, "serialNumber": "BAT-USED-1", "soC": 23.0, "soH": 85.0 },
    { "slotId": 2, "serialNumber": "BAT-USED-2", "soC": 45.0, "soH": 87.0 }
  ],
  "batteriesOut": [
    { "slotId": 3, "serialNumber": "BAT-FULL-3", "soC": 80.0, "soH": 95.0 },
    { "slotId": 4, "serialNumber": "BAT-FULL-4", "soC": 85.0, "soH": 78.0 }
  ],

  "ledgerImbalance": null                   // OUT_TIMED_OUT 일 때만 값이 있다
}
```

### 상태 (`status`)

[PLAN §5.2](PLAN.md) 의 상태머신 그대로다. **순서 불가지론**이라 입고 먼저(통상)든
출고 먼저(`SwapOrder = "Out-In"`)든 같은 종단에 도달한다.

```
                    AUTHORIZED          ← 개시 승인. requestId 확정
                    ╱         ╲
              HALF_IN         HALF_OUT  ← 반쪽이 열렸다
              ╱     ╲             │
     COMPLETED   OUT_TIMED_OUT  COMPLETED
```

| 상태 | 의미 | `batteriesIn` | `batteriesOut` | 종단 |
|---|---|---|---|---|
| `AUTHORIZED` | 개시가 승인됐다. 아직 배터리가 오가지 않았다 | `[]` | `[]` | |
| `HALF_IN` | 헌 배터리가 들어왔다 | 있음 | `[]` | |
| `HALF_OUT` | 새 배터리가 나갔다 (Out-In 순서) | `[]` | 있음 | |
| `COMPLETED` | **들어온 수 = 나간 수** | 있음 | 있음 | ✅ |
| `OUT_TIMED_OUT` | 제공된 배터리를 꺼내가지 않았다 | orphan | `[]` | ⚠️ |

> `IDLE` 은 없다. 그건 "아직 아무 일도 없다"이지 교환의 상태가 아니다.
> 인가 없이 도착한 교환 사건(**F5**)은 이상으로 기록되지만 **교환을 열지 않으므로**
> 여기서 조회되지 않는다(404). 그 사실은 지표의 `failures.byScenario.F5` 에 남는다.

### ★ 양쪽 배터리 정보를 전부 내보낸다

`serialNumber` · `soC` · `soH` 를 **입고·출고 양쪽 다** 보존한다 ([PLAN §11.2](PLAN.md)).
표준이 과금 근거를 명시하기 때문이다:

> *"the price can depend, for example, on **the difference between the state of charge of the
> old and new batteries**"* (Part 2 S. Ch.1)

요금 계산은 범위 밖이지만, 여기서 값을 버리면 나중에 과금이 **기존 데이터 위의 순수 계산**이
되지 못한다. 정보를 버리는 것은 범위 조정이 아니라 되돌릴 수 없는 전제다.

### ★ `OUT_TIMED_OUT` — 장부 불균형이 드러난다

> **S03.FR.06**: *"Situation needs to be reported, because CSMS ends up with an
> **orphan BatteryIn for which a BatteryOut is missing**."*

조용히 "실패"로 뭉개지 않는다. **몇 개가 orphan 인지, 어떤 배터리인지** 보여야 보상할 수 있다.

```jsonc
{
  "status": "OUT_TIMED_OUT",
  "batteriesIn":  [ /* orphan 배터리. 대응하는 출고가 없다 */ ],
  "batteriesOut": [],
  "ledgerImbalance": {
    "orphanCount": 2,
    "orphanBatteries": [
      { "slotId": 1, "serialNumber": "BAT-USED-1", "soC": 23.0, "soH": 85.0 },
      { "slotId": 2, "serialNumber": "BAT-USED-2", "soC": 45.0, "soH": 87.0 }
    ],
    "persisted": true      // 이 채무가 H2 에도 남았다 (PLAN §5.3 — 이것만 영속된다)
  }
}
```

`persisted` 를 감추지 않는 이유: 이것이 `false` 라면 프로세스가 죽는 순간 사라질 채무라는
뜻이다. 그 차이는 운영자가 알아야 한다.

### 없는 교환 — 404

```jsonc
// 404 Not Found
{ "error": "NOT_FOUND", "message": "그런 교환이 없다: CS001:9999", "stationId": null, "idTokenStatus": null }
```

**식별자의 모양이 틀린 경우도 404 다** (400 이 아니다). 400 으로 답하면 *"문법은 맞는데 없다"*
와 *"문법이 틀렸다"* 가 갈리고, 그 차이가 스테이션 식별자를 넘겨짚는 수단이 된다.
앱 입장에서는 둘 다 *"그런 교환은 없다"* 이다.

---

## `GET /api/metrics/swaps` — 지표 (성공 기준 S5)

> [PLAN §2 S5](PLAN.md): *"교환 성공률·소요시간·실패 사유가 REST 로 조회"*

```jsonc
// 200 OK
{
  "generatedAt": "2026-08-18T09:30:00Z",

  "swaps": {
    "attempted": 7,        // 스테이션에 실제로 도달한 개시 (열린 교환 + 거부된 개시)
    "completed": 2,
    "inProgress": 3,       // AUTHORIZED / HALF_IN / HALF_OUT
    "failed": 2,           // OUT_TIMED_OUT + 스테이션이 거부한 개시
    "blockedStarts": 1     // ★ 보내지 않은 시도 (S02.FR.03) — attempted 에 없다
  },
  "successRate": 0.2857142857142857,   // completed / attempted. 시도가 없으면 null

  "duration": {
    "completed":   { "count": 2, "minMillis": 30000, "meanMillis": 60000,
                     "maxMillis": 90000, "p50Millis": 30000, "p95Millis": 90000 },
    "outTimedOut": { "count": 1, "minMillis": 45000, "meanMillis": 45000,
                     "maxMillis": 45000, "p50Millis": 45000, "p95Millis": 45000 }
  },

  "failures": {
    "total": 4,
    "byScenario":  { "F1": 1, "F2": 1, "F3": 1, "F5": 1 },
    "byReasonCode": { "NoBatteryAvailable": 1, "BatteryUnknown": 1 },
    "byAnomalyReason": { "NOT_AUTHORIZED": 1 },
    "rejectedAuthorizations": 1
  },

  "idempotency": {
    "byScenario": { "F4": 1, "F6": 1 },
    "stateMachineIgnores": 1,
    "byIgnoreReason": { "DUPLICATE_BATTERY_IN": 1 },
    "sessionReplays": 1
  },

  "ledger": { "openImbalances": 3, "orphanBatteries": 6 }
}
```

### 성공률의 분모가 무엇인지 밝힌다

`attempted` 는 **스테이션에 실제로 도달한 개시**다: 열린 교환 전부 + 스테이션이 거부한 개시.

**`blockedStarts`(S02.FR.03 으로 막힌 시도)는 분모에 넣지 않는다.** `RequestBatterySwap` 이
전선에 나가지도 않은 것을 교환 시도로 세면 성공률이 그만큼 낮아 보인다. 인가 정책이 막은
것은 교환의 실패가 아니라 **교환이 시작되지 않은 것**이다.

### 소요시간 — 평균만으로는 쓸모가 적다

교환 소요시간은 대칭 분포가 아니다. 대부분 수십 초에 끝나고 소수가 이용자 사정으로 길게
끌린다. 그런 분포에서 평균 하나는 *"빠른 것도 느린 것도 아닌, 아무도 겪지 않은 값"* 이 된다.

- **백분위는 nearest-rank** — 정렬 후 `ceil(p/100 × n)` 번째 값을 그대로 쓴다. 보간하지 않는
  이유는 **실제로 일어난 값 하나를 가리키기 위해서**다. 보간값은 어느 교환의 소요시간도 아니다.
- **표본이 없으면 `count` 만 0 이고 나머지는 `null`** 이다. `0` 으로 채우지 않는다 —
  *"0 밀리초 만에 끝났다"* 와 *"끝난 교환이 없다"* 는 완전히 다른 사실이다.
- **완주와 수령 타임아웃을 섞지 않는다.** `OUT_TIMED_OUT` 의 시간은 "교환이 이만큼 걸렸다"가
  아니라 "이용자가 이만큼 안 꺼내 갔다"이다.

### 실패 사유 — "실패 12건"은 정보가 아니다

두 축으로 각각 센다. 한 축만으로는 *"거부 3건"* 이 배터리 부족인지 미등록 배터리인지 모른다.

| 축 | 값 |
|---|---|
| `byScenario` | [PLAN §5.4](PLAN.md) 의 F1~F6 |
| `byReasonCode` | 부록 `reason_codes.csv` ([§4.9.1](PLAN.md)) 의 사유 |

| # | 시나리오 | 어디서 세나 | 실패인가 |
|---|---|---|---|
| **F1** | 배터리 부족 — 스테이션이 `Rejected`/`NoBatteryAvailable` | 거부 기록 | ✅ |
| **F2** | 수령 타임아웃 → `OUT_TIMED_OUT` | 교환 상태 | ✅ |
| **F3** | 미등록 배터리 — `customData` 로 거부 | **이벤트 로그 원문** ↓ | ✅ |
| **F5** | 순서 위반 — 인가 없이 도착 | 이상 기록 | ✅ |
| **F4** | 중복 `BatteryIn` (새 messageId) | 상태머신 무시 기록 | ❌ **멱등** |
| **F6** | 재접속 재전송 (같은 messageId) | **이벤트 로그 중복 수신** ↓ | ❌ **멱등** |

**F4·F6 은 실패가 아니다.** 재전송·중복 수신은 정상적으로 일어나고, **두 번 반영되지
않았다**는 것이 올바른 동작이다. 실패로 세면 성공률이 이유 없이 나빠지고, 아예 안 세면
장부가 왜 안 늘었는지 설명할 수 없다. 그래서 `idempotency` 블록이 따로 있다.

**둘은 구분된다 — 걸린 층이 다르기 때문이다.**
F4 는 멱등 원장을 지나 **상태머신**이 `(stationId, requestId)` 로 잡았고,
F6 은 **세션의 멱등 원장**이 상위 계층을 부르지도 않고 저장된 응답을 그대로 냈다.

`rejectedAuthorizations` 는 인가되지 않은 토큰으로 들어온 시도다. **S02 원격 개시가 막힌
것과 S01 로컬 인가가 거부된 것이 섞여 있다** — 인가 기록이 시도의 출처를 남기지 않기
때문이다. 구분이 필요해지면 고칠 자리는 그 기록이지 지표가 아니다.

### `ledger` 는 다른 질문에 답한다

위의 모든 수치는 **이 프로세스가 본 교환**에 대한 것이라 재시작하면 0 부터 다시 센다.
`ledger` 만은 H2 에서 읽으므로 **재시작을 가로질러 남는다** ([PLAN §5.3](PLAN.md) — 교환
상태 중 `OUT_TIMED_OUT` 만 영속된다). **보상해야 할 채무의 총계**이지 이번 관측 구간의
실패 수가 아니다. 두 값이 다르다고 해서 어느 한쪽이 틀린 것이 아니다.

---

## `GET /api/stations/{stationId}/charging-transactions` — 충전 트랜잭션 조회 (S04)

> [PLAN §4.10](PLAN.md) · [§10 결정 #8](PLAN.md): **수신·기록만.** 스마트차징·요금은 없다.

```jsonc
// 200 OK — 이 스테이션의 충전 트랜잭션 전부
[
  {
    "self": "/api/stations/CS001/charging-transactions/0KA9L2M3N4P5",
    "stationId": "CS001",
    "transactionId": "0KA9L2M3N4P5",
    "slotId": 1,                          // 어느 슬롯(EVSE)인가
    "batterySerialNumber": "BAT-USED-1",  // 어느 배터리인가 (모르면 null — 아래)
    "status": "SUSPENDED",                // CONNECTED | CHARGING | SUSPENDED | ENDED | UNKNOWN
    "socPercent": 50.0,                   // 마지막으로 보고된 SoC (S04.FR.04). 없으면 null
    "startedAt": "2026-08-18T09:30:00Z",
    "updatedAt": "2026-08-18T10:00:00Z",
    "eventCount": 6
  }
]
```

`GET /api/stations/{stationId}/charging-transactions/{transactionId}` 는 같은 객체 1건이고,
없으면 **404** 다. 목록은 없는 스테이션이어도 **빈 배열에 200** 이다 — *"그 스테이션에 도는
충전이 없다"* 는 정상적인 답이고, 스테이션 자원은 아직 없다.

### ★ 교환의 하위 자원이 아니다 ([PLAN §5.1](PLAN.md))

`/api/swaps/{id}/charging` 같은 경로를 뚫지 **않았다.** 들어온 배터리의 충전은 **교환이 끝난
뒤에도 며칠 계속되기 때문**이다 — 교환 아래에 매달면 교환이 `COMPLETED` 가 되는 순간 그
배터리의 충전을 가리킬 이름이 사라진다. 충전은 스테이션의 슬롯에 매인 것이라 경로도 거기 있다.

같은 이유로 이 응답에는 **교환을 가리키는 필드가 없다.** 이 배터리는 어느 교환에도 매여 있지
않다. (`ChargingApiTest` 가 *"교환이 완료돼도 들어온 배터리의 충전은 조회된다"* 로 고정한다.)

### `status` 는 원문 `chargingState` 가 아니다

`chargingState` 의 값 목록은 표준이 늘릴 수 있고(`SuspendedEV` 등), 소비자가 알아야 하는 것은
**"충전 중인가 · 멈췄나 · 끝났나"** 셋이다. 경계에서 한 번 옮긴다 — `SwapView.status` 와 같다.

**`SUSPENDED` 는 끝난 것이 아니다.** `MaxSoc` 에 닿아 급전이 멈춘 상태이고(S04.FR.06),
배터리는 여전히 슬롯에 있으며 트랜잭션은 살아 있다. 끝은 배터리를 꺼내갈 때다
(`TxStopPoint = EVConnected`, S04.FR.09).

### `batterySerialNumber` 가 `null` 인 것은 버그가 아니다

CSMS 가 슬롯과 배터리를 잇는 지점은 `BatterySwapRequest(BatteryIn)` 가 `(evseId,
serialNumber)` 를 실어 오는 **그 순간 하나뿐**이다. `TransactionEvent` 도 `NotifyEvent` 도
일련번호를 싣지 않는다. 그래서 **부팅 시점부터 이미 꽂혀 있던 배터리는 CSMS 가 정말로
모른다** — 모르는 것을 지어내지 않는다. 알고 싶으면 `GetVariables` 로 물어야 한다
([PLAN §4.5](PLAN.md)).

### 쓰기가 없다

충전 트랜잭션은 스테이션이 만드는 사실이지 CSMS 가 지시하는 것이 아니다. 스마트차징도 요금도
범위 밖이다([§10 결정 #8](PLAN.md)). 그래서 `POST` 도 `PATCH` 도 없다.

### 디바이스 모델은 REST 에 없다

`GetVariables`/`SetVariables`(`TargetSoC`·`MaxSoc`·`BatteryCartridge.SoC` …)는 CSMS 안의
`DeviceModelClient` 로만 부를 수 있고 **HTTP 로 노출하지 않았다.** 소비자가 없기 때문이다
([PLAN §11.0](PLAN.md)) — 앱은 스테이션의 설정 변수를 만지지 않고, 운영 도구는 아직 없다.
필요해지면 그 클래스를 부르는 얇은 컨트롤러 하나가 생길 자리다.

---

## 설계 기록

### 지표에 Micrometer / Actuator 를 쓰지 않았다

**쓰지 않는 쪽이 더 단순하다:**

1. **필요한 값이 전부 이미 있는 기록에서 파생 계산된다.** 성공률은 교환 보관소의 상태
   분포이고, 소요시간은 그 상태에 이미 들어 있는 시각들의 차이이며, 실패 사유는 거부·이상·
   멱등 기록과 **이벤트 로그의 원문**에 있다. [PLAN §11.1](PLAN.md) 이 이벤트 로그에 건
   **유일한 규칙**이 *"파생 상태는 이 로그에서 계산될 수 있어야 한다"* 이고,
   지표야말로 그 규칙이 지켜지는지 확인하는 자리다.
   - **F3 과 F6 이 그 증거다.** 둘 다 지표를 위한 기록이 따로 없다 — `customData` 거부 응답과
     중복 수신 CALL 의 **원문**에서 계산된다. 원문 대신 해석 결과만 남겼다면 셀 수 없었을 것이다.
2. **카운터를 따로 두면 진실의 원본이 둘이 된다.** `Counter.increment()` 를 코드 곳곳에 심는
   순간, 장부(H2)와 카운터(인메모리)가 재시작·예외·재전송에서 어긋나기 시작한다. 그러면
   지표가 시스템을 설명하는 것이 아니라 **지표 자신을 설명해야 하는 대상**이 된다.
3. **소비자가 없다.** [PLAN §10 결정 #2](PLAN.md) 가 대시보드를 제외했다. Micrometer 의 값은
   Prometheus 같은 scrape 대상이 있을 때 의미가 있는데, 그 대상이 이 프로젝트에 없다.
4. **의존성이 늘지 않는다.**

**언제 바뀌나:** 시계열(시간에 따른 추이)이 필요해지면 이야기가 다르다. 이 계산은 언제나
"지금까지의 전량"이라 구간 질의를 할 수 없다. 그때 붙일 자리도 같다 — 이벤트 로그가 남아
있으므로 **소급 계산이 가능하다.**

### 발신 로직을 다시 만들지 않았다

S02 발신(`RequestBatterySwapRequest`)은 **M7 에서 이미 끝났다** —
`TC_S_103_CSMS` 의 1단계가 CSMS 의 발신이라 적합성보다 앞설 수 없었다
([PLAN §0 v3.1](PLAN.md)). 이 API 는 그 위에 REST 진입점을 얹은 것이고,
컨트롤러가 하는 일은 결과를 HTTP 로 옮기는 `when` 하나다.

인가 판정(S02.FR.03)도, 요청의 공식 스키마 자기검증도, `StationCommandBus` 발신도 전부
그 아래 계층에 있다. **REST 를 붙이면서 OCPP 경로는 한 줄도 건드리지 않았다.**

---

## 시험

이 문서의 모든 계약은 실행되는 시험으로 고정돼 있다.

| 시험 | 확인하는 것 |
|---|---|
| `SwapApiTest` | **실제 WebSocket 으로 붙은 시뮬레이터**를 상대로 한 전 경로. 목이 없다 |
| `SwapMetricsApiTest` | 성공·실패·멱등이 섞인 시나리오를 한 번 돌린 뒤 성공률·소요시간·사유별 집계 |
| `DurationSummaryTest` | 백분위·평균·빈 표본의 순수 계산 |
| `SwapIdsTest` | 복합키 ↔ URL 토큰 |
| `ChargingApiTest` | 충전 조회 — 슬롯·배터리·SoC 가 읽히고, **교환이 완료돼도 충전은 살아 있다** |

```bash
./gradlew :csms:test --tests 'dev.swapve.csms.api.*'
```

`POST` 가 정말로 `RequestBatterySwapRequest` 를 내보냈는지는 CSMS 의 반환값이 아니라
**시뮬레이터가 받은 프레임**으로 확인한다. 응답 본문도 DTO 로 되읽지 않고 JSON 필드 이름을
직접 본다 — 우리 타입으로 되읽으면 필드 이름이 바뀌어도 시험은 통과하고, 그때 깨지는 것은 앱이다.
