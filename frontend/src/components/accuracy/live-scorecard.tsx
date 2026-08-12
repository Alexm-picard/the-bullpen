/**
 * The /accuracy Live Scorecard section (Alex's ask): rolling realized top-1
 * accuracy for all four families, LIVE truth-join numbers only - rendered
 * ABOVE and structurally separate from the OFFLINE held-out tables, because
 * that separation is the page's whole integrity.
 *
 * Card states (all exercised by tests):
 *   live ........... big % (top-1 realized), class count + n + window
 *                    beneath, sparkline when >= 2 daily buckets
 *   no-truth ....... the endpoint's own reason rendered VERBATIM - the
 *                    backend owns the facts (the [163] framing, the
 *                    promotion date, the store-down state); this component
 *                    never restates them as literals of its own
 *   accumulating ... pitch_type live but n < PITCH_TYPE_RENDER_FLOOR: NO %
 *                    renders (a % over a handful of predictions is noise
 *                    wearing a number) - the floor is an FE-owned fact, so
 *                    the FE states it (class counts are the only other
 *                    FE-owned model fact; no endpoint field carries them)
 *   degraded ....... a "live" entry missing its numbers, or a family missing
 *                    from the payload entirely: stated, never implied and
 *                    NEVER a fabricated 0.0% (top1 ?? 0 was the exact bug)
 *
 * Honesty constraints (non-negotiable, from the work order): pitch-type is a
 * calibrated prior promoted on calibration ([183]) - its top-1 is
 * supplementary and captioned (the endpoint's own note, FE fallback only if
 * absent); batted-ball's reality gap stays stated in the backend's words;
 * every rendered % carries its class count, n and window.
 */
import type {
  ModelRollingAccuracy,
  RollingDailyBucket,
} from "../../api/rolling-accuracy";
import { colors, typography } from "../../design/broadcast";

/**
 * Below this many truth-joined predictions no % renders for pitch_type - the
 * card stays in its accumulating state with the n it has.
 */
export const PITCH_TYPE_RENDER_FLOOR = 500;

/** The backend's default window; used only while the response is in flight. */
export const DEFAULT_WINDOW_DAYS = 7;

/** The four families the endpoint contracts to report, in display order. */
const EXPECTED_MODELS = [
  "pitch_outcome_pre",
  "pitch_outcome_post",
  "battedball_outcome",
  "pitch_type_pre",
] as const;

/** Outcome-class counts, the anchor a bare % lacks (top-1 of HOW MANY?). */
const CLASS_COUNT: Record<string, number> = {
  pitch_outcome_pre: 5,
  pitch_outcome_post: 5,
  pitch_type_pre: 7,
};

const cardStyle: React.CSSProperties = {
  border: `1px solid ${colors.rule}`,
  background: colors.panel,
  padding: "14px 16px",
  minWidth: 0,
};

// LIVE cards carry the sanctioned live-state mark: a gold edge FILL on the
// frame (energy in the frame, restraint in the cells) - never gold text.
const liveCardStyle: React.CSSProperties = {
  ...cardStyle,
  borderTop: `3px solid ${colors.gold}`,
  // 2px less top padding compensates the 3px-vs-1px border so the first
  // baseline stays aligned with the no-truth cards in the same grid row.
  padding: "12px 16px 14px",
};

const modelNameStyle: React.CSSProperties = {
  margin: 0,
  fontFamily: typography.fonts.mono,
  fontSize: 12,
  fontWeight: typography.weights.semibold,
  letterSpacing: "0.08em",
  textTransform: "uppercase",
  color: colors.textMuted,
};

// scale[5], deliberately NOT scale[6]: a card stat competing at h1 size
// flattens the page hierarchy; 32px still dominates within the card.
const bigPctStyle: React.CSSProperties = {
  margin: "6px 0 0",
  fontFamily: typography.fonts.display,
  fontStyle: "italic",
  fontWeight: typography.weights.heavy,
  fontSize: typography.scale[5],
  lineHeight: 1.1,
  color: colors.ink,
};

const subStyle: React.CSSProperties = {
  margin: "4px 0 0",
  fontFamily: typography.fonts.body,
  fontSize: 12,
  color: colors.textMuted,
  lineHeight: 1.45,
};

