/**
 * `/players` - the player index on the [195] editorial identity (SPEC-players, layout A "Almanac").
 *
 * Read order: dateline; the head (slug, H1, dek, the search field) beside "What a player file
 * holds" (what is live, what is not yet, what is showcase); the index (positions, teams by
 * division, the chosen facet's roster); then the fenced Showcase part. The showcase lists are
 * hand-written fixtures and say so inline (D4); they never sit in the lede.
 *
 * The profile at `/players/:id` lives in its own route module, `player-profile-page.tsx`.
 */
import { useEffect, useState } from "react";
import { useNavigate } from "react-router";

import { BUILD_DATE, BUILD_SHA } from "../build-info";
import { BrowsePlayers } from "../components/players/browse-players";
import { FeaturedReports } from "../components/players/featured-reports";
import { ModelStandouts } from "../components/players/model-standouts";
import { PlayerSearch } from "../components/players/player-search";
import { ShowcaseNote } from "../components/players/showcase-note";
import { FEATURED_REPORTS } from "../data/players-landing-fixtures";

/** Module-level: the edition "arrives" once per session, as on the other [195] pages. */
let entranceShown = false;

const ET_DATE = new Intl.DateTimeFormat("en-US", {
  weekday: "long",
  month: "long",
  day: "numeric",
  year: "numeric",
  timeZone: "America/New_York",
});

export default function PlayersPage() {
  const navigate = useNavigate();
  const [animate] = useState(() => !entranceShown);
  useEffect(() => {
    entranceShown = true;
  }, []);
  const enter = (base: string, i: number) =>
    animate
      ? {
          className: `${base} ed-enter`,
          style: { "--ed-stagger": i } as React.CSSProperties,
        }
      : { className: base };

  return (
    <div className="ed-page">
      <div className="ed-col">
        <p className="ed-dateline">
          <span>{ET_DATE.format(new Date())}</span>
          <span className="ed-dateline__issued">Player index</span>
          <span>Active rosters, all 30 teams</span>
        </p>

        <div className="pl-split pl-head">
          <div>
            <p {...enter("ed-slug", 0)}>Players</p>
            <h1 {...enter("ed-h1", 1)}>Look up a player.</h1>
            <p {...enter("ed-dek", 2)}>
              Every arsenal and batted ball on file, and the calls the models
              made about him, graded once the pitch was thrown.
            </p>
            <PlayerSearch
              autoFocus
              onSelect={(p) => {
                navigate(`/players/${p.id}`);
              }}
            />
          </div>
          <aside className="pl-holds" aria-labelledby="pl-holds-title">
            <p className="ed-label" id="pl-holds-title">
              What a player file holds
            </p>
            <p>
              Live from the record: a pitcher&rsquo;s arsenal, a batter&rsquo;s
              balls in play, and every settled call the models made in his
              games.
            </p>
            <dl>
              <dt>Live</dt>
              <dd>
                Arsenal, batted balls, settled calls, and where the calls landed
                by confidence.
              </dd>
              <dt>Not yet</dt>
              <dd>
                Whether his calls came true at the rates they claimed. That
                needs a per-player truth-join we do not run yet.
              </dd>
              <dt>Showcase</dt>
              <dd>
                The lists at the foot of this page use illustrative values and
                say so.
              </dd>
            </dl>
          </aside>
        </div>

        <BrowsePlayers />

        <section className="pl-part" aria-labelledby="pl-showcase-title">
          <div className="pl-part__head">
            <p className="ed-slug">Showcase</p>
            <span className="pl-asof">fixtures, not from the models</span>
          </div>
          <h2 className="ed-h2" id="pl-showcase-title">
            From the files
          </h2>
          <ShowcaseNote>
            Illustrative values. There is no leaders endpoint yet, so these
            lists are written by hand; the names link to live files.
          </ShowcaseNote>
          <div className="pl-split pl-showcase-grid">
            <div>
              <h3 className="ed-h3">Featured reports</h3>
              <FeaturedReports reports={FEATURED_REPORTS} />
            </div>
            <div>
              <h3 className="ed-h3">Model standouts</h3>
              <ModelStandouts />
            </div>
          </div>
        </section>

        <footer className="ed-footer">
          <span>The Bullpen. Self-hosted, honestly scored.</span>
          <span>
            build {BUILD_SHA}, {BUILD_DATE}
          </span>
        </footer>
      </div>
    </div>
  );
}
