# Hyperframes Composition Brief: The Bullpen

## Objective

Create a short launch-style brag video for **The Bullpen** (thebullpen.net) - a self-hosted
baseball analytics platform whose real product is a hand-written ML systems wrapper. The video's
argument is that the discipline around the models is more impressive than the models.

## Output

- Composition directory: `brag-output/composition/`
- Rendered video: `brag-output/brag.mp4`
- Format: landscape - **1920x1080**
- Duration: **25.0s** (5 scenes)

## Source Material

- Project root: `/Users/alexpicard/thebullpen`
- Primary files read:
  - `README.md` (claims, numbers, the tagline)
  - `frontend/src/design/tokens.css` + `frontend/src/design/broadcast.ts` (exact palette, fonts)
  - `frontend/src/components/games/next-pitch-panel.tsx` (the left panel in Scene 2 - verbatim
    class order, labels, bar structure and honesty caption)
  - `frontend/src/components/games/pitch-type-panel.tsx` (the right panel in Scene 2 - verbatim
    class labels, descending rank order, and the **[183] constraint that every row is styled
    identically with no argmax emphasis anywhere**)
  - `frontend/src/components/ops/model-fleet-table.tsx` (the registry row in Scene 4)
  - `docs/screenshots/game-live.jpeg`, `docs/screenshots/ops.png` (layout reference; note these
    predate the dark-field rework, so use the **dark** token values, not the screenshots' light field)
- Product name: **The Bullpen**
- Tagline / strongest claim: *"The wrapper is the project; the models are the excuse."*
- Key UI moments to recreate:
  1. The **broadcast scorebug + count tile** from `/games/:id` (gold left tick, clipped-corner
     chrome plate, mono numerals, pulsing green LIVE dot).
  2. The **NextPitchPanel** - five labelled probability rows, flat bar tracks, right-aligned mono
     percentages, the winning class in gold.
  3. The **PitchTypePanel** beside it - seven rows ranked by probability, every row styled
     identically. Nothing highlighted, ever (decision [183]).
  4. An **Ops model-fleet registry row** with a STAGE cell.

### Copy that must appear verbatim

- `MIL 3 · STL 3 · INN 6 · LIVE`
- `0-1` / `1` / `FC · 95.0` / `79` (count, outs, last pitch, pitcher pitches)
- `The pitch hasn't been thrown yet.`
- `Ball` `Called strike` `Swinging strike` `Foul` `In play` (this exact order - it is the
  component's canonical 5-class display order)
- `34.2%` `18.6%` `11.9%` `21.4%` `13.9%`
- `pitch_outcome_pre v2 - calibrated estimate, not an accuracy claim.`
- `Four-seam` `Slider` `Sinker` `Changeup` `Curveball` `Cutter` `Other` (ranked descending, the
  component's display order)
- `31.8%` `20.4%` `15.1%` `12.6%` `9.7%` `6.9%` `3.5%`
- `pitch_type_pre v1 - calibrated prior, not a top-1 prediction.`
- `PRE-PITCH HEAD` / `CALIBRATED PRIOR` (the two panel tags)
- `Written from scratch in Java. No MLflow.`
- `REGISTRY` `SHADOW A/B ROUTER` `DRIFT` `RETRAIN QUEUE`
- `battedball_outcome v3`
- `96.8 min` / `0 interventions`
- `Promotion stays human-gated.`
- `CANDIDATE` / `NOT PROMOTED`
- `p99 34 ms · 300 req/s · 2,400+ tests · 193 decisions · 6 postmortems`
- `THE BULLPEN`
- `The wrapper is the project;` / `the models are the excuse.`
- `thebullpen.net`

**These numbers are all real and sourced from the project's own README and components. Do not
invent, round, or embellish any figure.**

## Creative Direction

- Tone preset: **polished**
- Creative direction: *a broadcast engineering film - telecast chrome, analytical restraint, the
  honesty is the flex*
- Interpretation: five scenes, long settled holds, soft crossfades (0.5-0.6s). Entries are snappy
  (0.3-0.5s) and then **stop completely** - no drifting, no idle float, no looping ambient motion
  behind text. Nothing strobes. The claims are real and dated, so the edit never needs to oversell.
  Display type is Barlow Condensed italic; every number is JetBrains Mono.
- Angle: Most ML portfolio videos brag about accuracy. This one brags about restraint. The hook is a
  live pitch being scored before it is thrown; the payoff is the caption underneath that prediction
  ("calibrated estimate, not an accuracy claim") and the beat where an unattended retrain finishes
  in 96.8 minutes with zero interventions and is then deliberately **not promoted**, because
  promotion stays human-gated. Every line is lifted from the product's own surfaces.
- Hook: the scorebug snaps in, the mono count tile punches up `0-1`, and one condensed-italic line
  lands - *The pitch hasn't been thrown yet.*
- Outro / punchline: the ledger strip, then the lockup with the README's own line.
- Avoid:
  - Generic SaaS language ("streamline", "supercharge", "powerful")
  - Abstract filler visuals - no particle fields, no gradient washes, no floating orbs
  - Unrelated visual redesign - this design system is locked and linted; stay on its tokens
  - Any accuracy claim for any model, any invented metric, any rounded-up number
  - **Any visual emphasis on a pitch-type row** - no gold bar, no bold label, no distinct colour,
    no scale pop. Decision [183] is enforced in three independent places in the codebase and the
    video must not be the fourth place it leaks. The outcome head's argmax earns gold; the prior's
    does not.
  - Baseball stock imagery, photographs, or emoji

## Visual Identity

Exact values from `frontend/src/design/tokens.css` (`:root[data-theme="dark"]`, the committed
default) - these are the only colors that may appear:

- Background (field): `#080F1F`
- Elevated field: `#0E1B33` (also the constant chrome color)
- Chrome deep: `#080F1F` / chrome edge: `#26365C`
- Panel: `#101D38`, panel edge `#26365C`, rule `#1C2A4A`
- Accent gold: `#F2A900`, deep gold `#C98D00`
- Live green: `#39D98A`
- Text: ink `#F4F6FA`, body `#C9D1E0`, muted `#95A0B3`, steel `#8B95A7`
- Text on chrome: `#F4F6FA` / muted on chrome: `#9DA9BF`

**Fonts are already local.** The project's own self-hosted woff2 subsets are copied to
`composition/assets/fonts/` - declare `@font-face` against them, no network fonts:

- Display: **Barlow Condensed** - `barlow-condensed-italic-{600,700,800}-latin.woff2`,
  `barlow-condensed-normal-{500,600,700,800}-latin.woff2`. Italic is the project's "speed" read for
  live states; use it for the hook line, section headers and the wordmark.
- Body: **Inter** - `inter-normal-{400,500,600,700}-latin.woff2`
- Mono: **JetBrains Mono** - `jetbrains-mono-normal-{400,500,700,800}-latin.woff2`. Every numeral,
  stage chip, model name and stat uses this with `font-feature-settings: "tnum" 1`.

Visual references from the project:

- **Scorebug / lower-third chrome**: a `#0E1B33` plate with a 4px gold `#F2A900` tick on the left
  edge and a clipped top-right or bottom-right corner (the telecast angle). Labels above values,
  uppercase, letter-spaced, `#9DA9BF`; values large in mono ink.
- **Probability bars**: flat, square, 10px tall, track `#0E1B33`, fill `#F2A900` for the winning
  class and `#8B95A7` for the rest. No rounded corners, no gradients, no glow.
- **Data panels**: `#101D38` background, `#26365C` 1px edge, `#1C2A4A` row rules. Restraint in the
  cells, energy in the frame - that is the project's governing design rule.

## Storyboard

`brag-output/brag-plan.md` is the creative contract - follow its scene text, order and audio notes.

Scene summary:

1. **HOOK: live, mid-at-bat** - 4.30s (0.00-4.30) - scorebug slides in, count tile `0-1` drops,
   then the line *The pitch hasn't been thrown yet.* settles at 2.40s and holds 2.0s.
2. **THE PREDICTION (both panels)** - 7.00s (4.30-11.30) - the outcome panel's five rows sweep out
   from 4.85s (0.28s apart); the **Ball** bar completes gold on the 6.56s beat; its caption lands at
   6.90s. The pitch-type panel arrives at 7.18s and its seven ranked rows land 7.60-8.46s with **no
   highlighting of any kind**; its caption lands on the 8.74s strong cue. Both sets hold side by
   side to the cut.
3. **THE WRAPPER** - 4.30s (11.30-15.60) - headline settles 11.50s; four chrome chips land on
   11.46 / 12.55 / 13.64 / 14.73 with a gold rule drawing between them.
4. **HELD BACK** - 4.20s (15.60-19.80) - registry row arrives; mono counter runs to `96.8 min` /
   `0 interventions` by 17.30s; the line settles 17.60s; STAGE flips to `CANDIDATE` and the
   **NOT PROMOTED** stamp lands on the 18.56s strong cue.
5. **LOCKUP** - 5.20s (19.80-25.00) - ledger strip 19.95-21.95s; the wordmark arrives 22.15s; the
   two-line tagline settles on the 22.93s strong cue; `thebullpen.net` under it; a gold rule sweeps
   the base and stops. Music fades over the last 1.2s.

Readability floors are already budgeted in the plan - every line has its settled hold. Do not
shorten a hold to fit a beat.

## Audio

- Audio role: **sparse professional accents over a steady clean bed**
- Audio arc: the bed runs unchanged for 24s as a floor, never foreground. Two small physical accents
  place the broadcast chrome in Scene 1, one warmer accent marks the gold winning bar in Scene 2,
  **Scene 2's caption is deliberately unscored**, thinned mechanical accents build the chain and the
  count-up in Scenes 3-4, one dry impact lands the stamp at the track's strongest cue, and one deep
  soft bell arrives on the lockup before the music fades out alone.
- Music: `assets/music/happy-beats-business-moves-vol-12-by-ende-dot-app.mp3` (already copied in)
- Music treatment: start at 0.0, volume **0.30**, brief fade-in over the first ~0.5s, fade out
  across the final 1.2s under the lockup. Never ducks the numbers.
- Music cue guidance: bundled preset copied to
  `assets/music/cues/happy-beats-business-moves-vol-12-by-ende-dot-app.music-cues.json` -
  117.36s, **109.96 BPM**. Three cue locks only:
  - **6.56s** (beat) - the winning outcome bar completes gold
  - **8.74s** (strongCue 0.99) - the pitch-type honesty caption lands. The strong beat goes to the
    caption precisely because [183] forbids landing it on a highlighted row.
  - **18.56s** (strongCue 0.99) - the `NOT PROMOTED` stamp lands
  - **22.93s** (strongCue 1.00) - the lockup tagline settles

  Beat-grid window: Scene 3's four wrapper chips on **11.46 / 12.55 / 13.64 / 14.73** (every other
  beat, ~1.1s apart - these carry multi-word labels and must not go faster). Scene 2's rows are
  staggered off the grid for rhythm only (0.28s left panel, 0.14s right panel), with each full set
  held 2s+ afterwards for reading.
- Audio-reactive treatment: **subtle**. Use RMS/low-band energy on at most two targets: the field
  vignette depth, and a soft presence lift on the gold lockup at 22.37s. No waveform bars, no
  equalizers, no particle systems, no text scaling, no strobing. If extraction is unavailable,
  document it and continue - do not block the render.
- Audio-coupled moments:
  - Scene 1, 0.2s - scorebug slide-in - soft placement drop
  - Scene 1, ~1.0s - count tile landing - light plate accent
  - Scene 2, 4.90s - first probability row arriving - one soft drop (not all five)
  - Scene 2, 6.56s - gold winner bar completing - one warmer accent, beat-locked
  - Scene 2, 7.16s - the pitch-type panel arriving - one soft drop
  - Scene 2, 6.90s and 8.74s - **both** honesty captions - **no sound, on purpose**
  - Scene 3, 9.83s and 13.11s - chips 1 and 4 only; chips 2 and 3 silent
  - Scene 4, 14.6-16.2s - mono count-up - thinned stacking ticks, not per-digit
  - Scene 4, 17.47s - `NOT PROMOTED` stamp - one dry medium impact, beat-locked
  - Scene 5, ~21.6s - wordmark arrival - one deep soft bell, then let the bed carry out
- SFX selection guidance: match the gesture. Chrome plates sliding and landing want soft drops and
  light plate accents; the stamp wants a dry medium soft impact with no ring; the lockup wants a
  single deep, brief bell. Total 6-8 cues across 24 seconds at **0.55-0.70** volume. When in doubt,
  leave it silent - this tone is carried by the bed.
- SFX analysis guidance: read
  `/Users/alexpicard/.claude/plugins/cache/brag/brag/0.2.2/skills/brag/assets/sfx/sfx-analysis.md`
  and prefer **low high-frequency-risk** files throughout; this is a polished tone with repeated
  accents, so nothing sharp or tinny.
- Exact SFX choice: Hyperframes selects filenames, timestamps, density and volume after the visual
  animation exists.
- Audio files: music and its cue preset are already in `composition/assets/music/`. Copy any chosen
  SFX into `composition/assets/sfx/<family>/`. Never use absolute paths in the composition HTML.

## Hyperframes Instructions

Load the composition-building Hyperframes domain skills - `hyperframes-core` (composition contract +
`data-*` timing), `hyperframes-animation` (motion), `hyperframes-creative` (design spec, beats,
audio-reactive), `hyperframes-keyframes` (seek-safe keyframes), and `hyperframes-cli` (lint / check /
render). `/brag` is its own workflow: do not enter the `hyperframes` entry-point intent interview and
do not route into its generic promo / launch-video workflow. Prefer native Hyperframes conventions
over anything in `/brag`.

Requirements:

- Show at least one real UI element from the source project - **Scenes 1, 2 and 4 are all
  recreations of real surfaces, and Scene 2 carries two of them**, and the palette, fonts and component structure must match the
  cited files.
- Keep all text readable in the final render. Every settled hold in the plan is a floor, not a
  target.
- Keep the video within 15-25 seconds (target 25.0s).
- Include the planned music and SFX layer.
- Treat `/brag` audio notes as guidance, not a fixed cue sheet. Choose SFX after the animation exists.
- Treat cue metadata as optional timing hints: major reveals within ~0.15s of a strong cue, smaller
  entrances within ~0.10s of a beat. Only the three cue locks above are required; ignore any cue that
  hurts readability or pacing.
- Honor the music fade-out under the lockup and the deliberate silence under Scene 2's caption.
- Use local assets only - fonts, music and SFX are all in `composition/assets/`.
- Run `npx hyperframes check` before render; it is brag's single gate.
