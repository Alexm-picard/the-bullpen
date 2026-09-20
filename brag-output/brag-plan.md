# Brag Plan: The Bullpen

## What is this app?

[thebullpen.net](https://thebullpen.net) is a self-hosted baseball analytics platform where the
real product is the ML systems wrapper - a model registry, shadow A/B router, drift detection and
a retraining queue, all written from scratch in Java - operating four calibrated models in
production on a single desktop behind a Cloudflare Tunnel. Its README says the quiet part out
loud: **"The wrapper is the project; the models are the excuse."**

## The angle

Most ML portfolio videos brag about accuracy. This one brags about **restraint**.

The hook is a live pitch being scored before it is thrown. The payoff is what sits underneath those
predictions - "calibrated estimate, not an accuracy claim" / "calibrated prior, not a top-1
prediction" - and the beat where an unattended retrain finishes in 96.8 minutes with zero
interventions and is then **deliberately not promoted**, because promotion stays human-gated
(rule 6). The video's argument: the impressive thing here is the discipline around the models, not
the models.

Specific to this project because every line is lifted from the product's own surfaces - the
scorebug, the two pitch panels and their honesty captions, the Ops model-fleet registry, the
decision ledger. None of it is transferable to any other project.

## Hook (first 2-3 seconds)

The broadcast scorebug snaps in on a near-black field - `MIL 3 · STL 3 · INN 6 ●LIVE` - and the
mono count tile punches up huge: `0-1`. Then one line in condensed italic:

> **The pitch hasn't been thrown yet.**

Motion and typography are pure telecast. The viewer is inside a live game in under two seconds,
and the line promises something is about to be predicted.

## Key moments (the middle)

- **Both live pitch panels fill in, side by side, exactly as the game page pairs them.**
  - *Left, `pitch_outcome_pre` v2*: five outcome bars sweep out one by one - Ball, Called strike,
    Swinging strike, Foul, In play - and the winning class turns gold. Caption: *calibrated
    estimate, not an accuracy claim.*
  - *Right, `pitch_type_pre` v1*: seven pitch-type rows arrive ranked by probability and **nothing
    is highlighted**. Caption: *calibrated prior, not a top-1 prediction.*
  - The contrast between the two panels is the scene. Decision [183] forbids any argmax emphasis on
    the pitch-type prior, because top-1 there is ~0.45 and a gold row would be wrong more often
    than right while carrying all the visual authority of an answer. The outcome head earns its
    gold row; the prior is not allowed one. That difference, shown rather than stated, is the most
    sophisticated thing the product does.
- **The wrapper chain lights up.** Four chrome chips connected by a gold rule -
  `REGISTRY → SHADOW A/B ROUTER → DRIFT → RETRAIN QUEUE` - arriving one at a time under the line
  "Written from scratch in Java. No MLflow."
- **The model that was held back.** An ops registry row for `battedball_outcome v3`, a mono
  count-up to `96.8 min / 0 interventions`, and then the stage chip snapping to `CANDIDATE` with a
  gold-outlined **NOT PROMOTED** stamp. "Promotion stays human-gated."

## Outro / punchline

A single mono ledger strip - `p99 34 ms · 300 req/s · 2,400+ tests · 193 decisions · 6 postmortems`
- then the lockup: the gold tick, THE BULLPEN, and the README's own line as the tagline.

> The wrapper is the project;
> *the models are the excuse.*

## User flow worth showing

Three beats of the working app, in order:

1. **Entry** - open a live game at `/games/:id`; the scorebug and count tile carry live state from
   the MLB Stats API poller.
2. **Key action** - mid-at-bat, both pre-pitch heads fire
   (`POST /v1/predict/pitch?head=pre` and the pitch-type prior endpoint) and the two panels render
   their calibrated distributions with their honesty captions.
3. **Result** - the Ops desk at `/ops` shows the same fleet from the registry: stages, drift, and
   the retrain candidate sitting there unpromoted.

Scenes 2 and 4 are recreations of real surfaces (`next-pitch-panel.tsx`, `pitch-type-panel.tsx`,
`model-fleet-table.tsx`), not marketing abstractions.

## Tone

- Preset: **polished**
- Creative direction: *a broadcast engineering film - telecast chrome, analytical restraint, the
  honesty is the flex*
- Interpretation: five scenes, long settled holds, soft crossfades. Motion is snappy on entry and
  then stops moving. Nothing strobes, nothing shouts. The claims are all real and dated, so the
  video never needs to oversell - the restraint is the brand. Type is condensed italic display for
  voice, mono for every number.

## Format: landscape - 1920x1080
## Duration: 25.0s

## Visual identity (from the project)

Pulled from `frontend/src/design/tokens.css` and `broadcast.ts` (dark theme, the committed default):

- Background (field): `#080F1F`
- Elevated field / chrome: `#0E1B33`
- Panel: `#101D38`, panel edge `#26365C`, rule `#1C2A4A`
- Accent (gold): `#F2A900`, deep gold `#C98D00`
- Live green: `#39D98A`
- Text: ink `#F4F6FA`, body `#C9D1E0`, muted `#95A0B3`, steel `#8B95A7`
- Display font: **Barlow Condensed** (600-800, italic is the "speed" read for live states)
- Body font: **Inter** (tabular figures)
- Mono font: **JetBrains Mono** (all numerals, scorebug, stat tiles)
- Strongest visual element: the **scorebug + lower-third chrome** (gold left tick, angled clipped
  corner, chrome `#0E1B33` plate on the dark field) and the **gold probability bar** of the
  next-pitch panel - the one highlighted row in the whole video.

The composition loads the project's **own self-hosted woff2 subsets** from `frontend/public/fonts/`,
so the video is set in the same typefaces the site serves.

## Share copy (draft)

The Bullpen is a self-hosted baseball analytics platform where the real project is the ML systems
wrapper: model registry, shadow A/B router, drift detection and a retraining queue, all hand-written
in Java around four calibrated models. It scores the next pitch before it's thrown and tells you
that's a calibrated estimate, not an accuracy claim.

## Audio direction

- Role: **sparse professional accents over a steady clean bed**
- Music: `happy-beats-business-moves-vol-12-by-ende-dot-app.mp3` (steady and clean, the
  polished/cinematic pick)
- Music treatment: starts at 0.0 on a `data-automation` volume lane - fade in to 0.30 over the
  first 0.5s, hold, then fade to 0 across the last 1.2s under the lockup. Never competes with the
  on-screen numbers. Measured in the render: mean -26.8 dB, peak -3.9 dB.
- Music cue guidance: bundled preset at
  `assets/music/cues/happy-beats-business-moves-vol-12-by-ende-dot-app.music-cues.json` -
  109.96 BPM. Three strong-cue locks plus one plain-beat lock:
  - **6.56s** (beat) - the winning outcome bar turns gold
  - **8.74s** (strongCue 0.99) - the pitch-type honesty caption lands. The strong beat goes to the
    caption *because* [183] forbids landing it on a highlighted row.
  - **18.56s** (strongCue 0.99) - the **NOT PROMOTED** stamp lands
  - **22.93s** (strongCue 1.00) - the lockup tagline settles

  Beat-grid window: Scene 3's four wrapper chips on **11.46 / 12.55 / 13.64 / 14.73** (every other
  beat, ~1.1s apart - these carry multi-word labels and must not go faster). Scene 2's rows are
  staggered off-grid for rhythm (0.28s on the left panel, 0.14s on the right), with each full set
  held for 2s+ afterwards for reading.
