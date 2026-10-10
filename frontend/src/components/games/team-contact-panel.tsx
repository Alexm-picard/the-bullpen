/**
 * <TeamContactPanel> - before the first ball in play, each team's REAL season-to-date contact
 * scored at tonight's park by the batted-ball champion ([195] editorial skin; logic unchanged).
 *
 * What it deliberately is not: a "typical" batted ball. A median launch speed and angle would be a
 * point that never occurred, fed to the model as a fabricated input wearing a statistic. Every
 * number here is a mean over REAL balls, and n is shown because a mean over 8 and a mean over 800
 * are not the same claim. It is also a different claim from the live card it stands in for -
 * season-to-date, not this game - so the caption says so.
 */
import { VisuallyHidden } from "@mantine/core";

import type { TeamContactResponse, TeamHrProfile } from "../../api/games";

export type TeamContactPanelProps = {
  data: TeamContactResponse | undefined;
  isLoading: boolean;
  error: unknown;
};

const PCT = new Intl.NumberFormat("en-US", {
  style: "percent",
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
});

function TeamRow({
  team,
  profile,
}: {
  team: string;
  profile: TeamHrProfile | null;
}) {
  // Scaled against a 10% ceiling: league HR-per-BIP sits well under that, so a linear bar to 100%
  // would render every team as a stub. The number is the claim; the bar is a comparison aid.
  const scale = profile ? Math.min(profile.meanHrProbability * 10, 1) : 0;
  return (
    <tr className="ed-agate__row">
      <td>
        {team}{" "}
        {profile ? <span className="ed-agate__code">n={profile.n}</span> : null}
      </td>
      <td className="ed-barcell" aria-hidden="true">
        <div className="ed-track">
          <span className="ed-bar" style={{ transform: `scaleX(${scale})` }} />
        </div>
      </td>
      <td className="ed-num">
        {profile ? PCT.format(profile.meanHrProbability) : "no profile"}
      </td>
    </tr>
  );
}

export function TeamContactPanel({
  data,
  isLoading,
  error,
}: TeamContactPanelProps) {
  if (error) {
    return (
      <p className="ed-note">Could not load the season-to-date comparison.</p>
    );
  }
  if (isLoading || !data) {
    return (
      <p className="ed-note" aria-busy="true">
        Scoring both teams&rsquo; recent contact at this park&hellip;
      </p>
    );
  }
  if (!data.home && !data.away) {
    return (
      <p className="ed-note">
        No profileable contact for either team yet this season.
      </p>
    );
  }
  return (
    <table
      className="ed-agate"
      aria-label="Season-to-date home-run probability by team at this park"
    >
      <caption>
        Each team&rsquo;s real batted balls since {data.since}, scored at this
        park by the batted-ball champion. Season to date, not this game, and not
        a &ldquo;typical&rdquo; batted ball. Team is current affiliation, so a
        mid-season trade attributes a batter&rsquo;s earlier contact to his new
        club.
      </caption>
      <thead>
        <tr>
          <th scope="col">Team</th>
          <th scope="col" className="ed-barcell">
            <VisuallyHidden>Share bar</VisuallyHidden>
          </th>
          <th scope="col" className="ed-num" style={{ width: "6rem" }}>
            P(HR)
          </th>
        </tr>
      </thead>
      <tbody>
        <TeamRow team={data.awayTeam} profile={data.away} />
        <TeamRow team={data.homeTeam} profile={data.home} />
      </tbody>
    </table>
  );
}
