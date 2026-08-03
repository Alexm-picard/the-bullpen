/**
 * The /accuracy Live Scorecard section (Alex's ask): rolling realized top-1
 * accuracy for all four families, LIVE truth-join numbers only - rendered
 * ABOVE and visually separate from the OFFLINE held-out table, because that
 * separation is the page's whole integrity.
 *
 * Card states (all four exercised by tests):
 *   live ........... big % (top-1 realized), n + window beneath, sparkline
 *                    when the endpoint provides >= 2 daily buckets
 *   no-truth ....... battedball: the OFFLINE figure labeled exactly as the
 *                    table labels it, plus the [163] calibrated-physics line
 *   accumulating ... pitch_type before any truth-joinable volume
 *   below-floor .... pitch_type live but n < 500: NO % renders (a % over a
 *                    handful of predictions is noise wearing a number)
 *
 * Honesty constraints (non-negotiable, from the work order): pitch-type is a
 * calibrated prior promoted on calibration ([183]) - its top-1 is
 * supplementary and captioned, never the claim; batted-ball's reality gap
 * stays stated ([163]); every rendered % carries its n and window.
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

const cardStyle: React.CSSProperties = {
  border: `1px solid ${colors.rule}`,
  background: colors.panel,
  padding: "14px 16px",
  minWidth: 0,
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

const bigPctStyle: React.CSSProperties = {
  margin: "6px 0 0",
  fontFamily: typography.fonts.display,
  fontStyle: "italic",
  fontWeight: typography.weights.heavy,
  fontSize: typography.scale[6],
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

const captionStyle: React.CSSProperties = {
  margin: "8px 0 0",
  fontFamily: typography.fonts.body,
  fontSize: 12,
  color: colors.textMuted,
  lineHeight: 1.45,
};

const fmtPct1 = (fraction: number): string => `${(fraction * 100).toFixed(1)}%`;

// -- Sparkline -------------------------------------------------------------

/**
 * Minimal inline polyline over the daily top-1 fractions. Decorative
 * (aria-hidden): the % and n are always printed as text, so the graphic is
 * never the sole carrier of the data.
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
      aria-hidden="true"
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
  windowDays,
  caption,
}: {
  model: ModelRollingAccuracy;
  windowDays: number;
  caption?: string;
}) {
  const buckets = model.buckets ?? [];
  return (
    <article style={cardStyle} aria-label={`${model.modelName} live accuracy`}>
      <p style={modelNameStyle}>{model.modelName}</p>
      <p style={bigPctStyle}>{fmtPct1(model.top1 ?? 0)}</p>
      <p style={subStyle}>
        top-1 realized · n = {(model.n ?? 0).toLocaleString()} · rolling{" "}
        {windowDays}d
      </p>
      {buckets.length >= 2 ? <Sparkline buckets={buckets} /> : null}
      {caption ? <p style={captionStyle}>{caption}</p> : null}
    </article>
  );
}

function NoTruthCard({
  model,
  headline,
  detail,
}: {
  model: ModelRollingAccuracy;
  headline: string;
  detail: string | null;
}) {
  return (
    <article style={cardStyle} aria-label={`${model.modelName} no live truth`}>
      <p style={modelNameStyle}>{model.modelName}</p>
      <p style={{ ...subStyle, marginTop: 6, color: colors.text }}>
        {headline}
      </p>
      {detail ? <p style={captionStyle}>{detail}</p> : null}
    </article>
  );
}

// -- Section ---------------------------------------------------------------

export function LiveScorecard({
  models,
  windowDays,
  battedBallOfflineEce,
}: {
  models: ModelRollingAccuracy[];
  windowDays: number;
  /** The OFFLINE table's batted-ball ECE, echoed on its card with the same label. */
  battedBallOfflineEce: number | null;
}) {
  const byName = new Map(models.map((m) => [m.modelName, m]));
  const pre = byName.get("pitch_outcome_pre");
  const post = byName.get("pitch_outcome_post");
  const battedBall = byName.get("battedball_outcome");
  const pitchType = byName.get("pitch_type_pre");

  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))",
        gap: 12,
      }}
    >
      {pre && pre.status === "live" ? (
        <LivePctCard model={pre} windowDays={windowDays} />
      ) : pre ? (
        <NoTruthCard model={pre} headline="no live truth" detail={pre.reason} />
      ) : null}

      {post && post.status === "live" ? (
        <LivePctCard model={post} windowDays={windowDays} />
      ) : post ? (
        <NoTruthCard
          model={post}
          headline="no live truth"
          detail={post.reason}
        />
      ) : null}

      {battedBall ? (
        <NoTruthCard
          model={battedBall}
          headline={
            battedBallOfflineEce != null
              ? `ECE ${battedBallOfflineEce.toFixed(3)} (offline)`
              : "no live truth"
          }
          detail="no live truth-join - calibrated physics estimate ([163])"
        />
      ) : null}

      {pitchType && pitchType.status === "live" ? (
        (pitchType.n ?? 0) >= PITCH_TYPE_RENDER_FLOOR ? (
          <LivePctCard
            model={pitchType}
            windowDays={windowDays}
            caption="calibrated prior; top-1 is supplementary, never the claim ([183])"
          />
        ) : (
          <NoTruthCard
            model={pitchType}
            headline="accumulating - promoted 2026-08-02"
            detail={`n = ${(pitchType.n ?? 0).toLocaleString()} so far; a % renders at n >= ${PITCH_TYPE_RENDER_FLOOR.toLocaleString()} - a percentage over a handful of predictions is noise wearing a number`}
          />
        )
      ) : pitchType ? (
        <NoTruthCard
          model={pitchType}
          headline="accumulating - promoted 2026-08-02"
          detail={pitchType.reason}
        />
      ) : null}
    </div>
  );
}
