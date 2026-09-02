/** 모르는 값은 모른다고 적는다. 그럴듯한 기본값으로 채우지 않는다. */
export function orDash(value: unknown): string {
  return value === null || value === undefined || value === "" ? "—" : String(value)
}

export function percent(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : `${value.toFixed(1)}%`
}

/** CSMS 의 REST 주소는 WebSocket URL 과 같은 호스트다. F1 을 걸 때 안내에 쓴다. */
export function csmsHost(wsUrl: string): string {
  return String(wsUrl).replace(/^wss?:\/\//, "").split("/")[0] ?? ""
}
