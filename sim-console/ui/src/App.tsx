import { useCallback, useEffect, useState } from "react"
import { attachStation, detachStation, fetchState, operate, startSwap } from "./api"
import { AttachForm } from "./components/AttachForm"
import { StationCard } from "./components/StationCard"
import type { ConsoleState, OpForm, Station } from "./types"

const EMPTY: ConsoleState = { defaultCsmsUrl: "", faults: [], stations: [] }

function reason(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure)
}

/**
 * ★ **손으로 쓰던 화면에 있던 `focusMark`/`focusRestore` 가 사라졌다.**
 *
 * 예전에는 1초마다 카드를 통째로 다시 그렸기 때문에 타이핑 중이던 칸이 매초 초점을 잃었고,
 * 초점과 커서 자리를 손으로 저장·복원해야 했다. React 는 바뀐 노드만 고치므로 그 문제 자체가
 * 없다 — **이식으로 없어진 코드**이지 빠뜨린 것이 아니다.
 */
export function App() {
  const [state, setState] = useState<ConsoleState>(EMPTY)
  const [attachError, setAttachError] = useState<string | null>(null)
  const [opError, setOpError] = useState<Record<string, string | null>>({})
  const [forms, setForms] = useState<Record<string, OpForm>>({})
  const [eventsOpen, setEventsOpen] = useState<Record<string, boolean>>({})

  const refresh = useCallback(async () => {
    try {
      setState(await fetchState())
    } catch {
      // 폴링 실패는 화면을 지우지 않는다 — 직전 스냅샷이 남는 편이 빈 화면보다 낫다.
    }
  }, [])

  useEffect(() => {
    void refresh()
    // 교환이 진행되는 동안 슬롯이 바뀌는 것이 보여야 한다. 상태는 시뮬레이터에게 매번 묻는다.
    const timer = setInterval(() => void refresh(), 1000)
    return () => clearInterval(timer)
  }, [refresh])

  /** 슬롯 선택은 첫 슬롯을 골라 둔다 — 손으로 쓰던 `formOf()` 가 하던 일이다. */
  function formOf(station: Station): OpForm {
    const existing = forms[station.stationId]
    if (existing) return existing
    const first = station.slots?.[0]
    return { slotId: first ? String(first.slotId) : "", byPercent: "" }
  }

  return (
    <>
      <h1><span className="mark">▮</span> SwapVe — simulator control console</h1>
      <p className="lede">
        This drives the station <b>simulator</b> — it is the test system, not the CSMS. Swap history
        and metrics live behind the CSMS REST API.
      </p>

      <AttachForm
        defaultCsmsUrl={state.defaultCsmsUrl}
        error={attachError}
        onAttach={spec => {
          setAttachError(null)
          attachStation(spec).then(refresh).catch(f => setAttachError(reason(f)))
        }}
      />

      <h2>Attached stations</h2>
      {!state.stations.length ? (
        <p className="none">No stations are attached yet.</p>
      ) : (
        state.stations.map(station => (
          <StationCard
            key={station.stationId}
            station={station}
            faults={state.faults}
            form={formOf(station)}
            opError={opError[station.stationId] ?? null}
            eventsOpen={!!eventsOpen[station.stationId]}
            onForm={next => setForms(f => ({ ...f, [station.stationId]: next }))}
            onEventsOpen={open => setEventsOpen(o => ({ ...o, [station.stationId]: open }))}
            onOperate={(op, params) => {
              setOpError(e => ({ ...e, [station.stationId]: null }))
              operate(station.stationId, op, params)
                .then(refresh)
                // 조작의 실패는 그 카드에 남긴다. 서버가 무엇을 왜 거절했는지가 문장 그대로 보여야 한다.
                .catch(f => setOpError(e => ({ ...e, [station.stationId]: reason(f) })))
            }}
            onStartSwap={fault =>
              startSwap(station.stationId, fault).then(refresh).catch(f => setAttachError(reason(f)))}
            onDetach={() =>
              detachStation(station.stationId).then(refresh).catch(f => setAttachError(reason(f)))}
          />
        ))
      )}

      <h2>Fault injection — the failure scenarios of PLAN §5.4</h2>
      <ul className="faults">
        {state.faults.map(fault => (
          <li key={fault.id}>
            <b>{fault.id} {fault.title}</b>
            <span> — {fault.expectation}</span>
          </li>
        ))}
      </ul>
    </>
  )
}
