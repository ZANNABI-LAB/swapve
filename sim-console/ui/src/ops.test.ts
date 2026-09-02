import { describe, expect, it } from "vitest"
import { disabledReason, faultText, selectedSlot, slotsOf } from "./ops"
import type { OpForm, Slot, Station } from "./types"

function slot(over: Partial<Slot> = {}): Slot {
  return {
    slotId: 1,
    role: "INSERT",
    battery: null,
    chargingTransactionId: null,
    chargingSuspended: false,
    ...over,
  }
}

function station(over: Partial<Station> = {}): Station {
  return {
    stationId: "CS001",
    csmsUrl: "ws://localhost:8080/ocpp",
    connectUrl: "ws://localhost:8080/ocpp/CS001",
    connected: true,
    swapOrder: "In-Out",
    idToken: "RFID-0001",
    idTokenType: "ISO14443",
    progress: "ATTACHED",
    fault: null,
    note: null,
    error: null,
    requestId: null,
    messageCount: 0,
    subprotocol: "ocpp2.1",
    lastTransmitFailure: null,
    lastCallTimeout: null,
    slots: [],
    events: [],
    ...over,
  }
}

const form: OpForm = { slotId: "1", byPercent: "" }
const battery = { serialNumber: "B-1", soC: 50, soH: 99 }

describe("disabledReason — 서버가 확실히 거절할 사실만 막는다", () => {
  it("각본이 도는 중에는 무엇도 누를 수 없다", () => {
    for (const progress of ["RUNNING", "AWAITING_REMOTE_START"]) {
      expect(disabledReason(station({ progress }), "connect", form))
        .toBe("a script is running — the server would answer 409")
    }
  })

  it("붙어 있으면 connect·reconnect 가 막히고, 아니면 열린다", () => {
    expect(disabledReason(station({ connected: true }), "connect", form)).toBe("already attached")
    expect(disabledReason(station({ connected: true }), "reconnect", form)).toBe("already attached")
    expect(disabledReason(station({ connected: false }), "connect", form)).toBeNull()
  })

  it("★ disconnect 는 붙어 있지 않아도 막지 않는다 — 막으면 되살릴 길이 없어진다", () => {
    // 상대가 먼저 닫으면 connected 는 거짓인데 서버는 connect 를 "이미 연결됨"으로 막는다.
    // 그때 disconnect 까지 막으면 화면에서 빠져나올 수 없다.
    expect(disabledReason(station({ connected: false }), "disconnect", form)).toBeNull()
  })

  it("붙어 있지 않으면 나머지 조작은 막힌다", () => {
    expect(disabledReason(station({ connected: false }), "authorize", form))
      .toBe("not attached — connect first")
  })

  it("★ 순서를 게이트로 만들지 않는다 — authorize 없이 insertBatteries 가 눌린다(=F5)", () => {
    // docs/VIRTUAL-STATION.md §3 이 일부러 열어 둔 문이다. 콘솔이 막으면 F5 를 영영 못 건다.
    const s = station({ slots: [slot({ role: "INSERT", battery: null })] })
    expect(disabledReason(s, "insertBatteries", form)).toBeNull()
  })

  it("넣을 자리가 없으면 insertBatteries 가 막힌다", () => {
    const s = station({ slots: [slot({ role: "INSERT", battery })] })
    expect(disabledReason(s, "insertBatteries", form))
      .toBe("every insert slot is full — there is nowhere to put one")
  })

  it("내줄 배터리가 없으면 removeBatteries 가 막힌다", () => {
    const empty = station({ slots: [slot({ slotId: 2, role: "DISPENSE", battery: null })] })
    expect(disabledReason(empty, "removeBatteries", form))
      .toBe("no battery in the dispense slots to give out")

    const loaded = station({ slots: [slot({ slotId: 2, role: "DISPENSE", battery })] })
    expect(disabledReason(loaded, "removeBatteries", form)).toBeNull()
  })

  it("빈 슬롯은 충전되지 않고, 고른 슬롯이 없으면 그것부터 말한다", () => {
    const s = station({ slots: [slot({ slotId: 1, battery: null })] })
    expect(disabledReason(s, "advanceCharging", form))
      .toBe("slot 1 is empty — an empty slot does not charge")

    expect(disabledReason(station({ slots: [] }), "advanceCharging", form))
      .toBe("no slot chosen to charge")

    const filled = station({ slots: [slot({ slotId: 1, battery })] })
    expect(disabledReason(filled, "advanceCharging", form)).toBeNull()
  })

  it("★ 전송 실패·타임아웃·미지원 목록은 버튼을 막지 않는다 — 보여 주기만 한다", () => {
    // 비활성은 서버가 확실히 거절할 사실에만 쓴다. 전송 실패는 다시 눌러 볼 수 있는 상황이다.
    const s = station({
      lastTransmitFailure: "socket closed",
      lastCallTimeout: "no answer for BatterySwap",
      unsupportedActions: ["NotifyEvent"],
    })
    expect(disabledReason(s, "authorize", form)).toBeNull()
  })
})

describe("보조 함수", () => {
  it("옛 스냅샷에 배열이 없어도 빈 목록이지 오류가 아니다", () => {
    const s = station()
    delete (s as Partial<Station>).slots
    expect(slotsOf(s)).toEqual([])
  })

  it("슬롯 선택은 문자열·숫자를 가리지 않는다", () => {
    const s = station({ slots: [slot({ slotId: 3, battery })] })
    expect(selectedSlot(s, { slotId: "3", byPercent: "" })?.slotId).toBe(3)
    expect(selectedSlot(s, { slotId: "9", byPercent: "" })).toBeNull()
  })

  it("장애 문구는 서버가 준 것만 쓴다 — 화면이 따로 적어 두지 않는다", () => {
    const faults = [{ id: "F5", title: "no authorize", expectation: "CSMS rejects it" }]
    expect(faultText(faults, "F5")).toBe("CSMS rejects it")
    expect(faultText(faults, "F9")).toBe("")
    expect(faultText(faults, undefined)).toBe("")
  })
})
