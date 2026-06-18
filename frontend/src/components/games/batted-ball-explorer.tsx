/**
 * <BattedBallExplorer> - the live game's batted-ball card + cross-park compare.
 * Shows a struck ball's Statcast line, then expands the LIVE batted-ball
 * champion's per-park heads: the same ball scored at every park, with the
 * estimated carry +/- error per park and the realized outcome.
 *
 * The user curates which parks show (+ adds from a dropdown of the remaining
 * parks - the dropdown lists the team abbrev, NOT the result, so adding a park
 * is a reveal; x removes; the current park is pinned). The "N of 30" headline
 * stays full-model - it is NEVER recomputed from the displayed subset.
 *
 * Showcase data (batted-ball-fixtures) until a "score this BIP at N parks"
 * endpoint exists; the LIVE chip marks it as the promoted champion (vs the held
 * pitch heads - decision [154]/ADR-0011). Team color is not used here (park
 * rows are neutral); cond-format tones carry the outcome.
 */

import { useMemo, useState } from "react";

import type {
  BattedBall,
  ParkOutcome,
  ParkOutcomeTone,
} from "../../data/batted-ball-fixtures";
import { colors, cuts, typography } from "../../design/broadcast";

const TONE: Record<ParkOutcomeTone, { bg: string; fg: string }> = {
  hr: { bg: colors.gold, fg: colors.ink },
  xb: { bg: colors.condFormat.good1, fg: colors.ink },
  out: { bg: colors.condFormat.neutral, fg: colors.textMuted },
};

const metricKeyStyle: React.CSSProperties = {
  fontFamily: typography.fonts.mono,
  fontSize: 10,
  letterSpacing: "0.08em",
  textTransform: "uppercase",
  color: colors.textMuted,
};

const metricValueStyle: React.CSSProperties = {
  marginTop: 2,
  fontFamily: typography.fonts.mono,
  fontWeight: typography.weights.bold,
  fontSize: 21,
  fontFeatureSettings: '"tnum" 1',
  color: colors.ink,
};

function Metric({
  k,
  value,
  unit,
}: {
  k: string;
  value: string;
  unit?: string;
}) {
  return (
    <div>
      <div style={metricKeyStyle}>{k}</div>
      <div style={metricValueStyle}>
        {value}
        {unit && (
          <span
            style={{
              fontSize: 11,
              color: colors.textMuted,
              fontWeight: typography.weights.medium,
            }}
          >
            {" "}
            {unit}
          </span>
        )}
      </div>
    </div>
  );
}

function ParkRow({
  park,
  onRemove,
}: {
  park: ParkOutcome;
  onRemove?: () => void;
}) {
  const tone = TONE[park.tone];
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        padding: "6px 10px",
        border: `1px solid ${park.here ? colors.gold : colors.rule}`,
        backgroundColor: park.here ? colors.fieldSubtle : colors.panel,
      }}
    >
      <span
        style={{
          fontFamily: typography.fonts.mono,
          fontSize: 11,
          letterSpacing: "0.02em",
          textTransform: "uppercase",
          color: colors.text,
        }}
      >
        {park.park}
      </span>
      <span style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            color: colors.textMuted,
            whiteSpace: "nowrap",
          }}
        >
          {park.dist} ± {park.err} ft
        </span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontWeight: typography.weights.bold,
            fontSize: 11,
            padding: "1px 7px",
            backgroundColor: tone.bg,
            color: tone.fg,
          }}
        >
          {park.outcome}
        </span>
        {onRemove && (
          <button
            type="button"
            onClick={onRemove}
            aria-label={`Remove ${park.park}`}
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: 15,
              lineHeight: 1,
              color: colors.textMuted,
              background: "none",
              border: "none",
              cursor: "pointer",
              padding: "0 1px",
            }}
          >
            ×
          </button>
        )}
      </span>
    </div>
  );
}