// The qualifier is contractually required reading, not a footnote: mono, full
// text color, and a hairline rule so it cannot dissolve into the metadata
// line above it (the "big claim, small disclaimer" trap).
const qualifierStyle: React.CSSProperties = {
  margin: "8px 0 0",
  paddingTop: 6,
  borderTop: `1px solid ${colors.rule}`,
  fontFamily: typography.fonts.mono,
  fontSize: 11,
  color: colors.text,
  lineHeight: 1.5,
};

const detailStyle: React.CSSProperties = {
  margin: "8px 0 0",
  fontFamily: typography.fonts.body,
  fontSize: 12,
  color: colors.textMuted,
  lineHeight: 1.45,
};

const fmtPct1 = (fraction: number): string => `${(fraction * 100).toFixed(1)}%`;

// -- Sparkline -------------------------------------------------------------

/**
 * Minimal inline polyline over the daily top-1 fractions. Labeled image per
 * the house SVG convention (role="img" + aria-label - CI-enforced by
 * audit-a11y-static, matching reliability-diagram and spray-chart); the % and
 * n are always printed as text, so the graphic is never the sole carrier of
 * the data.
 *
 * The y-scale is ABSOLUTE 0..1 on purpose: auto-scaling would exaggerate
 * day-to-day noise, exactly what this feature exists not to do. Known
 * trade-off, accepted: every plotted day weighs equally regardless of its n,
 * so a thin day can draw the same spike as a heavy one - the honest total
 * is always the printed %, not the line.
 */
