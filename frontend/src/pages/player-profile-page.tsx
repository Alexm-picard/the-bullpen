/**
 * `/players/:id` - a player's file on the [195] editorial identity (SPEC-players, layout B
 * "Magazine spread").
 *
 * A NUMERIC :id is a real player: the lede (slug, name, a dek GENERATED from his record, three
 * figures) beside a ranked aside, then one hero - a pitcher's velocity by pitch, a batter's contact
 * scatter, both for a two-way player (pitcher first) - then "What the models said". Every number
 * comes from the live hooks; usePlayer only knows name, position, team and active, so no bio is
 * shown or invented.
 *
 * A non-numeric :id keeps the showcase matchup (`/players/judge_aaron`), now labelled as a
 * showcase file with a role="note" banner (D4), defaulting to Judge against Skubal.
 */
import { VisuallyHidden } from "@mantine/core";
import { useEffect, useState, type ReactNode } from "react";
import { Link, useParams } from "react-router";

import {
  useBatterBattedBalls,
  usePitcherArsenal,
  usePlayer,
  type PlayerSearchResult,
} from "../api/players";
import { BUILD_DATE, BUILD_SHA } from "../build-info";
import { CallsSection } from "../components/players/calls-section";
import { ContactSection } from "../components/players/contact-section";
import { ShowcaseFile } from "../components/players/showcase-file";
import { VelocityStrips } from "../components/players/velocity-strips";
import { PitchTypeAgate } from "../components/home/pitch-type-agate";
import {
  batterDek,
  pitcherDek,
  rankArsenal,
  rankBattedTypes,
} from "../lib/player-dek";
import {
  batterFigures,
  battedWindowPhrase,
  pitcherFigures,
  positionWord,
  roleOf,
  teamPlace,
  type Figure,
  type PlayerRole,
} from "../lib/player-file";
import { pitchName } from "../lib/pitch-type-prior";

/** The batted-ball window the file reads (the hook's default limit). */
const BATTED_WINDOW = 200;

/** Module-level: the edition "arrives" once per session, as on the other [195] pages. */
let entranceShown = false;

const ET_DATE = new Intl.DateTimeFormat("en-US", {
  weekday: "long",
  month: "long",
  day: "numeric",
  year: "numeric",
  timeZone: "America/New_York",
});

const BB_CODE: Readonly<Record<string, string>> = {
  ground_ball: "GB",
  line_drive: "LD",
  fly_ball: "FB",
  popup: "PU",
};
const BB_LABEL: Readonly<Record<string, string>> = {
  ground_ball: "Ground ball",
  line_drive: "Line drive",
  fly_ball: "Fly ball",
  popup: "Popup",
};

type Enter = (
  base: string,
  i: number,
) => {
  className: string;
  style?: React.CSSProperties;
};

function Dateline({ middle, right }: { middle: string; right: string }) {
  return (
    <p className="ed-dateline">
      <span>{ET_DATE.format(new Date())}</span>
      <span className="ed-dateline__issued">{middle}</span>
      <span>{right}</span>
    </p>
  );
}

/** A static, pre-sized loading block (no shimmer). A span, so it is valid inside a heading. */
function Shell({ height, width = "100%" }: { height: string; width?: string }) {
  return (
    <span
      className="ed-shell"
      aria-hidden="true"
      style={{ display: "block", height, width, margin: "0.5rem 0" }}
    />
  );
}

function Figures({ figures }: { figures: Figure[] }) {
  return (
    <div className="ed-front__figs ed-figs">
      {figures.map((f) => (
        <p className="ed-fig" key={f.label}>
          <span
            className={
              f.value == null ? "ed-fig__value pl-fig-empty" : "ed-fig__value"
            }
          >
            {f.value ?? "–"}
          </span>
          <span className="ed-fig__label">{f.label}</span>
          <span className="ed-fig__basis">{f.basis}</span>
        </p>
      ))}
    </div>
  );
}

