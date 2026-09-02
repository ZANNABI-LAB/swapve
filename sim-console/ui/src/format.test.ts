import { describe, expect, it } from "vitest"
import { csmsHost, orDash, percent } from "./format"

describe("모르는 값은 모른다고 적는다", () => {
  it("orDash 는 빈 값만 대시로 바꾼다 — 0 과 false 는 값이다", () => {
    expect(orDash(null)).toBe("—")
    expect(orDash(undefined)).toBe("—")
    expect(orDash("")).toBe("—")
    expect(orDash(0)).toBe("0")
    expect(orDash(false)).toBe("false")
  })

  it("percent 는 소수 한 자리로 적고, 없으면 대시다", () => {
    expect(percent(12.34)).toBe("12.3%")
    expect(percent(0)).toBe("0.0%")
    expect(percent(null)).toBe("—")
  })

  it("csmsHost 는 WebSocket URL 에서 호스트만 남긴다", () => {
    expect(csmsHost("ws://localhost:8080/ocpp")).toBe("localhost:8080")
    expect(csmsHost("wss://csms.example.com/ocpp/CS001")).toBe("csms.example.com")
  })
})