function Sparkline({ buckets }: { buckets: RollingDailyBucket[] }) {
  const w = 120;
  const h = 26;
  const pad = 2;
  const points = buckets
    .map((b, i) => {
      const x =
        buckets.length === 1
          ? w / 2
          : pad + (i * (w - 2 * pad)) / (buckets.length - 1);
      const y = h - pad - b.top1 * (h - 2 * pad);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
  return (
    <svg
      width={w}
      height={h}
      viewBox={`0 0 ${w} ${h}`}
      role="img"
      aria-label="daily top-1 realized accuracy trend, absolute 0 to 1 scale"
      style={{ display: "block", marginTop: 8 }}
    >
      <polyline
        points={points}
        fill="none"
        stroke={colors.goldInk}
        strokeWidth={1.5}
      />
    </svg>
  );
}

// -- Cards -----------------------------------------------------------------

function LivePctCard({
  model,
  top1,
  n,
  windowDays,
  qualifier,
}: {
  model: ModelRollingAccuracy;
  top1: number;
  n: number;
  windowDays: number;
  qualifier?: string | null;
}) {
  const buckets = model.buckets ?? [];
  const classCount = CLASS_COUNT[model.modelName];
  return (
    <article
      style={liveCardStyle}
      aria-label={`${model.modelName} live accuracy`}
    >
      <p style={modelNameStyle}>{model.modelName}</p>
      <p style={bigPctStyle}>{fmtPct1(top1)}</p>
      <p style={subStyle}>
        top-1 realized{classCount ? ` of ${classCount} classes` : ""} · n ={" "}
        {n.toLocaleString()} · rolling {windowDays}d
      </p>
      {buckets.length >= 2 ? <Sparkline buckets={buckets} /> : null}
      {qualifier ? <p style={qualifierStyle}>{qualifier}</p> : null}
    </article>
  );
}

function NoTruthCard({
  modelName,
  headline,
  detail,
  note,
}: {
  modelName: string;
  headline: string;
  detail: string | null;
  note?: string | null;
}) {
  return (
    <article style={cardStyle} aria-label={`${modelName} no live truth`}>
      <p style={modelNameStyle}>{modelName}</p>
      <p style={{ ...subStyle, marginTop: 6, color: colors.text }}>
        {headline}
      </p>
      <p style={detailStyle}>
        {detail ?? "the endpoint reported no reason for this state"}
      </p>
      {note ? <p style={qualifierStyle}>{note}</p> : null}
    </article>
  );
}

// -- Per-family rendering --------------------------------------------------

/** A "live" entry is renderable only when its numbers actually exist. */
function renderableLive(
  m: ModelRollingAccuracy,
): { top1: number; n: number } | null {
  if (m.status !== "live" || m.top1 == null || m.n == null) {
    return null;
  }
  return { top1: m.top1, n: m.n };
}

function pitchOutcomeCard(m: ModelRollingAccuracy, windowDays: number) {
  const nums = renderableLive(m);
  if (nums) {
    return (
      <LivePctCard
        key={m.modelName}
        model={m}
        top1={nums.top1}
        n={nums.n}
        windowDays={windowDays}
        qualifier={m.note}
      />
    );
  }
  // no_live_truth, OR a "live" entry whose numbers are missing: stated,
  // never a fabricated 0.0% (top1 ?? 0 was the exact bug this replaces).
  return (
    <NoTruthCard
      key={m.modelName}
      modelName={m.modelName}
      headline="no live truth"
      detail={
        m.status === "live"
          ? "the endpoint reported a live status without its numbers - treated as no truth"
          : m.reason
      }
      note={m.note}
    />
  );
}

function battedBallCard(m: ModelRollingAccuracy) {
  // This family's live realized accuracy is structurally unavailable ([163]):
  // the served surface is the park heatmap (a cross-park counterfactual), not
  // per-pitch predictions, so there are no pitch keys to truth-join. The card
  // leads with the graded offline evidence instead.
  return (
    <NoTruthCard
      key={m.modelName}
      modelName={m.modelName}
      headline="measured offline (see backfill below)"
      detail="live truth-join structurally unavailable - calibrated physics estimate, not per-pitch predictions (Guide #batted-ball)"
      note={m.note}
    />
  );
}

function pitchTypeCard(m: ModelRollingAccuracy, windowDays: number) {
  const nums = renderableLive(m);
  if (nums && nums.n >= PITCH_TYPE_RENDER_FLOOR) {
    return (
      <LivePctCard
        key={m.modelName}
        model={m}
        top1={nums.top1}
        n={nums.n}
        windowDays={windowDays}
        // The endpoint's own [183] note is the caption; the FE fallback
        // exists only for an older payload without one.
        qualifier={
          m.note ??
          "top-1 of a calibrated 7-class distribution; ceiling ~45% by design (Guide #pitch-type)"
        }
      />
    );
  }
  if (nums) {
    // Below the floor: the floor is the one fact the FE owns, so the FE
    // states it - everything else (the promotion date, the [183] framing)
    // stays in the backend's reason/note.
    return (
      <NoTruthCard
        key={m.modelName}
        modelName={m.modelName}
        headline="accumulating"
        detail={`n = ${nums.n.toLocaleString()} so far; a % renders at n >= ${PITCH_TYPE_RENDER_FLOOR.toLocaleString()} - a percentage over a handful of predictions is noise wearing a number`}
        note={m.note}
      />
    );
  }
  // no_live_truth: the endpoint's reason is the headline fact (it says
  // whether this is accumulation, a store outage, or anything else) - the
  // FE must not caption an infrastructure state as progress.
  return (
    <NoTruthCard
      key={m.modelName}
      modelName={m.modelName}
      headline="no live truth yet"
      detail={m.reason}
      note={m.note}
    />
  );
}

// -- Section ---------------------------------------------------------------

export function LiveScorecard({
  models,
  windowDays,
}: {
  models: ModelRollingAccuracy[];
  windowDays: number;
}) {
  const byName = new Map(models.map((m) => [m.modelName, m]));

  const cards = EXPECTED_MODELS.map((name) => {
    const m = byName.get(name);
    if (!m) {
      // The endpoint contracts "absence is stated, never implied"; a family
      // it failed to return must not silently vanish from the page either.
      return (
        <NoTruthCard
          key={name}
          modelName={name}
          headline="not reported"
          detail="the endpoint omitted this family from its response"
        />
      );
    }
    if (name === "battedball_outcome") {
      return battedBallCard(m);
    }
    if (name === "pitch_type_pre") {
      return pitchTypeCard(m, windowDays);
    }
    return pitchOutcomeCard(m, windowDays);
  });

  // The mirror of the omission case: a family the endpoint ADDS beyond the
  // contracted four renders through the generic pitch-outcome shape (its own
  // status/reason/note) rather than silently vanishing.
  const extras = models
    .filter(
      (m) => !(EXPECTED_MODELS as readonly string[]).includes(m.modelName),
    )
    .map((m) => pitchOutcomeCard(m, windowDays));

  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))",
        gap: 12,
      }}
    >
      {cards}
      {extras}
    </div>
  );
}
