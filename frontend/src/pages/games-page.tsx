/**
 * /games - the LIVE slate on the BROADCAST identity (redesign PR-3, decision
 * [160]; the slate went live in FE-H1 and keeps every data contract here).
 *
 * Composition: condensed-italic masthead + mono context line, the telecast
 * slate strips (<TodaysSlateTable>: team-color bars, wedge state blocks, gold
 * on-air dots, numeric /games/{gameId} hrefs), and the chrome footer.
 *
 * Data wiring unchanged: `useTodaysGames()` polls at 60s; [] is a first-class
 * empty state (a game appears at its first OBSERVED status transition, ~first
 * pitch; restart-mid-game defers to the next poll's re-persist, L1). This page
 * imports ONLY the broadcast token namespace ([160] migration rule).
 */
import { useTodaysGames } from "../api/games";
import { LowerThird } from "../components/broadcast/lower-third";
import { TodaysSlateTable } from "../components/games/todays-slate-table";
import { colors, layouts, typography } from "../design/broadcast";

/** Stable build metadata fallback so the footer always renders. */
const BUILD_FALLBACK = {
  sha: "live",
  date: new Date().toISOString().slice(0, 10),
};

function todayIssueDate(): string {
  return new Intl.DateTimeFormat("en-US", {
    weekday: "short",
    month: "short",
    day: "numeric",
    year: "numeric",
  }).format(new Date());
}

const fieldStyle: React.CSSProperties = {
  backgroundColor: colors.field,
  minHeight: "100%",
  padding: "24px 16px 0",
};

const columnStyle: React.CSSProperties = {
  maxWidth: layouts.broadcastMaxWidth,
  margin: "0 auto",
  display: "flex",
  flexDirection: "column",
  gap: 24,
};

export default function GamesPage() {
  const games = useTodaysGames();
  const slate = games.data ?? [];

  return (
    <div style={fieldStyle}>
      <div style={columnStyle}>
        <header>
          <h1
            style={{
              margin: 0,
              fontFamily: typography.fonts.display,
              fontStyle: "italic",
              fontWeight: typography.weights.heavy,
              fontSize: typography.scale[6],
              lineHeight: typography.lineHeights.display,
              letterSpacing: "0.01em",
              textTransform: "uppercase",
              color: colors.ink,
            }}
          >
            Today&rsquo;s Games
          </h1>
          <p
            style={{
              margin: "2px 0 0",
              fontFamily: typography.fonts.mono,
              fontSize: 12,
              fontFeatureSettings: '"tnum" 1',
              letterSpacing: "0.02em",
              color: colors.textMuted,
            }}
          >
            {todayIssueDate()} &middot; live ingest &middot; slate fills as
            games go live
          </p>
        </header>

        <section aria-labelledby="games-slate-label">
          <div style={{ marginBottom: 12 }}>
            <LowerThird
              id="games-slate-label"
              meta={slate.length > 0 ? `${slate.length} TRACKED` : undefined}
            >
              Today&rsquo;s Slate
            </LowerThird>
          </div>
          {games.isError ? (
            <p
              style={{
                fontFamily: typography.fonts.body,
                fontWeight: typography.weights.semibold,
                color: colors.goldInk,
              }}
            >
              Could not load today&rsquo;s games
              {games.error instanceof Error ? `: ${games.error.message}` : ""}.
            </p>
          ) : games.isLoading ? (
            <p
              style={{
                fontFamily: typography.fonts.body,
                color: colors.textMuted,
              }}
            >
              Loading today&rsquo;s slate&hellip;
            </p>
          ) : (
            <TodaysSlateTable
              games={slate}
              caption={`live game tracker · ${todayIssueDate()}`}
            />
          )}
        </section>

        <footer
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            margin: "0 -16px",
            padding: "10px 16px",
            backgroundColor: colors.chromeDeep,
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            letterSpacing: "0.04em",
            color: colors.textOnChromeMuted,
          }}
        >
          <span>THE BULLPEN · LIVE SLATE</span>
          <span>
            build {BUILD_FALLBACK.sha} · {BUILD_FALLBACK.date}
          </span>
        </footer>
      </div>
    </div>
  );
}
