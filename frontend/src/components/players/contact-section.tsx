/**
 * <ContactSection> - the batter file's hero (SPEC-players 2.2): exit velocity against launch angle
 * for his balls in play, with the existing filters (hit type, home runs only, a date range), and a
 * newest-first agate beside it. Fed by useBatterBattedBalls (GET /v1/players/:id/batted-balls).
 *
 * Marks: an ordinary ball is an ink2 ring; a home run is a solid ink dot (event by fill weight,
 * never colour); the 8 to 32 degree band is a quiet reference wash named in the caption. Balls
 * without a tracked speed or angle cannot be placed, so they are left off AND counted in the
 * caption - never plotted at 0. The scatter has a hidden table; the agate is the readable list.
 */
import { VisuallyHidden } from "@mantine/core";
import { useElementSize } from "@mantine/hooks";
import { useState } from "react";

import {
  useBatterBattedBalls,
  type BattedBallFilters,
  type BattedBallRow,
} from "../../api/players";
import { DASH, eventWord } from "../../lib/player-file";

const HIT_TYPES: readonly { value: string | null; label: string }[] = [
  { value: null, label: "All" },
  { value: "ground_ball", label: "Ground" },
  { value: "line_drive", label: "Line drive" },
  { value: "fly_ball", label: "Fly" },
  { value: "popup", label: "Popup" },
];

const BB_WORD: Readonly<Record<string, string>> = {
  ground_ball: "ground ball",
  line_drive: "line drive",
  fly_ball: "fly ball",
  popup: "popup",
};

const AGATE_ROWS = 12;
const SHORT_DATE = new Intl.DateTimeFormat("en-US", {
  month: "short",
  day: "numeric",
  timeZone: "UTC",
});
const LONG_DATE = new Intl.DateTimeFormat("en-US", {
  month: "short",
  day: "numeric",
  year: "numeric",
  timeZone: "UTC",
});

function shortDate(iso: string, season: number): string {
  const d = new Date(`${iso}T00:00:00Z`);
  return d.getUTCFullYear() === season
    ? SHORT_DATE.format(d)
    : LONG_DATE.format(d);
}

type Placed = BattedBallRow & {
  launchSpeedMph: number;
  launchAngleDeg: number;
};

function isPlaced(b: BattedBallRow): b is Placed {
  return b.launchSpeedMph != null && b.launchAngleDeg != null;
}

const EV_MIN = 40;
const EV_MAX = 120;
const LA_MIN = -60;
const LA_MAX = 80;
const MAX_W = 640;

