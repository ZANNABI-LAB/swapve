import { csmsHost, orDash } from "../format"
import type { Fault, OpForm, Station } from "../types"
import { EventTable } from "./EventTable"
import { OpPanel } from "./OpPanel"
import { SlotTable } from "./SlotTable"

export function StationCard(props: {
  station: Station
  faults: Fault[]
  form: OpForm
  opError: string | null
  eventsOpen: boolean
  onForm: (next: OpForm) => void
  onEventsOpen: (open: boolean) => void
  onOperate: (op: string, params?: Record<string, unknown>) => void
  onStartSwap: (fault: string | null) => void
  onDetach: () => void
}) {
  const { station, faults, form, opError, eventsOpen } = props

  return (
    <div className="card">
      <header>
        <b>{station.stationId}</b>
        <span className={`badge ${station.progress}`}>{station.progress}</span>
        <span className={`badge ${station.connected ? "live" : "down"}`}>
          {station.connected ? "connected" : "disconnected"}
        </span>
        {/* 협상 결과는 붙어 있을 때만 있다. 없으면 배지 자체를 그리지 않는다 — 모르는 값이다. */}
        {station.subprotocol && <span className="badge sub">{station.subprotocol}</span>}
        {station.fault && <span className="badge">fault {station.fault}</span>}
      </header>

      <div className="meta">
        {station.connectUrl}
        {"  ·  order "}{station.swapOrder}
        {"  ·  token "}{station.idToken}/{station.idTokenType}
        {"  ·  requestId "}{orDash(station.requestId)}
        {"  ·  "}{station.messageCount} messages exchanged
      </div>

      <SlotTable station={station} />

      {station.progress === "AWAITING_REMOTE_START" && (
        <div className="hint">
          {"This scenario has to be started by the CSMS (S02). From another terminal:\n"}
          {`curl -X POST ${csmsHost(station.csmsUrl)}/api/swaps -H 'Content-Type: application/json' \\\n`}
          {`     -d '{"stationId":"${station.stationId}","idToken":{"idToken":"${station.idToken}","type":"${station.idTokenType}"}}'`}
        </div>
      )}

      {/* 마지막으로 시도한 전송이 나가지 못했다. **보여 주기만 한다** — 이 값으로 버튼을
          비활성으로 만들지 않는다(disabledReason 은 이것을 읽지 않는다). 비활성은 서버가
          확실히 거절할 사실에만 쓰고, 전송 실패는 다시 눌러 볼 수 있는 상황이다. */}
      {station.lastTransmitFailure && (
        <div className="error">The last transmission never left: {station.lastTransmitFailure}</div>
      )}
      {/* 위 줄과 **다른 사실이다** — 저쪽은 나가지 못한 것이고 이쪽은 나갔는데 답이 오지
          않은 것이다. 프레임이 선로에 올랐으므로 상대가 처리했을 수도 있고, 그래서 다음 수가
          다르다(재전송이 멱등해야 하는 상황이다). 한 줄로 합치면 그 구분이 사라진다. */}
      {station.lastCallTimeout && (
        <div className="error">Sent, but no answer came back: {station.lastCallTimeout}</div>
      )}
      {/* CSMS 가 모른다고 답한 action 들. 위 줄과 달리 **쌓인다** — 상대가 그것을 모른다는
          사실은 나중에 거짓이 되지 않는다. 여기도 보여 주기만 한다: 모른다고 했으니 다음부터
          보내지 않는 것은 시험계가 시험 대상에 맞춰 조용해지는 일이고, 그러면 그 답이
          틀렸을 때 화면에 아무것도 남지 않는다. */}
      {station.unsupportedActions && station.unsupportedActions.length > 0 && (
        <div className="note">
          Actions the CSMS said it does not know (notifications, so we carried on):{" "}
          {station.unsupportedActions.join(", ")}
        </div>
      )}
      {station.note && <div className="note">{station.note}</div>}
      {station.error && <div className="error">{station.error}</div>}

      <div className="actions">
        <button type="button" onClick={() => props.onStartSwap(null)}>Start exchange</button>
        {faults.map(fault => (
          <button key={fault.id} type="button" title={fault.expectation}
            onClick={() => props.onStartSwap(fault.id)}>
            {fault.id} {fault.title}
          </button>
        ))}
        <button type="button" onClick={props.onDetach}>Detach</button>
      </div>

      <OpPanel station={station} form={form} faults={faults} error={opError}
        onForm={props.onForm} onOperate={props.onOperate} />
      <EventTable station={station} open={eventsOpen} onToggle={props.onEventsOpen} />
    </div>
  )
}
