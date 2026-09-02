import type { Fault, OpForm, Slot, Station } from "./types"

/**
 * 조작 한 걸음의 목록 — `StationOp.wireValue` 13 가지를 그대로 옮긴 순수 데이터다.
 *
 * `group` 은 docs/VIRTUAL-STATION.md §1 이 가른 Acts(사실을 바꾸고 알린다) /
 * Reports(말할 뿐 아무것도 바꾸지 않는다) 를 그대로 쓴다. Scripts 는 여기 없다 —
 * 교환 시작 버튼이 이미 그 자리다.
 *
 * `needs` 는 손으로 재현했을 때 걸리는 장애 시나리오의 id 다. 문구 자체는 여기 적지 않고
 * 서버가 준 `faults` 에서 가져온다 — 코드와 화면에 같은 설명이 두 벌 생기지 않게 한다.
 */
export type OpGroup = "Acts" | "Reports"

export interface OpVariant {
  label: string
  params: Record<string, unknown>
  needs?: string
}

export interface OpSpec {
  op: string
  label: string
  group: OpGroup
  effect: string
  needs?: string
  args?: "charging"
  variants?: OpVariant[]
}

export const OPS: OpSpec[] = [
  { op: "connect", label: "connect", group: "Acts",
    effect: "Opens the transport. Nothing but the handshake goes out" },
  { op: "disconnect", label: "disconnect", group: "Acts",
    effect: "Closes the socket only — the session, the slots and the requestId stay alive" },
  { op: "reconnect", label: "reconnect", group: "Acts",
    effect: "Reopens the transport over the same session and slots" },
  { op: "boot", label: "boot", group: "Acts",
    effect: "BootNotification → a NotifyEvent per slot → SecurityEventNotification → a TransactionEvent(Started) for every slot holding a battery" },
  { op: "reboot", label: "reboot", group: "Acts",
    effect: "Drops and reconnects, then reopens under fresh transaction ids (S04.FR.11). The batteries stay where they are" },
  { op: "insertBatteries", label: "insertBatteries", group: "Acts", needs: "F5",
    effect: "The insert slots take a battery and one BatterySwap(BatteryIn) goes out" },
  { op: "removeBatteries", label: "removeBatteries", group: "Acts",
    effect: "The dispense slots give up their battery and one BatterySwap(BatteryOut) goes out" },
  { op: "advanceCharging", label: "advanceCharging", group: "Acts", args: "charging",
    effect: "Raises the SoC and sends a TransactionEvent(Updated). At the ceiling the transaction stays open and only the charging stops" },

  { op: "authorize", label: "authorize", group: "Reports",
    effect: "Sends an Authorize. Not one slot moves" },
  { op: "reportChargingStarted", label: "reportChargingStarted", group: "Reports",
    effect: "A TransactionEvent(Updated, Charging) per filled slot. Nothing inside changes" },
  { op: "reportBatteryOutTimeout", label: "reportBatteryOutTimeout", group: "Reports", needs: "F2",
    effect: "A BatterySwap(BatteryOutTimeout) carrying the uncollected battery. The battery stays in its slot" },
  { op: "resendLastBatterySwap", label: "resendLastBatterySwap", group: "Reports",
    effect: "Sends the last BatterySwap frame again",
    variants: [
      { label: "same messageId (F6)", params: { sameMessageId: true }, needs: "F6" },
      { label: "new messageId (F4)", params: { sameMessageId: false }, needs: "F4" },
    ] },
]

export function slotsOf(station: Station): Slot[] {
  return station.slots ?? []
}

export function eventsOf(station: Station) {
  return station.events ?? []
}

/** F1~F6 의 문구는 서버가 내주는 것 하나뿐이다. 화면이 따로 적어 두지 않는다. */
export function faultText(faults: Fault[], id: string | undefined): string {
  if (!id) return ""
  return faults.find(f => f.id === id)?.expectation ?? ""
}

export function selectedSlot(station: Station, form: OpForm): Slot | null {
  return slotsOf(station).find(slot => String(slot.slotId) === String(form.slotId)) ?? null
}

/**
 * 지금 이 버튼을 누를 수 없는 이유. 누를 수 있으면 `null` 이다.
 *
 * 판정에 쓰는 것은 **그 순간의 스냅샷과 그 카드의 폼 값**뿐이다. 화면은 "인가됨" 같은
 * 순서 상태를 절대 기억하지 않는다 — 그런 게이트를 콘솔이 새로 만들면 authorize 없는
 * insertBatteries(=F5)를 영영 걸 수 없게 되고, 그건 docs/VIRTUAL-STATION.md §3 이
 * 일부러 열어 둔 문이다. 여기서 막는 것은 순서가 아니라 **서버가 확실히 거절할 사실**뿐이다.
 *
 * ★ 손으로 쓰던 화면에서는 이 함수가 DOM 조립 코드 한가운데 있어 시험할 수 없었다.
 * 분리한 것이 이식의 실질적 이득이고, `ops.test.ts` 가 분기를 붙든다.
 */
export function disabledReason(station: Station, op: string, form: OpForm): string | null {
  if (station.progress === "RUNNING" || station.progress === "AWAITING_REMOTE_START") {
    return "a script is running — the server would answer 409"
  }
  if (op === "connect" || op === "reconnect") {
    return station.connected ? "already attached" : null
  }
  // 상대가 먼저 닫으면 소켓은 죽었는데 전송 객체는 남는다. 그때 connected 는 거짓이지만
  // connect/reconnect 는 서버에서 "이미 연결돼 있다"로 막히므로, disconnect 를 함께 막으면
  // 화면에서 되살릴 길이 없어진다. 붙어 있지 않을 때 눌러도 하는 일이 없을 뿐이다.
  if (op === "disconnect") return null
  if (!station.connected) return "not attached — connect first"

  if (op === "insertBatteries") {
    const empty = slotsOf(station).filter(slot => slot.role === "INSERT" && !slot.battery)
    return empty.length ? null : "every insert slot is full — there is nowhere to put one"
  }
  if (op === "removeBatteries") {
    const loaded = slotsOf(station).filter(slot => slot.role === "DISPENSE" && slot.battery)
    return loaded.length ? null : "no battery in the dispense slots to give out"
  }
  if (op === "advanceCharging") {
    const slot = selectedSlot(station, form)
    if (!slot) return "no slot chosen to charge"
    return slot.battery ? null : `slot ${slot.slotId} is empty — an empty slot does not charge`
  }
  return null
}