- Audio-reactive treatment: **subtle**. Pre-extracted per-frame RMS and low-band energy (750 frames,
  inlined - no runtime analysis, no fetch) drive exactly two things: the field glow's depth and
  scale, and a soft gold presence lift behind the lockup. No waveform bars, no equalizers, no
  particle systems, no text scaling.
- SFX posture: **sparse, motion-matched, polished restraint** - 11 cues across 25 seconds, all warm
  low-high-frequency-risk files at 0.32-0.68 (`impactSoft_medium_*`, `interface/drop_*`,
  `casino/chip-lay-1`, `interface/bong_001`).
- Restraint rule: no sound on every element. **Neither honesty caption in Scene 2 is scored** - the
  two most important lines in the video land in near-silence on purpose.

## Storyboard

### Scene 1 - HOOK: live, mid-at-bat - 4.30s (0.00 -> 4.30, clip holds to 4.65)

Near-black field `#080F1F` with a faint vignette and a constant 4px gold rule across the top of the
frame. The broadcast scorebug slides in from the left at 0.20s: gold left tick, chrome `#0E1B33`
plate with a clipped corner, `MIL 3 · STL 3` then a divider then `INN 6` and a `#39D98A` LIVE dot
that pulses (finite, seek-safe). At 0.95s a chrome panel drops under it carrying the count tile in
JetBrains Mono at 92px: `COUNT / OUTS / LAST PITCH / PITCHER PITCHES` over `0-1`, `1`, `FC · 95.0`,
and `79` in gold.

