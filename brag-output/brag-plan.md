# Brag Plan: The Bullpen

## What is this app?

[thebullpen.net](https://thebullpen.net) is a self-hosted baseball analytics platform whose real
product is the ML systems wrapper (model registry, A/B router, drift detection, retraining queue,
human-gated promotion) around four CHAMPION models, now dressed as an editorial newspaper front
page. Its README says it plainly: **"The wrapper is the project; the models are the excuse."**

## The angle

A newspaper's front page going to press, where the paper prints its own corrections in advance.

Most ML portfolio videos brag about accuracy. The Bullpen's new identity is a broadsheet, and a
broadsheet's credibility comes from what it refuses to overstate. So the video is set like an
edition: the dateline rule draws, a headline composed from live data slams in, and then each
"story" is a model that tells you exactly how far to trust it. The game page prints
"it is not an accuracy claim" under its own forecast. The margin note says low figures "are not
misses". The systems register shows a retrain that finished unattended in 96.8 minutes and is
still a candidate, because a champion changes only by a human promotion. The edition closes on
the footer the site prints on every page: "The Bullpen. Self-hosted, honestly scored."

Specific to this project because every line on screen is verbatim from the product's own
components (`slate-lines.ts`, `game-claims.ts`, `outcome-agate.tsx`, `ops-page.tsx`, the page
footers) and the visual grammar (dateline rule, slug, serif H1, italic dek, ruled columns, agate
tables, red only on links) is the [195] identity itself.

## Hook (first 2-3 seconds)

Paper ground, empty. The masthead nav sets ("The Bullpen" wordmark, Games underlined). The 3px
dateline rule draws left to right, the dateline prints in mono caps
(`SUNDAY, OCTOBER 11, 2026` / `THE SLATE` / `12 GAMES`), the slug "Games" appears, and on the
first beat the serif H1 slams in at broadsheet scale:

> **Four under way, five to come, three final.**

That headline is the real `boardHeadline()` grammar for a 4/5/3 slate. It reads as a sentence,
not a stat, which is the whole identity in one line. The three ruled scoreboard columns draw in
beneath it, and the red "LAD at MIL" link (the only red on the page) is clicked.

## Key moments (the middle)

- **The next pitch, by outcome.** The game page's agate: slug "At the plate", serif H2
  "The next pitch, by outcome", then five fixed-order rows (Ball, Called strike, Swinging strike,
  Foul, In play) with ink bars filling and agate figures (`.36`). The most probable row takes ink
  weight, never colour, exactly as decision [180] specifies. Then the italic caption, verbatim:
  _"Calibrated pre-pitch estimate. It passes calibration (ECE < 0.02); it is not an accuracy
  claim."_ Provenance in mono: `pitch_outcome_pre v2`.
- **The margin note.** The page's "MODEL GAVE IT" aside, pulled up to quote scale:
  _"Low figures are normal for a calibrated model; they are not misses."_
- **The systems register.** Slug "Ops", H1 "The systems register", the Serving line verbatim
  ("4 champions, 4 shadows, 2 candidates; slowest p99 119.1 ms"), then the agate model table:
  four Champion rows and `battedball_outcome v3`, Candidate, annotated "v3: retrained unattended in 96.8 minutes. Not promoted.".
  On a strong cue the "Held to" line lands: _"A champion changes only by a human promotion."_

## Outro / punchline

The page turns to the Night edition (#141612, the [192] toggle). Centered serif:

> The wrapper is the project;
> _the models are the excuse._

A footer rule draws and the site's own footer prints beneath it, the last thing on screen:

> The Bullpen. Self-hosted, honestly scored.

## User flow worth showing

1. **Entry**: the `/games` board, headline composed from the slate counts, three ruled columns.
2. **Key action**: click the red team-pair link; the game page opens to "The next pitch, by
   outcome" and the calibrated distribution fills in with its honesty caption.
3. **Result**: `/ops`, "The systems register": champions serving, a retrained candidate held at
   the human gate.

Scenes 1, 2 and 4 are HTML rebuilds of `scoreboard.tsx`, `outcome-agate.tsx` and
`pages/ops/ops-page.tsx`, styled from `frontend/src/design/editorial.css`.

Data note: the slate rows and the five outcome probabilities are illustrative values rendered
through the real components' grammar (the live capture had no game in progress). Every claim,
caption, model name, stage and operating figure on screen is verbatim from the repo or the live
2026-10-11 Ops capture (`docs/screenshots/ops.png`): 4 champions / 4 shadows / 2 candidates,
slowest p99 119.1 ms, the per-model p99s, and the 96.8-minute retrain (README).

## Tone

- Preset: **polished**
- Creative direction: _a newspaper's front page going to press_
- Interpretation: five scenes, crisp mechanical entrances (rules drawing, type setting, a
  headline that lands with weight) followed by still, long holds for reading. Ink on paper, no
  glows, no gradients, no colour except the single red link. The confidence comes from the copy
  being true and dated, so the motion never oversells.

## Format: landscape - 1920x1080

## Duration: 22.6s

## Visual identity (from the project)

From `frontend/src/design/editorial.css` (paper edition, plus the night edition for the outro):

- Background (paper ground): `#eceee6`; Night ground `#141612`
- Ink: `#16181d`; ink2 `#4a4f59`; ink3 `#62666f`
- Accent (links only): `#b3261e` (night `#f0806f`)
- Bar track `#dcdfd5`; hairline rule `#c4c8bb`
- Night ink `#e6e9e0`, night ink2 `#b0b4a8`, night rule `#34382f`
- Display font: **Source Serif 4** (variable, optical sizes; bold H1, italic dek)
- Body/UI font: **Inter**
- Figures and datelines: **JetBrains Mono**
- Strongest visual element: the dateline rule (3px ink over 1px ink) above a huge serif H1, and
  the agate table with ink bars on the pale track.

The composition self-hosts the site's own woff2 subsets from `frontend/public/fonts/`.

## Share copy (draft)

The Bullpen went to press in a new edition: a self-hosted baseball analytics site where the real
project is the ML systems wrapper (registry, A/B router, drift detection, retraining queue)
around four calibrated models. Its forecasts print "it is not an accuracy claim" underneath, and
a retrain that finished unattended in 96.8 minutes is still waiting on a human.

## Audio direction

- Role: **sparse professional accents over a steady clean bed**
- Music: `happy-beats-business-moves-vol-12-by-ende-dot-app.mp3` (steady and clean, the polished
  pick)
- Music treatment: from 0.0, fade in to ~0.30 over 0.5s, hold, fade to 0 across the last ~1.2s
  under the footer.
- Music cue guidance: bundled preset
  `assets/music/cues/happy-beats-business-moves-vol-12-by-ende-dot-app.music-cues.json`,
  109.96 BPM.
  - **0.56s** (beat): the hook H1 slams in.
  - **15.84s** (strongCue 0.96): "A champion changes only by a human promotion." lands.
  - **19.10s** (beat): the closing tagline sets in the Night edition (moved off the 18.56s strong cue so the Scene 4 gate line keeps its full reading hold).
  - Beat-grid window: Scene 2's five outcome rows set on beats/half-beats from 4.39s, then the
    full table holds ~4s for reading. Scene 4's five register rows set quickly from ~13.9s and
    hold. Labels are 1-3 words, so a fast set plus a long hold is the reading-safe pattern.
- Audio-reactive treatment: **subtle**. Per-frame RMS gently deepens a soft "press shade"
  vignette at the paper's edges. No waveform, no pulsing text.
- SFX posture: **sparse, motion-matched**. A soft impact under the headline slam, one click on the
  red link, a quiet drop as the agate sets, a soft impact on the human-gate line, a warm bong on
  the footer. The two honesty captions in Scene 2 and 3 land in near-silence on purpose.
- Restraint rule: no sound per row, nothing bright or glassy, nothing over the reading holds.

## Storyboard

### Scene 1 - Going to press - 0.0 to 3.6s (3.6s)

Masthead nav (wordmark "The Bullpen", Tonight / Games / Players | Models Accuracy Parks Guide Ops
About; Games underlined). Dateline rule draws; dateline prints `SUNDAY, OCTOBER 11, 2026 ·
THE SLATE · 12 GAMES`. Slug "Games". H1 slams: "Four under way, five to come, three final."
(settled ~0.9s to 3.8s, 2.9s for 7 words). Three ruled columns (In progress / Still to come /
Final) draw in beneath with two line-score rows each. The red "LAD at MIL" link gets a cursor
click at ~3.3s.
Sequential/interaction: yes - rule draws, type sets, three columns arrive left to right,
simulated click on the red link.
Audio intent: the press starting. Audio-coupled idea: soft impact on the H1 slam; click on link.
Music: bed fading in.
Transition mood: clean, a quick paper slide to the game page -> Scene 2

### Scene 2 - The next pitch, by outcome - 3.6 to 9.8s (6.2s)

Dateline `SUNDAY, OCTOBER 11, 2026 · GAME DAY · LAD AT MIL`. Slug "At the plate", H2 "The next
pitch, by outcome". Agate: header OUTCOME / PROB., five rows set one by one with ink bars filling
(Ball .36, Called strike .17, Swinging strike .11, Foul .21, In play .15; illustrative). The Ball
row takes ink weight. Caption lands ~6.0s and holds to 9.8s (3.4s+, 15 words). Provenance
`pitch_outcome_pre v2`.
Sequential/interaction: yes - five rows arrive in fixed order (4.39 to 5.34s), then hold.
Audio intent: quiet precision. Audio-coupled idea: one soft drop as the table sets; caption is
unscored.
Transition mood: soft, reframe to the margin -> Scene 3

### Scene 3 - The margin note - 9.8 to 13.8s (4.0s)

The "MODEL GAVE IT" aside as a pull quote: small caps label, rule, then large serif italic:
"Low figures are normal for a calibrated model; they are not misses." (settled ~10.3s to 13.4s,
3.1s for 12 words). Faint pitch-by-pitch account rows at left as texture.
Sequential/interaction: none.
Audio intent: near-silence over the bed; the line speaks.
Transition mood: soft -> Scene 4

### Scene 4 - The systems register - 13.8 to 18.9s (5.1s)

Nav switches to Ops. Dateline `OPS DESK, READ 7:45 PM ET`. Slug "Ops", H1 "The systems
register", Serving line "4 champions, 4 shadows, 2 candidates; slowest p99 119.1 ms". Agate
table (MODEL / STAGE / P99 MS): battedball_outcome v2 Champion 1.3, pitch_outcome_post v1
Champion 19.7, pitch_outcome_pre v2 Champion 10.2, pitch_type_pre v1 Champion 40.8,
battedball_outcome v3 Candidate with the note "v3: retrained unattended in 96.8 minutes. Not promoted.". At 15.84s (strong cue) the
line "A champion changes only by a human promotion." sets beneath (held ~2.4s).
Sequential/interaction: yes - five rows set quickly then hold; the v3 row is called out.
Audio intent: the hinge of the story. Audio-coupled idea: soft impact on the human-gate line.
Transition mood: the page turns to the Night edition -> Scene 5

### Scene 5 - Night edition - 18.9 to 22.6s (3.7s)

Ground crossfades to #141612. Centered serif: "The wrapper is the project;" / italic "the models
are the excuse." lands 18.56s (strong cue). Footer rule draws ~20.2s and the mono footer
"The Bullpen. Self-hosted, honestly scored." prints (held ~2s). Music fades out under it.
Sequential/interaction: none.
Audio intent: resolve. Audio-coupled idea: warm bong on the footer.
Transition mood: end on the held footer frame.

**Music mood for this video:** steady, clean, understated
**Audio summary:** a clean bed fades in as the press starts, accents only where type lands with
weight, near-silence under the honesty lines, a single warm bong on the footer, then out.
