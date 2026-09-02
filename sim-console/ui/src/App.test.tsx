import { render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { beforeEach, describe, expect, it, vi } from "vitest"
import { StationCard } from "./components/StationCard"
import type { Fault, Station } from "./types"

const faults: Fault[] = [
  { id: "F1", title: "no battery available", expectation: "The station answers Rejected" },
]

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
    messageCount: 7,
    subprotocol: "ocpp2.1",
    lastTransmitFailure: null,
    lastCallTimeout: null,
    slots: [
      { slotId: 1, role: "INSERT", battery: null, chargingTransactionId: null, chargingSuspended: false },
      { slotId: 2, role: "DISPENSE", battery: { serialNumber: "B-9", soC: 95.4, soH: 99.1 },
        chargingTransactionId: "tx-1", chargingSuspended: true },
    ],
    events: [{ seq: 2, direction: "OUTBOUND", action: "BatterySwap", occurredAt: "2026-09-02T00:00:00Z" }],
    ...over,
  }
}

function renderCard(over: Partial<Station> = {}, onOperate = vi.fn()) {
  const s = station(over)
  render(
    <StationCard
      station={s}
      faults={faults}
      form={{ slotId: "1", byPercent: "" }}
      opError={null}
      eventsOpen={false}
      onForm={vi.fn()}
      onEventsOpen={vi.fn()}
      onOperate={onOperate}
      onStartSwap={vi.fn()}
      onDetach={vi.fn()}
    />,
  )
  return { station: s, onOperate }
}

beforeEach(() => vi.clearAllMocks())

describe("스테이션 카드가 실제로 그려진다", () => {
  it("모르는 값은 대시로, 아는 값은 그대로 적는다", () => {
    renderCard()
    expect(screen.getByText("CS001")).toBeInTheDocument()
    // requestId 는 아직 없다 — 그럴듯한 기본값으로 채우지 않는다
    expect(screen.getByText(/requestId\s+—/)).toBeInTheDocument()
    expect(screen.getByText(/7 messages exchanged/)).toBeInTheDocument()
  })

  it("빈 슬롯과 채워진 슬롯을 갈라 그리고, SoC 는 소수 한 자리다", () => {
    renderCard()
    expect(screen.getByText("empty")).toBeInTheDocument()
    expect(screen.getByText("95.4%")).toBeInTheDocument()
    expect(screen.getByText("99.1%")).toBeInTheDocument()
  })

  it("★ 급전 멈춤은 트랜잭션 칸 안에 적는다 — 트랜잭션은 살아 있다", () => {
    renderCard()
    const cell = screen.getByText("tx-1").closest("td")
    expect(cell).not.toBeNull()
    expect(within(cell!).getByText(/charging suspended/)).toBeInTheDocument()
  })

  it("협상 결과가 없으면 배지 자체를 그리지 않는다 — 모르는 값이다", () => {
    renderCard({ subprotocol: null })
    expect(screen.queryByText("ocpp2.1")).not.toBeInTheDocument()
  })

  it("★ 전송 실패와 응답 없음은 다른 사실이라 따로 적는다", () => {
    renderCard({ lastTransmitFailure: "socket closed", lastCallTimeout: "no answer" })
    expect(screen.getByText(/The last transmission never left: socket closed/)).toBeInTheDocument()
    expect(screen.getByText(/Sent, but no answer came back: no answer/)).toBeInTheDocument()
  })

  it("CSMS 가 모른다고 한 action 은 쌓아서 보여 준다", () => {
    renderCard({ unsupportedActions: ["NotifyEvent", "LogStatusNotification"] })
    expect(screen.getByText(/NotifyEvent, LogStatusNotification/)).toBeInTheDocument()
  })

  it("CSMS 가 시작해야 하는 시나리오면 붙여 쓸 curl 을 화면에 적는다", () => {
    renderCard({ progress: "AWAITING_REMOTE_START" })
    expect(screen.getByText(/curl -X POST localhost:8080\/api\/swaps/)).toBeInTheDocument()
  })
})

describe("버튼이 판정대로 움직인다", () => {
  it("누를 수 없는 버튼은 이유를 title 에 달고 비활성이다", () => {
    renderCard({ connected: true })
    const connect = screen.getByRole("button", { name: "connect" })
    expect(connect).toBeDisabled()
    expect(connect.title).toMatch(/cannot be pressed — already attached/)
  })

  it("★ 각본이 도는 중에는 조작 버튼이 전부 잠긴다", () => {
    renderCard({ progress: "RUNNING" })
    expect(screen.getByRole("button", { name: "authorize" })).toBeDisabled()
    expect(screen.getByRole("button", { name: "boot" })).toBeDisabled()
  })

  it("누를 수 있는 버튼은 그 조작을 그대로 올려보낸다", async () => {
    const { onOperate } = renderCard()
    await userEvent.click(screen.getByRole("button", { name: "authorize" }))
    expect(onOperate).toHaveBeenCalledWith("authorize", undefined)
  })

  it("재전송은 변종마다 다른 파라미터를 싣는다 (F6 는 같은 messageId)", async () => {
    const { onOperate } = renderCard()
    await userEvent.click(screen.getByRole("button", { name: /same messageId \(F6\)/ }))
    expect(onOperate).toHaveBeenCalledWith("resendLastBatterySwap", { sameMessageId: true })
  })

  it("★ byPercent 를 비운 채 눌러도 화면이 막지 않는다 — 서버의 400 이 보여야 한다", async () => {
    const { onOperate } = renderCard({
      slots: [{ slotId: 1, role: "INSERT", battery: { serialNumber: "B-1", soC: 10, soH: 90 },
        chargingTransactionId: null, chargingSuspended: false }],
    })
    await userEvent.click(screen.getByRole("button", { name: "advanceCharging" }))
    expect(onOperate).toHaveBeenCalledWith("advanceCharging", { slotId: 1 })
  })

  it("장애 시나리오 버튼은 서버가 준 문구를 그대로 단다", () => {
    renderCard()
    const f1 = screen.getByRole("button", { name: /F1 no battery available/ })
    expect(f1.title).toBe("The station answers Rejected")
  })
})
