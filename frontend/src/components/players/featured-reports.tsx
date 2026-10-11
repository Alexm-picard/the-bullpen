/**
 * <FeaturedReports> - the /players showcase list ([195] editorial skin). These are hand-written
 * FIXTURES (players-landing-fixtures.ts): the parent section carries the Showcase label inline, and
 * the caption repeats it. Each name links to the player's LIVE file. The grade chips the fixture
 * still carries are deliberately not rendered (tone-coded quality chips break "data in ink").
 */
import { Link } from "react-router";

import type { FeaturedReport } from "../../data/players-landing-fixtures";

export function FeaturedReports({
  reports,
}: {
  reports: readonly FeaturedReport[];
}) {
  if (reports.length === 0) return null;
  return (
    <table className="ed-agate">
      <caption>Showcase figures, illustrative.</caption>
      <thead>
        <tr>
          <th scope="col">Player</th>
          <th scope="col">Role</th>
          <th scope="col" className="pl-r">
            Stat
          </th>
          <th scope="col" className="pl-r">
            Stat
          </th>
        </tr>
      </thead>
      <tbody>
        {reports.map((r) => (
          <tr key={r.playerId}>
            <td>
              <Link className="ed-link" to={`/players/${r.playerId}`}>
                {r.name}
              </Link>{" "}
              <span className="pl-faint">{r.team}</span>
            </td>
            <td>{r.role}</td>
            {r.stats.slice(0, 2).map((s) => (
              <td
                key={s.label}
                className="pl-r"
                style={{ whiteSpace: "nowrap" }}
              >
                {s.label} {s.value}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
