import type { TeamContactResponse, TeamHrProfile } from "../../api/games";
import { colors, typography } from "../../design/broadcast";

/**
 * The pre-first-pitch state of the batted-ball card: both teams' SEASON-TO-DATE contact, scored at
 * this park by the same champion the live card uses.
 *
 * <p>What it deliberately is not: a "typical" batted ball. Taking a team's median launch speed and
 * median launch angle would produce a point that never occurred, and feeding that to the model
 * would be a fabricated input wearing a statistic. Every number here is a mean over REAL balls
 * someone actually hit, and n is shown because a mean over 8 and a mean over 800 are not the same
 * claim.
 *
 * <p>It is also a DIFFERENT claim from the live card it stands in for - season-to-date rather than
 * this game - so the copy says so rather than letting the layout imply continuity.
 */
export type TeamContactPanelProps = {
  data: TeamContactResponse | undefined;
  isLoading: boolean;
  error: unknown;
};

const muted: React.CSSProperties = {
  margin: 0,
  fontFamily: typography.fonts.mono,
  fontSize: 12,
  letterSpacing: "0.02em",
  color: colors.textMuted,
};

function TeamRow({
  team,
  profile,
}: {
  team: string;
  profile: TeamHrProfile | null;
}) {
  return (
    <li
      style={{
        display: "grid",
        gridTemplateColumns: "72px 1fr 96px",
        alignItems: "center",
        gap: 10,
        padding: "4px 0",
      }}
    >
      <span
        style={{
          fontFamily: typography.fonts.display,
          fontSize: 15,
          color: colors.ink,
        }}
      >
        {team}
      </span>
      <span
        aria-hidden="true"
        style={{
          display: "block",
          height: 10,
          background: colors.fieldSubtle,
          overflow: "hidden",
        }}
      >
        <span
          style={{
            display: "block",
            height: "100%",
            // Scaled against a 10% ceiling: league HR-per-BIP sits well under that, so a linear
            // bar to 100% would render every team as a stub and hide the difference the card is
            // about. The number beside it is the claim; the bar is only a comparison aid.
            width: profile
              ? `${Math.min(profile.meanHrProbability * 1000, 100)}%`
              : "0%",
            background: colors.steel,
          }}
        />
      </span>
      <span
        style={{
          fontFamily: typography.fonts.mono,
          fontSize: 12,
          fontFeatureSettings: '"tnum" 1',
          textAlign: "right",
          color: colors.textMuted,
        }}
      >
        {profile
          ? `${(profile.meanHrProbability * 100).toFixed(1)}% · n=${profile.n}`
          : "no profile"}
      </span>
    </li>
  );
}

export function TeamContactPanel({
  data,
  isLoading,
  error,
}: TeamContactPanelProps) {
  if (error) {
    return <p style={muted}>Could not load the season-to-date comparison.</p>;
  }
  if (isLoading || !data) {
    return (
      <p aria-busy="true" style={muted}>
        Scoring both teams&rsquo; recent contact at this park&hellip;
      </p>
    );
  }
  if (!data.home && !data.away) {
    return (
      <p style={muted}>
        No profileable contact for either team yet this season.
      </p>
    );
  }
  return (
    <div>
      <ul
        aria-label="Season-to-date home-run probability by team at this park"
        style={{ listStyle: "none", margin: 0, padding: 0 }}
      >
        <TeamRow team={data.awayTeam} profile={data.away} />
        <TeamRow team={data.homeTeam} profile={data.home} />
      </ul>
      <p style={{ ...muted, marginTop: 8 }}>
        Each team&rsquo;s REAL batted balls since {data.since}, scored at this
        park by the batted-ball champion - not this game, and not a
        &ldquo;typical&rdquo; batted ball. Team is current affiliation, so a
        mid-season trade attributes a batter&rsquo;s earlier contact to his new
        club.
      </p>
    </div>
  );
}
