# 설정 — 스테이션 인증과 REST 인증

> 5분 데모는 인증을 낮춰 띄웁니다. **기본 운영값은 WebSocket `BASIC` + REST Basic** 입니다.
> 이 문서는 그 상태로 띄울 때 필요한 것들입니다.
> 두 자격증명은 **서로 다른 목록**이고, 경계의 전체 그림은 보안 프로파일 에 있습니다.

## 스테이션 인증

CSMS WebSocket 은 기본 `csms.security.profile=BASIC` 입니다. 스테이션별 bcrypt 해시를
설정하고, 스테이션은 Basic username 으로 stationId 를 보냅니다.

```yaml
csms:
  security:
    profile: BASIC
    stations:
      - station-id: CS001
        password-hash: "$2a$10$..."
```

해시는 Spring Security Crypto 의 `BCryptPasswordEncoder(10).encode("station-password")`
같은 방식으로 생성합니다. 로컬 시뮬레이터는 의존성을 추가하지 않고 JDK WebSocket 헤더만 씁니다.

```bash
./gradlew :station-sim:run \
  --args="--csms-url ws://localhost:8080/ocpp --station-id CS001 --password station-password"
```

인증을 끄는 것은 로컬 실험용입니다:

```bash
./gradlew :csms:bootRun --args="--csms.security.profile=NONE --csms.api.security.enabled=false"
```

TLS 를 CSMS 가 직접 끝내려면 `server.ssl.enabled=true` 와 `server.ssl.key-store` 계열 설정을
채웁니다. 운영 환경에서는 리버스 프록시가 TLS 를 종료하고 내부로 `ws` 를 넘기는 구성도
가능합니다.

## REST API 인증

`/api/*` 는 기본 Basic 인증입니다. `csms.api.security.users` 가 비어 있으면 서버는 뜨지만
모든 REST 요청은 401 로 닫힙니다. OCPP 스테이션 자격증명과 별도 목록입니다.

```yaml
csms:
  api:
    security:
      users:
        - username: operator
          password-hash: "$2a$10$..."
```

```bash
curl -u operator:api-password localhost:8080/api/metrics/swaps
```

---

## 인증을 끄는 것은 로컬 실험용입니다

```bash
./gradlew :csms:bootRun --args="--csms.security.profile=NONE --csms.api.security.enabled=false"
```

이때도 신원은 사라지지 않습니다 — `StationPrincipal.authMethod` 에 `NONE` 이 남습니다
(보안 프로파일).

## 막히면 확인할 것

- 인가 토큰이 `authorized-id-tokens` 목록에 있어야 합니다 (기본값 `RFID-0001`)
- 배터리 일련번호가 `known-battery-serials` 에 있어야 합니다
- `csms.api.security.users` 가 비어 있으면 서버는 뜨지만 모든 REST 요청이 401 입니다

## 아직 없는 것

mTLS(보안 프로파일 2/3, B12), 자격증명 회전, 속도 제한,
운영용 감사 로그. 자세한 것은 README 의 "무엇이 아닌가" 를 보세요.
