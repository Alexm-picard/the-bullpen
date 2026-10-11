/**
 * Section 2, Drift (SPEC-ops 6.3 / 6.4): PSI per feature grouped by model, ECE per pitch model,
 * in ink. Each value sits beside a fixed-scale rule with the alert threshold drawn as a tick.
 *
 *   over        heavier ink bar + weight 700 + the word "over"   (value > line, n >= floor)
 *   under       ink2 bar
 *   thin        hollow bar + ink3 value + "n under 300, not counted"
 *   awaiting    em-dash + "awaiting first value"    (watched, no row yet)
 *   not read    em-dash + "not read"                (the drift query failed)
 *
 * No hue anywhere: the distinction is carried by the printed word and the weight. The scale is
 * aria-hidden; removing it changes no visible text.
 */
import type { DriftCell, DriftGroup } from "../../api/ops-rows";
import { DRIFT_THRESHOLDS as T } from "../../data/ops-claims";
import { isCountable, isEceOver, isPsiOver } from "../../lib/ops-status";

import { DASH } from "./ops-format";
import { Fresh } from "./register-section";

const COUNT = new Intl.NumberFormat("en-US");

function Scale({
  value,
  max,
  line,
  over,
  thin,
}: {
  value: number | null;
  max: number;
  line: number;
  over: boolean;
  thin: boolean;
}) {
  if (value === null) return null;
  const share = Math.min(Math.max(value / max, 0), 1);
  return (
    <div className="op-scale" aria-hidden="true">
      <span className="op-scale__axis" />
      <span
        className="op-scale__bar"
        data-over={over ? "true" : undefined}
        data-thin={thin ? "true" : undefined}
        style={{ transform: `scaleX(${share})` }}
      />
      <span
        className="op-scale__tick"
        style={{ left: `${(line / max) * 100}%` }}
      />
    </div>
  );
}

function ReadsNote({
  cell,
  offline,
  over,
  thin,
}: {
  cell: DriftCell;
  offline: boolean;
  over: boolean;
  thin: boolean;
}) {
  if (offline) return <span className="op-faint">not read</span>;
  if (cell.value === null)
    return <span className="op-faint">awaiting first value</span>;
  if (thin)
    return (
      <span className="op-faint">{`n under ${T.psiMinSample}, not counted`}</span>
    );
  if (over)
    return <>over{cell.tag ? <span className="op-flag">drill</span> : null}</>;
  return null;
}

export function DriftKey() {
  return (
    <p className="op-key" aria-hidden="true">
      <span>
        <i />
        Under the line
      </span>
      <span>
        <i data-k="over" />
        Over (ink, heavier)
      </span>
      <span>
        <i data-k="thin" />
        Too few rows to count
      </span>
      <span>
        <i data-k="tick" />
        Alert line
      </span>
    </p>
  );
}

/** [175]: drill drift stays visible (that is the drill working) but is never read as organic. */
export function DrillNotice({ psi }: { psi: readonly DriftGroup[] }) {
  const tagged = psi.flatMap((g) => g.cells).filter((c) => c.tag !== null);
  if (tagged.length === 0) return null;
  const features = tagged.map((c) => c.key).join(", ");
  const tags = [...new Set(tagged.map((c) => c.tag))].join(", ");
  const plural = tagged.length > 1;
  return (
    <p className="op-drill" role="status">
      <b>* Deliberate synthetic drift.</b>{" "}
      {`The ${features} ${plural ? "rows carry" : "row carries"} the drill tag ${tags} (decision [175]): drift injected on purpose to prove the alert path, not organic production drift.`}
    </p>
  );
}