function slugFor(p: PlayerSearchResult): string {
  const place = teamPlace(p.team);
  return [positionWord(p.primaryPosition), place, p.active ? null : "inactive"]
    .filter(Boolean)
    .join(", ");
}

function LiveFile({ id, enter }: { id: number; enter: Enter }) {
  const player = usePlayer(id);
  const role: PlayerRole | null = player.data
    ? roleOf(player.data.primaryPosition)
    : null;
  const pitches = role === "pitcher" || role === "two-way";
  const bats = role === "batter" || role === "two-way";
  const arsenal = usePitcherArsenal(pitches ? id : null);
  // Unfiltered: the dek, figures and aside describe the whole window. The contact section's
  // default filters share this exact query key, so the first paint is one request, not two.
  const balls = useBatterBattedBalls(bats ? id : null, {});

  const notFound = player.isError && player.error.status === 404;
  const dateRight = player.data
    ? `${slugFor(player.data)}, Statcast ${id}`
    : `Statcast ${id}`;

  if (player.isError) {
    return (
      <>
        <Dateline middle="Player file" right={dateRight} />
        <div className="pl-head">
          <p className="ed-slug">Players</p>
          <h1 className="ed-h1">
            {notFound ? "No player with that ID" : "Could not load this player"}
          </h1>
          <p className="ed-dek">
            {notFound
              ? `Nothing on file for Statcast ID ${id}. `
              : "The player service did not answer. "}
            <Link className="ed-link" to="/players">
              Back to the index
            </Link>
          </p>
        </div>
      </>
    );
  }

  // Lede pieces per role (a two-way player's lede reads from the arsenal, pitcher first).
  let dek: ReactNode = null;
  let figures: Figure[] | null = null;
  let aside: ReactNode = null;
  let heroLink: ReactNode = null;
  const season = new Date().getUTCFullYear();

  if (role != null && pitches) {
    heroLink = (
      <a className="ed-link" href="#velocity">
        Velocity by pitch
      </a>
    );
    if (arsenal.isError) {
      dek = <p className="ed-dek">Could not load his arsenal.</p>;
      figures = pitcherFigures([]);
    } else if (arsenal.isPending) {
      dek = <Shell height="4.5rem" width="80%" />;
    } else {
      const text = pitcherDek(arsenal.data);
      dek = text ? <p {...enter("ed-dek", 2)}>{text}</p> : null;
      figures = pitcherFigures(arsenal.data);
      aside = (
        <aside className="ed-front__aside" aria-labelledby="pl-mix-title">
          <h2 className="ed-h2" id="pl-mix-title">
            His mix, ranked
          </h2>
          <p className="ed-sub">Share of every pitch he has thrown.</p>
          <PitchTypeAgate
            rows={rankArsenal(arsenal.data).map((p) => ({
              code: p.pitchType,
              label: pitchName(p.pitchType),
              share: p.usagePct,
            }))}
            caption="All seasons on file, most-thrown first. Usage, not a call."
            valueHeader="Usage"
            note={
              arsenal.data.length === 0
                ? "No pitches on file for him yet."
                : undefined
            }
            provenance="Statcast pitch record, all seasons"
          />
        </aside>
      );
    }
  } else if (role === "batter") {
    heroLink = (
      <a className="ed-link" href="#contact">
        Every ball in play
      </a>
    );
    if (balls.isError) {
      dek = <p className="ed-dek">Could not load his balls in play.</p>;
      figures = batterFigures([], BATTED_WINDOW);
    } else if (balls.isPending) {
      dek = <Shell height="4.5rem" width="80%" />;
    } else {
      const text = batterDek(balls.data, season, BATTED_WINDOW);
      dek = text ? <p {...enter("ed-dek", 2)}>{text}</p> : null;
      figures = batterFigures(balls.data, BATTED_WINDOW);
      const phrase = battedWindowPhrase(balls.data.length, BATTED_WINDOW);
      aside = (
        <aside className="ed-front__aside" aria-labelledby="pl-bb-title">
          <h2 className="ed-h2" id="pl-bb-title">
            His balls in play, by type
          </h2>
          <p className="ed-sub">{`Share of ${phrase}.`}</p>
          <PitchTypeAgate
            rows={rankBattedTypes(balls.data).map((t) => ({
              code: BB_CODE[t.type] ?? t.type,
              label: BB_LABEL[t.type] ?? t.type,
              share: t.share,
            }))}
            caption="Statcast hit types; a share of the record, not a call."
            valueHeader="Share"
            labelHeader="Type"
            note={
              balls.data.length === 0 ? "No balls in play on file." : undefined
            }
            provenance="Statcast batted-ball record, newest first"
          />
        </aside>
      );
    }
  }

  return (
    <>
      <Dateline middle="Player file" right={dateRight} />
      <div className="ed-front pl-front">
        <div className="ed-front__head">
          {player.data ? (
            <>
              <p {...enter("ed-slug", 0)}>{slugFor(player.data)}</p>
              <h1 {...enter("ed-h1", 1)}>{player.data.name}</h1>
            </>
          ) : (
            <>
              <Shell height="1rem" width="10rem" />
              <h1 className="ed-h1" aria-busy="true">
                <VisuallyHidden>Loading the player</VisuallyHidden>
                <Shell height="3.5rem" width="60%" />
              </h1>
            </>
          )}
          {dek}
        </div>
        {figures ? (
          <Figures figures={figures} />
        ) : (
          <div className="ed-front__figs ed-figs" aria-hidden="true">
            {[0, 1, 2].map((i) => (
              <Shell key={i} height="5rem" />
            ))}
          </div>
        )}
        <div className="ed-front__links">
          {heroLink}
          <a className="ed-link" href="#calls">
            The calls
          </a>
          <Link className="ed-link" to="/accuracy">
            How we score ourselves
          </Link>
        </div>
        {aside}
      </div>

      {pitches ? (
        <section
          className="pl-part"
          id="velocity"
          aria-labelledby="pl-velo-title"
        >
          <div className="pl-part__head">
            <p className="ed-slug">Velocity</p>
            <span className="pl-asof">one fixed scale, 65 to 105 mph</span>
          </div>
          <h2 className="ed-h2" id="pl-velo-title">
            How hard, pitch by pitch
          </h2>
          <p className="ed-sub">
            Each bar runs from his slowest to his fastest of that pitch; the
            solid tick is the average.
          </p>
          {arsenal.isError ? (
            <p className="ed-note">Could not load his arsenal.</p>
          ) : arsenal.isPending ? (
            <Shell height="14rem" />
          ) : arsenal.data.length === 0 ? (
            <p className="ed-note">No pitches on file for him yet.</p>
          ) : (
            <VelocityStrips arsenal={arsenal.data} />
          )}
        </section>
      ) : null}
      {bats ? (
        <div id="contact">
          <ContactSection playerId={id} />
        </div>
      ) : null}

      <CallsSection playerId={id} />
    </>
  );
}

export default function PlayerProfilePage() {
  const { id } = useParams<{ id: string }>();
  // Search and the index navigate with a NUMERIC Statcast id; the demo matchups use a string slug.
  const isRealPlayer = id != null && /^\d+$/.test(id);
  const [animate] = useState(() => !entranceShown);
  useEffect(() => {
    entranceShown = true;
  }, []);
  const enter: Enter = (base, i) =>
    animate
      ? {
          className: `${base} ed-enter`,
          style: { "--ed-stagger": i } as React.CSSProperties,
        }
      : { className: base };

  return (
    <div className="ed-page">
      <div className="ed-col">
        {isRealPlayer ? (
          <LiveFile id={Number(id)} enter={enter} />
        ) : (
          <>
            <Dateline
              middle="Showcase file"
              right="Demonstration, illustrative values"
            />
            <ShowcaseFile slug={id} />
          </>
        )}
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
