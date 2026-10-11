/**
 * <ShowcaseFile> - `/players/:slug`, the demonstration matchup (SPEC-players 2.2, 6.5) on the
 * [195] identity. Everything here is a hand-written FIXTURE (data/matchup-fixtures.ts), so:
 *  - a role="note" banner sits directly under the dateline and is never collapsed;
 *  - every section slug ends "(showcase)", and the provenance says "showcase fixture";
 *  - both names link to the players' LIVE files;
 *  - there is no "What the models said" section: no live player, nothing live to show.
 * Dropped from the broadcast version on purpose: the location heatmap and spray chart
 * (fixture-only colour ramps) and grade chips; grades remain as a plain mono register.
 */
import { Link } from "react-router";

import {
  getDefaultMatchup,
  SHOWCASE_LIVE_IDS,
  type MatchupReport,
  type ScoutingPlayer,
} from "../../data/matchup-fixtures";
import { PitchTypeAgate } from "../home/pitch-type-agate";

const POSITION_PLAYERS = new Set([
  "RF",
  "CF",
  "LF",
  "DH",
  "1B",
  "2B",
  "3B",
  "SS",
  "C",
]);
const DASH = "–";

function rate3(v: number): string {
  return v.toFixed(3).replace(/^0/, "");
}
function pct0(v: number): string {
  return `${Math.round(v * 100)}%`;
}

function sides(report: MatchupReport): {
  batter: ScoutingPlayer;
  pitcher: ScoutingPlayer;
} {
  const primaryBats = POSITION_PLAYERS.has(report.primary.position);
  return primaryBats
    ? { batter: report.primary, pitcher: report.opponent }
    : { batter: report.opponent, pitcher: report.primary };
}

function LiveLink({ player }: { player: ScoutingPlayer }) {
  const id = SHOWCASE_LIVE_IDS[player.id];
  if (id == null) return <span>{player.name}</span>;
  return (
    <Link className="ed-link" to={`/players/${id}`}>
      {`${player.name}, live file`}
    </Link>
  );
}

function Grades({ player }: { player: ScoutingPlayer }) {
  return (
    <p className="pl-grades">
      Scouting grades, 20 to 80:{" "}
      {player.grades.map((g, i) => (
        <span key={g.label}>
          {i > 0 ? " · " : ""}
          <b>
            {g.label} {g.value ?? DASH}
          </b>
        </span>
      ))}
    </p>
  );
}

export function ShowcaseFile({ slug }: { slug: string | undefined }) {
  const report = getDefaultMatchup(slug);
  const { batter, pitcher } = sides(report);
  const mix = [...report.pitcherMix].sort((a, b) => b.usage - a.usage);

  return (
    <>
      <p className="pl-banner" role="note">
        <span className="pl-tag">Showcase</span>A demonstration matchup with
        illustrative values, not live scouting. The live files for both players
        are linked below.
      </p>

      <div className="ed-front pl-front">
        <div className="ed-front__head">
          <p className="ed-slug">Matchup (showcase)</p>
          <h1 className="ed-h1">
            {batter.name} against {pitcher.name}
          </h1>
          <p className="ed-dek">
            A sample of the scouting file this page will become.
          </p>
          <p className="ed-prov">{report.context}</p>
        </div>
        <div className="ed-front__links">
          <LiveLink player={batter} />
          <LiveLink player={pitcher} />
          <Link className="ed-link" to="/players">
            Back to the index
          </Link>
        </div>
        <aside className="ed-front__aside" aria-labelledby="pl-sc-mix">
          <h2 className="ed-h2" id="pl-sc-mix">
            {`${pitcher.name.split(" ").slice(-1)[0]}’s mix (showcase)`}
          </h2>
          <p className="ed-sub">Illustrative usage, written by hand.</p>
          <PitchTypeAgate
            rows={mix.map((p) => ({
              code: p.code,
              label: p.name,
              share: p.usage,
            }))}
            caption="Showcase usage, not Statcast."
            valueHeader="Usage"
            provenance="showcase fixture, not Statcast"
          />
        </aside>
      </div>

      <div className="pl-pair">
        <div>
          <p className="ed-slug">The pitcher (showcase)</p>
          <p className="pl-who">
            {pitcher.name}{" "}
            <span className="pl-mono-slug" style={{ display: "inline" }}>
              {`${pitcher.position}, ${pitcher.team}, ${pitcher.hand}`}
            </span>
          </p>
          <p className="ed-body" style={{ marginTop: "0.5rem" }}>
            {pitcher.summary}
          </p>
          <Grades player={pitcher} />
          <table className="ed-agate">
            <caption>
              Showcase pitch mix against the opposite hand. Illustrative.
            </caption>
            <thead>
              <tr>
                <th scope="col">Pitch</th>
                <th scope="col" className="pl-r">
                  Velo
                </th>
                <th scope="col" className="pl-r">
                  Whiff
                </th>
                <th scope="col" className="pl-r">
                  xwOBA
                </th>
                <th scope="col" className="pl-r">
                  Put-away
                </th>
              </tr>
            </thead>
            <tbody>
              {mix.map((p) => (
                <tr key={p.code}>
                  <td>
                    <span className="ed-agate__code" aria-hidden="true">
                      {p.code}
                    </span>
                    {p.name}
                  </td>
                  <td className="pl-r">{p.velo.toFixed(1)}</td>
                  <td className="pl-r">{pct0(p.whiff)}</td>
                  <td className="pl-r">{rate3(p.xwoba)}</td>
                  <td className="pl-r">{pct0(p.putaway)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div>
          <p className="ed-slug">The batter (showcase)</p>
          <p className="pl-who">
            {batter.name}{" "}
            <span className="pl-mono-slug" style={{ display: "inline" }}>
              {`${batter.position}, ${batter.team}, ${batter.hand}`}
            </span>
          </p>
          <p className="ed-body" style={{ marginTop: "0.5rem" }}>
            {batter.summary}
          </p>
          <Grades player={batter} />
          <table className="ed-agate">
            <caption>Showcase splits. Illustrative.</caption>
            <thead>
              <tr>
                <th scope="col">Split</th>
                <th scope="col" className="pl-r">
                  PA
                </th>
                <th scope="col" className="pl-r">
                  AVG
                </th>
                <th scope="col" className="pl-r pl-hide-phone">
                  SLG
                </th>
                <th scope="col" className="pl-r">
                  xwOBA
                </th>
                <th scope="col" className="pl-r">
                  K%
                </th>
              </tr>
            </thead>
            <tbody>
              {report.batterSplits.map((s) => (
                <tr key={s.split}>
                  <td>{s.split}</td>
                  <td className="pl-r">{s.pa}</td>
                  <td className="pl-r">{rate3(s.avg)}</td>
                  <td className="pl-r pl-hide-phone">{rate3(s.slg)}</td>
                  <td className="pl-r">{rate3(s.xwoba)}</td>
                  <td className="pl-r">{(s.k * 100).toFixed(1)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {report.keyNotes.length > 0 ? (
        <section className="pl-part" aria-labelledby="pl-sc-notes">
          <div className="pl-part__head">
            <p className="ed-slug" id="pl-sc-notes">
              Key notes (showcase)
            </p>
            <span className="pl-asof">written by hand, illustrative</span>
          </div>
          <ol className="pl-notes">
            {report.keyNotes.map((n) => (
              <li key={n}>{n}</li>
            ))}
          </ol>
        </section>
      ) : null}
    </>
  );
}
