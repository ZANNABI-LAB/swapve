import { disabledReason, faultText, OPS, slotsOf, type OpSpec, type OpVariant } from "../ops"
import type { Fault, OpForm, Station } from "../types"

/** 충전 조작만 인자를 받는다 — 슬롯과 올릴 비율. */
function ChargingArgs({
  station,
  form,
  onChange,
}: {
  station: Station
  form: OpForm
  onChange: (next: OpForm) => void
}) {
  return (
    <>
      <select
        value={form.slotId}
        onChange={e => onChange({ ...form, slotId: e.target.value })}
      >
        {slotsOf(station).map(slot => (
          <option key={slot.slotId} value={String(slot.slotId)}>slot {slot.slotId}</option>
        ))}
      </select>
      <input
        className="narrow"
        placeholder="byPercent"
        value={form.byPercent}
        onChange={e => onChange({ ...form, byPercent: e.target.value })}
      />
    </>
  )
}

function OpButton({
  station, spec, variant, form, faults, onOperate,
}: {
  station: Station
  spec: OpSpec
  variant: OpVariant | null
  form: OpForm
  faults: Fault[]
  onOperate: (op: string, params?: Record<string, unknown>) => void
}) {
  const needs = variant?.needs ?? spec.needs
  const reason = disabledReason(station, spec.op, form)
  let title = spec.effect
  if (needs) title += `\nBy hand this is ${needs}: ${faultText(faults, needs)}`

  return (
    <button
      type="button"
      disabled={!!reason}
      title={reason ? `cannot be pressed — ${reason}\n\n${title}` : title}
      onClick={() => {
        if (spec.args === "charging") {
          const charging: Record<string, unknown> = { slotId: Number(form.slotId) }
          // byPercent 를 화면에서 검사하지 않는다. 비운 채 누르면 서버의 400
          // ("advanceCharging 에는 byPercent 이 필요하다")이 그대로 보여야 한다.
          if (String(form.byPercent).trim() !== "") charging.byPercent = Number(form.byPercent)
          onOperate(spec.op, charging)
          return
        }
        onOperate(spec.op, variant?.params)
      }}
    >
      {variant ? variant.label : spec.label}
    </button>
  )
}

function OpRow(props: {
  station: Station
  group: "Acts" | "Reports"
  form: OpForm
  faults: Fault[]
  onForm: (next: OpForm) => void
  onOperate: (op: string, params?: Record<string, unknown>) => void
}) {
  const { station, group, form, faults, onForm, onOperate } = props
  return (
    <div className="row">
      <i>{group}</i>
      {OPS.filter(spec => spec.group === group).map(spec => (
        <span key={spec.op} style={{ display: "contents" }}>
          {spec.args === "charging" && (
            <ChargingArgs station={station} form={form} onChange={onForm} />
          )}
          {spec.variants
            ? spec.variants.map(variant => (
                <OpButton key={variant.label} station={station} spec={spec} variant={variant}
                  form={form} faults={faults} onOperate={onOperate} />
              ))
            : <OpButton station={station} spec={spec} variant={null}
                form={form} faults={faults} onOperate={onOperate} />}
        </span>
      ))}
    </div>
  )
}

/** 각본(교환 시작)과 **한 걸음**을 눈으로도 갈라 둔다 — 손이 닿는 결과가 다르다. */
export function OpPanel(props: {
  station: Station
  form: OpForm
  faults: Fault[]
  error: string | null
  onForm: (next: OpForm) => void
  onOperate: (op: string, params?: Record<string, unknown>) => void
}) {
  const { station, form, faults, error, onForm, onOperate } = props
  return (
    <fieldset className="ops">
      <legend>One step at a time — a single operation, not a script</legend>
      <div className="lede">
        No ordering is enforced. Pressing insertBatteries without authorize is exactly F5 —{" "}
        {faultText(faults, "F5")}
      </div>
      <OpRow station={station} group="Acts" form={form} faults={faults}
        onForm={onForm} onOperate={onOperate} />
      <OpRow station={station} group="Reports" form={form} faults={faults}
        onForm={onForm} onOperate={onOperate} />
      {error && <div className="error">{error}</div>}
    </fieldset>
  )
}
