# Configuration — station authentication and REST authentication

> The five-minute demo turns both layers down. **The operational defaults are `BASIC` over the
> WebSocket and Basic on the REST API.** This document is what you need to run it that way.
> The two credential sets are **separate lists**; for the whole picture of the boundary see the
> §3.1.1 note in [CONFORMANCE.md](CONFORMANCE.md).

## Station authentication

The CSMS WebSocket defaults to `csms.security.profile=BASIC`. You configure a bcrypt hash per
station, and the station sends its stationId as the Basic username.

```yaml
csms:
  security:
    profile: BASIC
    stations:
      - station-id: CS001
        password-hash: "$2a$10$..."
```

Generate the hash with something like Spring Security Crypto's
`BCryptPasswordEncoder(10).encode("station-password")`. The local simulator adds no dependency for
this — it sets the JDK WebSocket header directly.

```bash
./gradlew :station-sim:run \
  --args="--csms-url ws://localhost:8080/ocpp --station-id CS001 --password station-password"
```

To terminate TLS in the CSMS itself, fill in `server.ssl.enabled=true` and the
`server.ssl.key-store` family. In production it is equally valid to terminate TLS at a reverse
proxy and forward plain `ws` inside the trust boundary.

## REST API authentication

`/api/*` is Basic-authenticated by default. If `csms.api.security.users` is empty the server still
starts, but **every REST request is closed with 401**. This is a separate list from the OCPP
station credentials.

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

## Turning authentication off is for local experiments

```bash
./gradlew :csms:bootRun --args="--csms.security.profile=NONE --csms.api.security.enabled=false"
```

Identity does not disappear even then — `StationPrincipal.authMethod` records `NONE`
(the §3.1.1 note in [CONFORMANCE.md](CONFORMANCE.md)).

## If something is rejected, check these

- The authorization token must be in the `authorized-id-tokens` list (default `RFID-0001`)
- The battery serial must be in `known-battery-serials`
- If `csms.api.security.users` is empty the server starts but every REST request answers 401

## What is not here yet

mTLS (security profiles 2 and 3), credential rotation, rate limiting, and an operational audit log.
For the full list see *"What this is not"* in the README.