function Row({
  cell,
  offline,
  kind,
}: {
  cell: DriftCell;
  offline: boolean;
  kind: "psi" | "ece";
}) {
  const shown = offline ? null : cell.value;
  const live = { ...cell, value: shown };
  const thin =
    kind === "psi" && shown !== null && !isCountable(live, T) ? true : false;
  const over =
    shown !== null &&
    (kind === "psi" ? isPsiOver(live, T) : isEceOver(live, T));
  const label = kind === "psi" ? cell.key : cell.modelName;
  const digits = kind === "psi" ? 2 : 3;
  return (
    <tr>
      <td>
        {label}
        {cell.tag && !offline ? <sup>*</sup> : null}
        <span className="op-phone-only op-sub">
          <ReadsNote cell={live} offline={offline} over={over} thin={thin} />
        </span>
      </td>
      <td className="op-num">
        {shown === null ? (
          <span className="op-faint">{DASH}</span>
        ) : (
          <span className={over ? "op-strong" : thin ? "op-faint" : undefined}>
            <Fresh value={shown.toFixed(digits)} />
          </span>
        )}
      </td>
      <td className="ed-barcell">
        <Scale
          value={shown}
          max={kind === "psi" ? T.psiScaleMax : T.eceScaleMax}
          line={kind === "psi" ? T.psiNotice : T.ecePage}
          over={over}
          thin={thin}
        />
      </td>
      <td className="op-num op-hide-phone op-faint">
        {shown === null || cell.sampleSize === null
          ? ""
          : COUNT.format(cell.sampleSize)}
      </td>
      <td className="op-pad op-word op-hide-phone">
        <ReadsNote cell={live} offline={offline} over={over} thin={thin} />
      </td>
    </tr>
  );
}

export function PsiTable({
  groups,
  offline,
}: {
  groups: readonly DriftGroup[];
  offline: boolean;
}) {
  return (
    <table className="ed-agate" data-testid="ops-psi">
      <thead>
        <tr>
          <th scope="col">Feature</th>
          <th scope="col" className="op-num">
            PSI
          </th>
          <th scope="col" className="ed-barcell">
            {`0 to ${T.psiScaleMax.toFixed(1)}, notice at ${T.psiNotice.toFixed(2)}`}
          </th>
          <th scope="col" className="op-num op-hide-phone">
            n
          </th>
          <th scope="col" className="op-pad op-hide-phone">
            Reads
          </th>
        </tr>
      </thead>
      {groups.map((g) => (
        <tbody key={g.modelName}>
          <tr>
            <th scope="rowgroup" colSpan={5} className="op-group">
              {g.modelName}{" "}
              <span>{`${g.cells.length} watched ${g.cells.length === 1 ? "feature" : "features"}`}</span>
            </th>
          </tr>
          {g.cells.map((c) => (
            <Row key={c.key} cell={c} offline={offline} kind="psi" />
          ))}
        </tbody>
      ))}
      <caption>
        {`Population stability against each champion's training distribution, newest nightly value. A notice fires only after ${T.psiNoticeDays} straight days over ${T.psiNotice.toFixed(2)} with at least ${T.psiMinSample} rows a day.`}
      </caption>
    </table>
  );
}

export function EceTable({
  cells,
  offline,
}: {
  cells: readonly DriftCell[];
  offline: boolean;
}) {
  return (
    <table className="ed-agate" data-testid="ops-ece">
      <thead>
        <tr>
          <th scope="col">Model, all pitches</th>
          <th scope="col" className="op-num">
            ECE
          </th>
          <th scope="col" className="ed-barcell">
            {`0 to ${T.eceScaleMax.toFixed(1)}, page at ${T.ecePage.toFixed(2)}`}
          </th>
          <th scope="col" className="op-num op-hide-phone">
            n
          </th>
          <th scope="col" className="op-pad op-hide-phone">
            Reads
          </th>
        </tr>
      </thead>
      <tbody>
        {cells.map((c) => (
          <Row key={c.modelName} cell={c} offline={offline} kind="ece" />
        ))}
      </tbody>
      <caption>
        {`Calibration error against settled truth, pitch models only (batted balls have no live truth). Pages after ${T.ecePageDays} days over ${T.ecePage.toFixed(2)}.`}
      </caption>
    </table>
  );
}
