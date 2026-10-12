# Hyperframes Composition Brief: The Bullpen

## Objective

Create a short launch-style brag video for The Bullpen's new editorial "Front Page" edition.

## Output

- Composition directory: `brag-output/composition/`
- Rendered video: `brag-output/brag.mp4`
- Format: landscape - 1920x1080, 30 fps
- Duration: 22.6 seconds

## Source Material

- Project root: `/Users/alexpicard/thebullpen`
- Primary files read: `README.md`, `frontend/src/design/editorial.css`,
  `frontend/src/components/games/scoreboard.tsx`, `slate-lines.ts`, `outcome-agate.tsx`,
  `game-account.ts`, `frontend/src/data/game-claims.ts`, `frontend/src/data/games-claims.ts`,
  `frontend/src/pages/ops/ops-page.tsx`, `docs/screenshots/{games,game,ops}.png`,
  `docs/postmortems/2026-08-03_pitcher-form-silent-staleness.md`, prior `brag-output/brag-plan.md`
- Product name: The Bullpen (thebullpen.net)
- Tagline / strongest claim: "The wrapper is the project; the models are the excuse."
- Key UI to recreate: the /games board headline + ruled columns, the game page's "The next pitch,
  by outcome" agate, the /ops "The systems register" model table.
- Copy that must appear verbatim:
  - Four under way, five to come, three final.
  - The next pitch, by outcome
  - Calibrated pre-pitch estimate. It passes calibration (ECE < 0.02); it is not an accuracy claim.
  - Low figures are normal for a calibrated model; they are not misses.
  - The systems register
  - 4 champions, 4 shadows, 2 candidates; slowest p99 119.1 ms
  - A champion changes only by a human promotion. (clause of the Ops "Held to" line)
  - The wrapper is the project; the models are the excuse.
  - The Bullpen. Self-hosted, honestly scored.

## Creative Direction

- Tone preset: polished
- Creative direction: a newspaper's front page going to press
- Interpretation: mechanical, crisp entrances (rules drawing, type setting, a weighted headline
  slam), then still holds. Ink on paper. No glow, gradient, or decorative colour.
- Angle: the paper prints its own corrections in advance. Each "story" is a model that tells you
  how far to trust it, and the edition closes on the honest footer.
- Hook: dateline rule draws, mono dateline prints, serif H1 "Four under way, five to come, three
  final." slams in on the first beat.
- Outro / punchline: Night edition, the README tagline, then the footer line last.
- Avoid: generic SaaS language, abstract filler, any red that is not a link, any colour on data
  (the top outcome row gets ink weight only, per [180]).

## Visual Identity

- Background: `#eceee6` (paper), `#141612` (night, outro only)
- Text: `#16181d` ink, `#4a4f59` ink2, `#62666f` ink3; night `#e6e9e0` / `#b0b4a8`
- Accent: `#b3261e` (links only)
- Track `#dcdfd5`, rule `#c4c8bb`, night rule `#34382f`
- Display font: Source Serif 4 (self-hosted variable woff2 from `frontend/public/fonts`)
- Body font: Inter (self-hosted); figures/datelines: JetBrains Mono (self-hosted)
- Visual references: dateline (3px ink top rule, 1px bottom, mono caps), slug + H1 + italic dek,
  agate tables with 1px ink header rule and dotted row rules, ink bars on the pale track.

## Storyboard

Use the storyboard in `brag-plan.md` as the creative contract.

1. Going to press - 3.6s - masthead, dateline, H1 slam, three ruled columns, click red link
2. The next pitch, by outcome - 6.2s - agate fills, ink weight on top row, caption holds
3. The margin note - 4.0s - "they are not misses" pull quote
4. The systems register - 5.1s - Serving line, model table, v3 candidate, human-gate line
5. Night edition - 3.7s - tagline, footer rule, footer line

## Audio

- Audio role: sparse professional accents over a steady clean bed
- Audio arc: fade in, accents on weighted type, near-silence under honesty lines, bong on footer,
  fade out
- Music: `happy-beats-business-moves-vol-12-by-ende-dot-app.mp3`
- Music treatment: volume automation 0 -> 0.30 over 0.5s, hold, -> 0 over the last 1.2s
- Music cue guidance: bundled preset (109.96 BPM). Locks: 0.56s H1 slam (beat), 15.84s human-gate
  line (strong 0.96), 19.10s tagline (beat). Beat grid for Scene 2 rows from 4.39s.
- Audio-reactive treatment: subtle; RMS deepens a soft edge "press shade" on the paper.
- Audio-coupled moments: H1 slam (soft impact), link click (click), agate set (drop), gate line
  (soft impact), footer (bong)
- SFX selection guidance: warm, low HF risk files from `sfx-analysis.md`; volumes 0.35-0.6
- Exact SFX choice: chosen after the animation exists.
- Audio files: copied into `composition/assets/`

## Hyperframes Instructions

Built with hyperframes-core / animation / creative / keyframes / cli. Single paused GSAP
timeline registered at `window.__timelines["main"]`, self-hosted fonts with `@font-face`,
`npx hyperframes check` must pass with zero errors before render.