export function BattedBallExplorer({ data }: { data: BattedBall }) {
  const [open, setOpen] = useState(false);
  const [shown, setShown] = useState<string[]>(data.defaultShown);
  const [selectOpen, setSelectOpen] = useState(false);

  const byName = useMemo(
    () => new Map(data.parks.map((p) => [p.park, p])),
    [data.parks],
  );
  const addable = data.parks.filter((p) => !p.here && !shown.includes(p.park));

  return (
    <div
      style={{
        backgroundColor: colors.panel,
        border: `1px solid ${colors.rule}`,
        clipPath: cuts.panelCorner,
        padding: "16px 20px",
      }}
    >
      <div
        style={{
          display: "flex",
          alignItems: "baseline",
          justifyContent: "space-between",
          gap: 12,
        }}
      >
        <div>
          <div
            style={{
              fontFamily: typography.fonts.display,
              fontStyle: "italic",
              fontWeight: typography.weights.bold,
              fontSize: 23,
              lineHeight: 1.05,
              color: colors.ink,
            }}
          >
            {data.batter}
          </div>
          <div
            style={{
              marginTop: 3,
              fontFamily: typography.fonts.mono,
              fontSize: 11,
              letterSpacing: "0.06em",
              textTransform: "uppercase",
              color: colors.textMuted,
            }}
          >
            {data.description}
          </div>
        </div>
        <div
          style={{
            fontFamily: typography.fonts.display,
            fontStyle: "italic",
            fontWeight: typography.weights.heavy,
            fontSize: 22,
            color: colors.goldInk,
            whiteSpace: "nowrap",
          }}
        >
          {data.result}
        </div>
      </div>

      <div
        style={{
          display: "flex",
          gap: 28,
          flexWrap: "wrap",
          margin: "15px 0 2px",
        }}
      >
        <Metric k="Exit velo" value={data.exitVeloMph.toFixed(1)} unit="mph" />
        <Metric k="Launch" value={`${data.launchDeg}°`} />
        <Metric k="Distance" value={String(data.distanceFt)} unit="ft" />
        <Metric k="xBA" value={data.xba} />
      </div>

      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        style={{
          marginTop: 16,
          fontFamily: typography.fonts.mono,
          fontWeight: typography.weights.medium,
          fontSize: 12,
          letterSpacing: "0.06em",
          textTransform: "uppercase",
          color: colors.textOnChrome,
          backgroundColor: colors.chrome,
          border: "none",
          padding: "9px 16px",
          cursor: "pointer",
        }}
      >
        {open ? "Hide park comparison" : "Compare across parks →"}
      </button>

      {open && (
        <div
          style={{
            marginTop: 16,
            borderTop: `1px solid ${colors.rule}`,
            paddingTop: 14,
          }}
        >
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              marginBottom: 8,
            }}
          >
            <span
              style={{
                fontFamily: typography.fonts.mono,
                fontWeight: typography.weights.bold,
                fontSize: 12,
                color: colors.ink,
              }}
            >
              batted_ball v1.4 · per-park heads
            </span>
            <span
              style={{
                fontFamily: typography.fonts.mono,
                fontSize: 10,
                fontWeight: typography.weights.bold,
                letterSpacing: "0.1em",
                backgroundColor: colors.condFormat.good1,
                color: colors.condFormat.good3,
                border: `1px solid ${colors.condFormat.good3}`,
                padding: "2px 8px",
              }}
            >
              LIVE
            </span>
          </div>

          <p
            style={{
              margin: "0 0 12px",
              fontFamily: typography.fonts.body,
              fontSize: 13,
              lineHeight: 1.5,
              color: colors.text,
            }}
          >
            The same struck ball, scored at every park:{" "}
            <strong>
              home run in {data.hrParkCount} of {data.parkCount}
            </strong>
            . Here it was caught at the track - the model&rsquo;s whole point.
          </p>

          <div
            style={{
              display: "grid",
              gridTemplateColumns: "repeat(auto-fill, minmax(240px, 1fr))",
              gap: 7,
            }}
          >
            {shown.map((name) => {
              const park = byName.get(name);
              if (!park) return null;
              return (
                <ParkRow
                  key={name}
                  park={park}
                  onRemove={
                    park.here
                      ? undefined
                      : () => setShown((s) => s.filter((n) => n !== name))
                  }
                />
              );
            })}
          </div>

          {addable.length > 0 && (
            <div
              style={{
                marginTop: 10,
                display: "flex",
                alignItems: "center",
                gap: 10,
              }}
            >
              <button
                type="button"
                aria-expanded={selectOpen}
                onClick={() => setSelectOpen((s) => !s)}
                style={{
                  fontFamily: typography.fonts.mono,
                  fontSize: 11,
                  fontWeight: typography.weights.medium,
                  letterSpacing: "0.06em",
                  textTransform: "uppercase",
                  color: colors.goldInk,
                  background: "none",
                  border: `1px dashed ${colors.rule}`,
                  padding: "6px 12px",
                  cursor: "pointer",
                }}
              >
                + Add park
              </button>
              {selectOpen && (
                <select
                  aria-label="Add a park to the comparison"
                  defaultValue=""
                  onChange={(e) => {
                    if (e.target.value) {
                      setShown((s) => [...s, e.target.value]);
                      setSelectOpen(false);
                    }
                  }}
                  style={{
                    fontFamily: typography.fonts.mono,
                    fontSize: 12,
                    padding: "6px 10px",
                    border: `1px solid ${colors.rule}`,
                    backgroundColor: colors.panel,
                    color: colors.text,
                  }}
                >
                  <option value="" disabled>
                    Choose a park…
                  </option>
                  {addable.map((p) => (
                    <option key={p.park} value={p.park}>
                      {p.park} · {p.team}
                    </option>
                  ))}
                </select>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