function Scatter({ placed }: { placed: Placed[] }) {
  const { ref, width: measured } = useElementSize();
  // Keep the last real measurement: a transient 0 (an element briefly out of layout, a
  // beyond-viewport capture) must not snap the chart back to its desktop width.
  const [width, setWidth] = useState(0);
  if (measured > 0 && measured !== width) setWidth(measured);
  const W = Math.max(260, Math.min(MAX_W, width > 0 ? width : MAX_W));
  const H = Math.round(W * 0.64);
  const m = { l: 40, r: 10, t: 10, b: 36 };
  const S = W - m.l - m.r;
  const T = H - m.t - m.b;
  const x = (v: number) =>
    m.l +
    ((Math.min(Math.max(v, EV_MIN), EV_MAX) - EV_MIN) / (EV_MAX - EV_MIN)) * S;
  const y = (a: number) =>
    m.t +
    (1 - (Math.min(Math.max(a, LA_MIN), LA_MAX) - LA_MIN) / (LA_MAX - LA_MIN)) *
      T;
  const hrs = placed.filter((b) => b.events === "home_run");
  const rest = placed.filter((b) => b.events !== "home_run");
  return (
    <div ref={ref} className="pl-chart" style={{ maxWidth: MAX_W }}>
      <svg
        width={W}
        height={H}
        viewBox={`0 0 ${W} ${H}`}
        role="img"
        aria-label={`Exit velocity against launch angle, ${placed.length} balls in play, ${hrs.length} home runs`}
      >
        <rect
          className="pl-sc__band"
          x={x(EV_MIN)}
          y={y(32)}
          width={S}
          height={y(8) - y(32)}
        />
        {[60, 80, 100].map((v) => (
          <line
            key={`gx${v}`}
            className="pl-sc__grid"
            x1={x(v)}
            x2={x(v)}
            y1={y(LA_MAX)}
            y2={y(LA_MIN)}
          />
        ))}
        {[-30, 0, 30, 60].map((a) => (
          <line
            key={`gy${a}`}
            className="pl-sc__grid"
            x1={x(EV_MIN)}
            x2={x(EV_MAX)}
            y1={y(a)}
            y2={y(a)}
          />
        ))}
        <line
          className="pl-sc__axis"
          x1={x(EV_MIN)}
          x2={x(EV_MAX)}
          y1={y(LA_MIN)}
          y2={y(LA_MIN)}
        />
        <line
          className="pl-sc__axis"
          x1={x(EV_MIN)}
          x2={x(EV_MIN)}
          y1={y(LA_MIN)}
          y2={y(LA_MAX)}
        />
        {rest.map((b, i) => (
          <circle
            key={`b${i}`}
            className="pl-sc__pt"
            cx={x(b.launchSpeedMph)}
            cy={y(b.launchAngleDeg)}
            r={3.25}
          />
        ))}
        {hrs.map((b, i) => (
          <circle
            key={`h${i}`}
            className="pl-sc__hr"
            cx={x(b.launchSpeedMph)}
            cy={y(b.launchAngleDeg)}
            r={4}
          />
        ))}
        {[40, 60, 80, 100, 120].map((v) => (
          <text
            key={`tx${v}`}
            className="pl-sc__tick"
            x={x(v)}
            y={y(LA_MIN) + 14}
            textAnchor="middle"
          >
            {v}
          </text>
        ))}
        {[-60, -30, 0, 30, 60].map((a) => (
          <text
            key={`ty${a}`}
            className="pl-sc__tick"
            x={m.l - 6}
            y={y(a) + 3}
            textAnchor="end"
          >
            {a}
          </text>
        ))}
        <text className="pl-sc__label" x={x(80)} y={H - 4} textAnchor="middle">
          exit velocity, mph
        </text>
        <text
          className="pl-sc__label"
          transform={`translate(10 ${y(10)}) rotate(-90)`}
          textAnchor="middle"
        >
          launch angle, degrees
        </text>
      </svg>
      <VisuallyHidden>
        <table>
          <caption>Balls in play plotted above</caption>
          <thead>
            <tr>
              <th scope="col">Date</th>
              <th scope="col">Result</th>
              <th scope="col">Exit velocity, mph</th>
              <th scope="col">Launch angle, degrees</th>
            </tr>
          </thead>
          <tbody>
            {placed.map((b, i) => (
              <tr key={`r${i}`}>
                <td>{b.gameDate}</td>
                <td>{eventWord(b.events)}</td>
                <td>{b.launchSpeedMph.toFixed(1)}</td>
                <td>{Math.round(b.launchAngleDeg)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </VisuallyHidden>
    </div>
  );
}

export function ContactSection({ playerId }: { playerId: number }) {
  const [bbType, setBbType] = useState<string | null>(null);
  const [hrOnly, setHrOnly] = useState(false);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const filters: BattedBallFilters = {
    bbType: bbType ?? undefined,
    event: hrOnly ? "home_run" : undefined,
    from: from || undefined,
    to: to || undefined,
  };
  const balls = useBatterBattedBalls(playerId, filters);
  const season = new Date().getUTCFullYear();
  const filtered = bbType != null || hrOnly || from !== "" || to !== "";
  const clear = () => {
    setBbType(null);
    setHrOnly(false);
    setFrom("");
    setTo("");
  };

  return (
    <section className="pl-part" aria-labelledby="pl-contact-title">
      <div className="pl-part__head">
        <p className="ed-slug">Contact</p>
        <span className="pl-asof">his balls in play, newest first</span>
      </div>
      <h2 className="ed-h2" id="pl-contact-title">
        How hard, and how high
      </h2>
      <div className="pl-controls">
        <div className="pl-seg" role="group" aria-label="Hit type">
          {HIT_TYPES.map((h) => (
            <button
              key={h.label}
              type="button"
              className="pl-facet"
              aria-pressed={bbType === h.value}
              onClick={() => setBbType(h.value)}
            >
              {h.label}
            </button>
          ))}
        </div>
        <label className="pl-check">
          <input
            type="checkbox"
            checked={hrOnly}
            onChange={(e) => setHrOnly(e.currentTarget.checked)}
          />
          Home runs only
        </label>
        <label className="pl-date">
          From
          <input
            type="date"
            value={from}
            onChange={(e) => setFrom(e.currentTarget.value)}
          />
        </label>
        <label className="pl-date">
          To
          <input
            type="date"
            value={to}
            onChange={(e) => setTo(e.currentTarget.value)}
          />
        </label>
      </div>
      <ContactBody
        query={balls}
        season={season}
        filtered={filtered}
        onClear={clear}
      />
    </section>
  );
}

function ContactBody({
  query,
  season,
  filtered,
  onClear,
}: {
  query: ReturnType<typeof useBatterBattedBalls>;
  season: number;
  filtered: boolean;
  onClear: () => void;
}) {
  if (query.isError) {
    return <p className="ed-note">Could not load his balls in play.</p>;
  }
  if (query.isPending) {
    return (
      <div role="status" aria-busy="true">
        <VisuallyHidden>Loading his balls in play</VisuallyHidden>
        <div
          className="ed-shell"
          aria-hidden="true"
          style={{
            aspectRatio: "1 / 0.64",
            maxWidth: MAX_W,
            marginTop: "0.75rem",
          }}
        />
      </div>
    );
  }
  const rows = query.data;
  if (rows.length === 0) {
    return filtered ? (
      <p className="ed-note">
        No balls in play match these filters.{" "}
        <button
          type="button"
          className="pl-facet"
          style={{ minHeight: 0, fontStyle: "normal" }}
          onClick={onClear}
        >
          Clear filters
        </button>
      </p>
    ) : (
      <p className="ed-note">No balls in play on file.</p>
    );
  }
  const placed = rows.filter(isPlaced);
  const unplaced = rows.length - placed.length;
  const shown = rows.slice(0, AGATE_ROWS);
  return (
    <div className="pl-split" style={{ marginTop: "0.5rem" }}>
      <figure style={{ margin: 0 }}>
        <Scatter placed={placed} />
        <figcaption className="ed-note">
          {`Each ring is a ball in play; a solid dot is a home run. The shaded band is launch angles from 8 to 32 degrees. ${
            unplaced === 0
              ? "Every ball here has a tracked speed and angle."
              : `${unplaced} ${unplaced === 1 ? "ball has" : "balls have"} no tracked speed or angle and ${unplaced === 1 ? "is" : "are"} left off.`
          }`}
        </figcaption>
      </figure>
      <div>
        <h3 className="ed-h3" style={{ marginTop: 0 }}>
          Newest first
        </h3>
        <table className="ed-agate">
          <caption>
            {rows.length <= AGATE_ROWS
              ? `All ${rows.length}, newest first. A dash means Statcast did not track it.`
              : `The newest ${AGATE_ROWS} of ${rows.length}. A dash means Statcast did not track it.`}
          </caption>
          <thead>
            <tr>
              <th scope="col">Date</th>
              <th scope="col">Result</th>
              <th scope="col" className="pl-r">
                EV
              </th>
              <th scope="col" className="pl-r">
                LA
              </th>
              <th scope="col" className="pl-r">
                Ft
              </th>
            </tr>
          </thead>
          <tbody>
            {shown.map((b, i) => (
              <tr key={`${b.gameDate}-${i}`}>
                <td className="pl-faint" style={{ whiteSpace: "nowrap" }}>
                  {shortDate(b.gameDate, season)}
                </td>
                <td>
                  {eventWord(b.events)}
                  {BB_WORD[b.bbType] ? `, ${BB_WORD[b.bbType]}` : ""}
                </td>
                <td className="pl-r">
                  {b.launchSpeedMph == null
                    ? DASH
                    : b.launchSpeedMph.toFixed(1)}
                </td>
                <td className="pl-r">
                  {b.launchAngleDeg == null
                    ? DASH
                    : Math.round(b.launchAngleDeg)}
                </td>
                <td className="pl-r">
                  {b.hitDistanceFt == null ? DASH : Math.round(b.hitDistanceFt)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
