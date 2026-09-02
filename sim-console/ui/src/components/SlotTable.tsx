import { orDash, percent } from "../format"
import { slotsOf } from "../ops"
import type { Station } from "../types"

/** 붙지 않은 스테이션의 슬롯은 그리지 않는다 — 받지 않은 값은 — 로 둔다. */
export function SlotTable({ station }: { station: Station }) {
  const slots = slotsOf(station)
  if (!slots.length) return <p className="none">No slot information</p>

  return (
    <table>
      <tbody>
        <tr>
          {["Slot", "Role", "Battery", "SoC", "SoH", "Charging transaction"].map(title => (
            <th key={title}>{title}</th>
          ))}
        </tr>
        {slots.map(slot => (
          <tr key={slot.slotId}>
            <td>{slot.slotId}</td>
            <td>{slot.role === "INSERT" ? "insert" : slot.role === "DISPENSE" ? "dispense" : "—"}</td>
            {slot.battery ? (
              <>
                <td>{slot.battery.serialNumber}</td>
                <td>{percent(slot.battery.soC)}</td>
                <td>{percent(slot.battery.soH)}</td>
              </>
            ) : (
              <>
                <td className="empty">empty</td>
                <td className="empty">—</td>
                <td className="empty">—</td>
              </>
            )}
            {/* 급전 멈춤은 트랜잭션 칸 **안**에 적는다. 멈춘 것은 트랜잭션이 아니라 에너지
                흐름이고, 두 값을 나란히 두어야 "트랜잭션은 살아 있는데 급전만 멈췄다"가
                한 눈에 읽힌다. */}
            <td>
              {orDash(slot.chargingTransactionId)}
              {slot.chargingSuspended && (
                <span className="suspended">{"  ·  charging suspended (ceiling reached)"}</span>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