At 2.05s the hook line settles in Barlow Condensed italic and holds 1.85s to the cut:

> **The pitch hasn't been thrown yet.**

Sequential/interaction: yes - scorebug, then count tile, then hook line. Three arrivals, ~1s apart.
Audio intent: calm live-broadcast room tone; the bed starts, two small physical accents place the
chrome.
Audio-coupled idea: soft drop on the scorebug slide (0.20s), light plate accent on the count tile
landing (0.98s). Nothing on the hook line.
Transition mood: soft crossfade (0.35s) -> Scene 2

### Scene 2 - THE PREDICTION: both live pitch panels - 7.00s (4.30 -> 11.30, clip holds to 11.65)

The game page's two pre-pitch panels, recreated side by side in a reserved 1600px two-column frame.

**Left (900px) - `pitch_outcome_pre` v2.** Lower-third header plate: `NEXT PITCH` in condensed
italic with a `PRE-PITCH HEAD` mono tag. Below it a `#101D38` panel of five rows in the component's
canonical class order, each a 3-column grid (Inter label / flat bar track / right-aligned mono
percentage):

| Label | Bar | Value |
|---|---|---|
| Ball | **gold `#F2A900`** | **34.2%** |
| Called strike | steel `#8B95A7` | 18.6% |
| Swinging strike | steel | 11.9% |
| Foul | steel | 21.4% |
| In play | steel | 13.9% |

Rows sweep out one by one from 4.85s at 0.28s apart. The **Ball** row resolves gold on the 6.56s
beat - the only highlighted row in the entire video. Caption at 6.90s:

> `pitch_outcome_pre v2`
> Calibrated estimate - **not an accuracy claim.**

**Right (656px) - `pitch_type_pre` v1.** Header plate: `PITCH TYPE` with a `CALIBRATED PRIOR` tag.
Panel arrives at 7.18s; seven rows land ranked by probability from 7.60s at 0.14s apart - a quick
set, then held 2.4s to read:

| Label | Bar | Value |
|---|---|---|
| Four-seam | steel | 31.8% |
| Slider | steel | 20.4% |
| Sinker | steel | 15.1% |
| Changeup | steel | 12.6% |
| Curveball | steel | 9.7% |
| Cutter | steel | 6.9% |
| Other | steel | 3.5% |

**Every row is styled identically. Nothing turns gold, ever.** That is decision [183] rendered in
motion: the panel's own source says top-1 here is ~0.45 because pitch selection is high-entropy, so
a highlighted row "would be wrong more often than right while carrying all the visual authority of
an answer". Rank order is the only signal the panel is allowed to send. Caption on the 8.74s strong
cue:

> `pitch_type_pre v1`
> Calibrated prior - **not a top-1 prediction.**

Both captions then hold side by side to the cut. Reading the two panels against each other is the
whole scene: one model's argmax is a meaningful read and is dressed as one; the other's is not, and
is deliberately left undressed.

Sequential/interaction: yes - five outcome rows one by one, then seven prior rows as a quick ranked
set, each set held well past its reading floor.
Audio intent: quiet, analytical, precise. The bars feel like instrumentation, not fanfare. Both
captions land in near-silence - that is the point of the scene.
Audio-coupled idea: one soft drop on the first outcome row (4.83s), a warmer accent when the gold
winner bar completes (6.48s), one soft drop as the prior panel arrives (7.16s). **No SFX on either
caption.**
Transition mood: soft crossfade (0.35s) -> Scene 3

### Scene 3 - THE WRAPPER - 4.30s (11.30 -> 15.60, clip holds to 15.95)

The headline settles at 11.50s, centred, condensed italic, and stays for the whole scene:

> **Written from scratch in Java.** *No MLflow.* (second sentence in gold)

Beneath it, four chrome chips land on a drawing gold rule, each on its beat - **11.46 / 12.55 /
13.64 / 14.73** - gold tick on each left edge, mono uppercase labels:

`REGISTRY` -> `SHADOW A/B ROUTER` -> `DRIFT` -> `RETRAIN QUEUE`

