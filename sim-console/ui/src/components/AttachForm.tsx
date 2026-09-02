import { useState } from "react"

export function AttachForm({
  defaultCsmsUrl,
  error,
  onAttach,
}: {
  defaultCsmsUrl: string
  error: string | null
  onAttach: (spec: {
    csmsUrl: string; stationId: string; slots: number
    setSize: number; swapOrder: string; idToken: string
  }) => void
}) {
  const [csmsUrl, setCsmsUrl] = useState("")
  const [stationId, setStationId] = useState("CS001")
  const [slots, setSlots] = useState("4")
  const [setSize, setSetSize] = useState("2")
  const [swapOrder, setSwapOrder] = useState("In-Out")
  const [idToken, setIdToken] = useState("RFID-0001")

  // 서버가 준 기본값은 사용자가 손대기 전까지만 쓴다 — 타이핑을 덮어쓰지 않는다.
  const url = csmsUrl || defaultCsmsUrl

  return (
    <fieldset>
      <legend>Attach a station</legend>
      <label>CSMS URL <span>without the station id</span>
        <input value={url} onChange={e => setCsmsUrl(e.target.value)} />
      </label>
      <label>stationId <span>station identifier</span>
        <input value={stationId} onChange={e => setStationId(e.target.value)} />
      </label>
      <label>Slots <span>= EVSE count</span>
        <input className="narrow" value={slots} onChange={e => setSlots(e.target.value)} />
      </label>
      <label>Set <span>batteries per exchange</span>
        <input className="narrow" value={setSize} onChange={e => setSetSize(e.target.value)} />
      </label>
      <label>Order <span>SwapOrder</span>
        <select value={swapOrder} onChange={e => setSwapOrder(e.target.value)}>
          <option>In-Out</option>
          <option>Out-In</option>
        </select>
      </label>
      <label>Authorization token <span>IdToken</span>
        <input value={idToken} onChange={e => setIdToken(e.target.value)} />
      </label>
      <button type="button" onClick={() => onAttach({
        csmsUrl: url, stationId, slots: Number(slots),
        setSize: Number(setSize), swapOrder, idToken,
      })}>Attach</button>
      {error && <div className="error">{error}</div>}
    </fieldset>
  )
}
