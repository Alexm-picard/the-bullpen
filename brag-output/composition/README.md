# brag.mp4 - HyperFrames composition source

The 23s launch video at [`../brag.mp4`](../brag.mp4), rendered from `index.html` by
[HyperFrames](https://hyperframes.heygen.com). Written by the `/brag` skill on 2026-10-11 against
the [195] editorial "Front Page" identity; the plan and the handoff brief are
[`../brag-plan.md`](../brag-plan.md) and [`../composition-brief.md`](../composition-brief.md).
It replaces the September broadcast-era cut, which is in git history.

The composition rebuilds real surfaces in HTML rather than inventing marketing visuals:

| Scene | Source in this repo |
| --- | --- |
| 1 - "going to press": dateline, composed headline, three-column slate | `frontend/src/pages/games-page.tsx`, `components/games/scoreboard.tsx` |
| 2 - "The next pitch, by outcome" + calibration caption | `frontend/src/components/games/outcome-agate.tsx`, `data/game-claims.ts` |
| 3 - margin pull quote | the game page's "Model gave it" margin |
| 4 - "The systems register" Serving section + the human gate | `frontend/src/pages/ops-page.tsx`, `data/ops-claims.ts` |
| 5 - Night edition tagline + footer | the README tagline and the site footer |

Palette and type are mirrored from `frontend/src/design/editorial.css` (paper `#eceee6`, night
`#141612`; Source Serif 4 / Inter / JetBrains Mono).

**What is illustrative.** The capture night had no game in progress, so the slate's teams,
scores and times and the five next-pitch probabilities are example values set in the real
components' wording. Every model name, stage, p99 figure, count, caption and the 96.8-minute
retrain (decision [178]) is real. Keep it that way if you edit this: no invented model
performance figures ([202]).

## Media assets are not committed

`assets/` is gitignored, so a fresh clone will not re-render as-is. Restore it with:

```bash
# fonts - byte-identical copies of the ones this site already serves
mkdir -p assets/fonts && cp ../../frontend/public/fonts/{inter-normal-{400,500,600},jetbrains-mono-normal-{400,500,700},source-serif-4-italic-400,source-serif-4-normal-var}-latin.woff2 assets/fonts/

# GSAP, pinned
mkdir -p assets/vendor && curl -sL https://cdn.jsdelivr.net/npm/gsap@3.12.5/dist/gsap.min.js -o assets/vendor/gsap.min.js

# music + SFX - from the /brag skill's bundled assets (resolve <skill-dir> locally; the music
# track's license terms are unverified upstream, which is why this repo does not redistribute
# the .mp3). The SFX are CC0 (Kenney.nl).
mkdir -p assets/music assets/sfx/impact assets/sfx/interface assets/sfx/ui
cp <skill-dir>/assets/music/happy-beats-business-moves-vol-12-by-ende-dot-app.mp3 assets/music/
cp <skill-dir>/assets/sfx/impact/impactSoft_medium_00{1,3}.ogg assets/sfx/impact/
cp <skill-dir>/assets/sfx/interface/{drop_001,bong_001}.ogg assets/sfx/interface/
cp <skill-dir>/assets/sfx/ui/click2.ogg assets/sfx/ui/
```

The per-frame audio-reactive data is inlined in `index.html`, so no extraction step is needed.

## Rebuild

```bash
npx hyperframes check                        # the gate: lint + runtime + layout + WCAG AA
npx hyperframes preview --background         # Studio timeline
npx hyperframes render --quality looks --output ../brag.mp4
```

`check` must report 0 errors. The `nested_structure_needs_subcomposition` and file-size
warnings are expected and non-gating: this is a single-file composition.
