import { orDash } from "../format"
import { eventsOf } from "../ops"
import type { Station } from "../types"

/**
 * 오간 프레임의 꼬리. **본문 칸은 없다** — 스냅샷이 프레임 내용을 싣지 않기 때문이고,
 * 내용을 봐야 한다면 그건 CSMS 의 이벤트 로그 API 가 할 일이다.
 */
export function EventTable({
  station,
  open,
  onToggle,
}: {
  station: Station
  open: boolean
  onToggle: (open: boolean) => void
}) {
  const events = eventsOf(station)
  return (
    <details open={open} onToggle={e => onToggle((e.currentTarget as HTMLDetailsElement).open)}>
      <summary>{events.length} frames exchanged (newest first)</summary>
      {!events.length ? (
        <p className="none">No frames have been exchanged yet.</p>
      ) : (
        <table>
          <tbody>
            <tr>
              {["seq", "direction", "action", "time"].map(t => <th key={t}>{t}</th>)}
            </tr>
            {/* 스냅샷이 이미 최신을 앞에 두고 내려온다. 화면에서 다시 정렬하지 않는다. */}
            {events.map((event, i) => (
              <tr key={`${event.seq}-${i}`}>
                <td>{orDash(event.seq)}</td>
                <td>
                  {event.direction === "OUTBOUND" ? "sent"
                    : event.direction === "INBOUND" ? "received"
                    : orDash(event.direction)}
                </td>
                <td>{orDash(event.action)}</td>
                <td>{orDash(event.occurredAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </details>
  )
}