Each chip that lands stays lit; the connecting rule segment draws into the next chip. The complete
chain holds at full brightness before the cut.

Sequential/interaction: yes - four chips, ~1.1s apart (every other beat), with the gold rule drawing
between them. Deliberately slower than Scene 2's stagger because these carry multi-word labels.
Audio intent: mechanical assembly, understated. Something being built, not celebrated.
Audio-coupled idea: a light accent on chip 1 (11.44s) and a firmer one on chip 4 (14.71s) as the
chain completes. Chips 2 and 3 are silent - the rhythm is implied, not scored.
Transition mood: soft crossfade (0.35s) -> Scene 4

### Scene 4 - HELD BACK - 4.20s (15.60 -> 19.80, clip holds to 20.15)

A single Ops registry row, recreated from the model-fleet table: `battedball_outcome v3` on a
`#101D38` panel with a `#26365C` edge, columns for MODEL / STAGE / REGISTERED and an empty fourth
column reserved for the stamp.

From 16.05s a mono readout counts up and settles by 17.30s:

> `96.8 MIN` &nbsp; `0` INTERVENTIONS

At 17.60s the line settles under it and holds 1.8s:

> **Promotion stays human-gated.**

Then on the 18.56s strong cue the STAGE cell snaps from `PENDING` to gold `CANDIDATE`, and a
gold-outlined stamp lands rotated across the reserved column:

> **NOT PROMOTED**

It holds to the cut. This is the emotional centre of the video: the retrain worked, unattended, and
was still not shipped.

Sequential/interaction: yes - registry row, mono counter ticking to its value, stage flip, stamp.
Audio intent: procedural, then one dry, final punctuation. The stamp should feel like a decision
made by a person, not an event that happened to a system.
Audio-coupled idea: two thinned stacking ticks under the count-up (16.10s, 16.95s), then one dry
medium soft impact exactly on the stamp (18.46s).
Transition mood: soft crossfade (0.35s) -> Scene 5

### Scene 5 - LOCKUP - 5.20s (19.80 -> 25.00)

Two beats in one movement.

**Ledger strip (19.95 -> 21.95s).** A single centred mono line in muted text with gold middots:

> `p99 34 ms · 300 req/s · 2,400+ tests · 193 decisions · 6 postmortems`

**Lockup (22.08 -> 25.00s).** The strip clears; the gold tick and wordmark scale up gently to
centre - **THE BULLPEN** in Barlow Condensed italic at 152px. A gold rule sweeps across beneath it
and stops. The tagline settles as two lines with clear hierarchy, the second on the 22.93s strong
cue (the track's single strongest beat in the window):

> **The wrapper is the project;**
> *the models are the excuse.* (gold `#F2A900`)

Under it, small and muted: `thebullpen.net`. Everything holds still to the end; the bed fades out
across the last 1.2s.

Sequential/interaction: yes - stat strip, then it clears for the wordmark, then the rule, then the
two tagline lines, then the URL. No overlap between strip and lockup.
Audio intent: arrival and settle. One warm, deep, brief payoff - then let the music carry out alone.
Audio-coupled idea: a soft deep bell on the wordmark's arrival (22.12s). Nothing on the tagline or
the URL.
Transition mood: hold to black.

**Music mood for this video:** steady and clean - restrained corporate bed, used as floor not
foreground.
**Audio summary:** A low steady bed runs the whole 25 seconds with eleven sparse motion-matched
accents on top - two placing the broadcast chrome, three through the pitch panels, two building the
wrapper chain, two thinned ticks under the retrain counter, one dry impact on the NOT PROMOTED stamp
at a strong cue, and one deep soft bell on the lockup - with both honesty captions deliberately
unscored so the two most important lines in the video land in near-silence.

## Honesty note on the on-screen numbers

Every **claim** in the video is real and sourced, and none was rounded or embellished:
`p99 34 ms`, `300 req/s`, `2,400+ tests`, `193 decisions`, `6 postmortems`, `96.8 min`,
`0 interventions`, the model names and versions, and the stage `CANDIDATE`. The two probability
distributions in Scene 2 (and the scorebug's game state) are **representative example payloads** -
plausible renderings of what one live pitch looks like through each panel, not a specific logged
prediction. The panel structure, class vocabulary, class ordering, bar treatment and both captions
are verbatim from the components.
