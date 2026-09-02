/**
 * 서버 스냅샷의 모양 — `SimConsoleServer.snapshotOf` 가 싣는 것을 그대로 옮겼다.
 *
 * ⚠️ **옛 스냅샷에는 없을 수 있는 배열이 있다**(`slots` · `events` · `unsupportedActions`).
 * 없으면 빈 목록이지 오류가 아니므로 전부 선택 속성으로 둔다 — 손으로 쓰던 화면의
 * `slotsOf()` · `eventsOf()` 가 하던 일을 타입이 대신 강제한다.
 */
export interface Battery {
  serialNumber: string
  soC: number | null
  soH: number | null
}

export interface Slot {
  slotId: number
  role: "INSERT" | "DISPENSE" | string
  battery: Battery | null
  chargingTransactionId: string | null
  chargingSuspended: boolean
}

export interface FrameEvent {
  seq: number | null
  direction: "INBOUND" | "OUTBOUND" | string
  action: string | null
  occurredAt: string | null
}

export type Progress =
  | "ATTACHED"
  | "RUNNING"
  | "AWAITING_REMOTE_START"
  | "COMPLETED"
  | "REJECTED"
  | "FAILED"
  | string

export interface Station {
  stationId: string
  csmsUrl: string
  connectUrl: string
  connected: boolean
  swapOrder: string
  idToken: string
  idTokenType: string
  progress: Progress
  fault: string | null
  note: string | null
  error: string | null
  requestId: number | null
  messageCount: number
  subprotocol: string | null
  lastTransmitFailure: string | null
  lastCallTimeout: string | null
  slots?: Slot[]
  events?: FrameEvent[]
  unsupportedActions?: string[]
}

export interface Fault {
  id: string
  title: string
  expectation: string
}

export interface ConsoleState {
  defaultCsmsUrl: string
  faults: Fault[]
  stations: Station[]
}

/** 그 카드의 입력 위젯 값. **프로토콜 상태가 아니다** — 스테이션이 무엇을 하고 있는지는 스냅샷만 말한다. */
export interface OpForm {
  slotId: string
  byPercent: string
}
