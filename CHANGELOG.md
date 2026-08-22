# Changelog

Notable changes to the published artifacts — `io.github.zannabi-lab:ocpp-core` and
`io.github.zannabi-lab:swap-domain`. The applications in this repository (`csms`, `station-sim`,
`sim-console`) are not published and are mentioned only where they explain a change.

This project follows [Semantic Versioning](https://semver.org/). While the major version is `0`,
**the public API may still change between minor versions** — that is what `0.x` means, and it is
deliberate: the shape of these types has not yet been tested against a consumer other than this
repository.

## [Unreleased]

### Changed

- **`transmit` now reports whether the frame left, instead of throwing.** Its type went from
  `suspend (String) -> Unit` to `OcppTransmit` — `suspend (String) -> TransmitOutcome`, which is
  `Delivered` or `Gone(reason)`. `OcppSession.call()` turns `Gone` into
  `OcppResult.NotConnected`, so its documented promise — *"Never throws. Every outcome is an
  `OcppResult`"* — is now true; it previously rethrew whatever the transport raised, which the
  same file described as expected behaviour two paragraphs earlier. `send()` returns the outcome
  for the same reason and no longer returns a messageId; the frame is recorded in the event log
  before the attempt either way.

  A reply that cannot be delivered on the inbound path is now dropped deliberately rather than
  raising into whatever coroutine the consumer reads frames on. The ledger still holds the
  answer, so a retransmission after reconnect gets it back byte for byte (Part 4 §4.1.4).

  **Migration**: a transmit lambda that used to end in `Unit` now ends in `TransmitOutcome`.
  Wrap the send and report a dead connection as `Gone` rather than letting the exception out.
  Making the boundary total also makes it composable — retry, metrics or backpressure can wrap
  an `OcppTransmit`, which a throwing boundary cannot support.

- **`SessionRegistry.isConnected` is now `isRegistered`**, and `connectedStationIds` is
  `registeredStationIds`. Both only ever asked whether a session is in the map, which is not the
  same as being able to reach the station — a socket can die between the last frame and the
  teardown that unregisters it. The authoritative answer is what `StationCommandBus.send`
  returns.

### Fixed

- **A closed `OcppSession` now refuses inbound frames as well as outbound ones.** `closed` was
  consulted only by `call()`, so a session that had been closed while a transport was still (or
  again) attached would keep answering the peer's requests while refusing to originate anything —
  a half-dead peer that no real station or CSMS can be. `receive()` now drops the line without
  decoding, logging or answering, and `send()` refuses too — as `TransmitOutcome.Gone`, per the
  entry above. `call()` is unchanged; it already answered `OcppResult.NotConnected`.

  **This is a behaviour change.** A session belongs to one connection: when the peer comes back,
  open a new session instead of putting a fresh transport under the closed one. Nothing in this
  repository relied on the old behaviour — it was found by a test written against the simulator
  console, not by a consumer.

## [0.1.0] — 2026-08-21

The first release intended for use. Identical in behaviour to `0.0.1`; what changed is the
documentation around it.

### Changed

- The `docs/` set is now **English only**. `README.ko.md` remains as the Korean entry point
- The POM description for `ocpp-core` now states the two facts that decide adoption: **payloads are
  Jackson `JsonNode` with no generated DTOs**, and **the session layer is Kotlin-only** while the
  codec and schema layers are callable from Java

### Added

- A tag-triggered release workflow, so publishing no longer depends on one developer's machine

## [0.0.1] — 2026-08-21

The first publication. A rehearsal release that walked the whole path — signing, the Portal, and
resolution from Central by a consumer project — deliberately kept as a record rather than deleted.

### Added

- **`ocpp-core`** — OCPP-J framing (`OcppFrameCodec`), payload validation against the **181
  official OCPP 2.1 JSON schemas** (`OcppPayloadValidator`), and a session layer
  (`OcppSession`, `OcppSessions`, `InboundCallLedger`, `StationSerializer`) implementing response
  correlation, one in-flight CALL per connection, timeouts, and idempotent retransmission.
  Battery-swap wire vocabulary (`BatterySwapWire`, `AvailabilityState`, `DeviceModelVariables`)
  is machine-checked against the official schemas on every build
- **`swap-domain`** — the battery swap state machine, slot model, and invariants, with **zero
  dependencies** and no I/O. `SwapStateMachine.replay` rebuilds state from an event log
- The official schemas ship **inside the jar** under `dev/swapve/ocpp/schemas/`, alongside
  `META-INF/LICENSE` and `META-INF/NOTICE` — the OCA schemas are CC BY-ND 4.0 and are never
  transcribed into code

### Known limitations

- **No generated message DTOs.** Payloads are Jackson `JsonNode` / `ObjectNode`; correctness is
  decided by the official schema. A licensing consequence, not an oversight
- **The session layer is Kotlin-only** (coroutines). The codec and schema layers work from Java —
  measured by the `java-compat` module, see [docs/LAYERS.md](docs/LAYERS.md) §4
- **The consumer supplies the transport**, plus reconnection, backoff, and coroutine scope
- **No interoperability testing against a third-party implementation or real hardware.** Every test
  here runs against this project's own simulator
- Security profiles 2 and 3 (mTLS) are not implemented; OCPP 1.6J is not supported

[Unreleased]: https://github.com/ZANNABI-LAB/swapve/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/ZANNABI-LAB/swapve/releases/tag/v0.1.0
[0.0.1]: https://github.com/ZANNABI-LAB/swapve/releases/tag/v0.0.1
