import type { ConsoleState } from "./types"

/**
 * 서버가 거절하면 **그 사유를 문장 그대로** 던진다 — 화면이 자기 말로 바꾸지 않는다.
 * 무엇을 왜 거절했는지는 서버만 안다.
 */
export async function request<T = unknown>(
  method: string,
  path: string,
  body?: unknown,
): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const json = await response.json().catch(() => ({}) as Record<string, unknown>)
  if (!response.ok) {
    const reason = (json as { error?: string }).error ?? "The request failed"
    throw new Error(`${reason} (${response.status})`)
  }
  return json as T
}

export function fetchState(): Promise<ConsoleState> {
  return request<ConsoleState>("GET", "/api/state")
}

export function attachStation(spec: {
  csmsUrl: string
  stationId: string
  slots: number
  setSize: number
  swapOrder: string
  idToken: string
}) {
  return request("POST", "/api/stations", spec)
}

export function detachStation(stationId: string) {
  return request("DELETE", `/api/stations/${encodeURIComponent(stationId)}`)
}

export function startSwap(stationId: string, fault: string | null) {
  return request("POST", `/api/stations/${encodeURIComponent(stationId)}/swap`, { fault })
}

export function operate(stationId: string, op: string, params?: Record<string, unknown>) {
  return request("POST", `/api/stations/${encodeURIComponent(stationId)}/op`, { op, ...params })
}
