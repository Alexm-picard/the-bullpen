# brag.mp4 - HyperFrames composition source

The 25s launch video at [`../brag.mp4`](../brag.mp4), rendered from `index.html` by
[HyperFrames](https://hyperframes.heygen.com). Written by the `/brag` skill against this
repo's own design tokens and components; the plan and the handoff brief are
[`../brag-plan.md`](../brag-plan.md) and [`../composition-brief.md`](../composition-brief.md).

The composition recreates real surfaces rather than inventing marketing visuals:

| Scene | Source in this repo |
| --- | --- |
| 1 - scorebug + count tile | the `/games/:id` live header |
| 2 - next-pitch panel | `frontend/src/components/games/next-pitch-panel.tsx` |
| 2 - pitch-type prior | `frontend/src/components/games/pitch-type-panel.tsx` |
| 4 - registry row | `frontend/src/components/ops/model-fleet-table.tsx` |

Palette and type are mirrored from `frontend/src/design/tokens.css` (dark theme) and
`frontend/src/design/broadcast.ts`.

**Decision [183] holds inside the video too.** The pitch-type prior's rows are styled
identically to one another - no gold bar, no bold label, no distinct colour on the top
class - because top-1 there is ~0.45 and a highlighted row would be wrong more often than
right while carrying the visual authority of an answer. The next-pitch outcome head, whose
argmax *is* a meaningful read, is the only highlighted row in the whole 25 seconds. Keep it
that way if you edit this.

## Media assets are not committed

`assets/` is gitignored, so a fresh clone will not re-render as-is. Restore it with:

```bash
# fonts - byte-identical copies of the ones this site already serves
mkdir -p assets/fonts && cp ../../frontend/public/fonts/*-latin.woff2 assets/fonts/

# music + SFX - from the /brag skill's bundled assets (resolve <skill-dir> locally;
# the music track's license terms are unverified upstream, which is why this repo
# does not redistribute the .mp3). The SFX are CC0 (Kenney.nl).
mkdir -p assets/music assets/sfx/impact assets/sfx/interface assets/sfx/casino
cp <skill-dir>/assets/music/happy-beats-business-moves-vol-12-by-ende-dot-app.mp3 assets/music/
cp <skill-dir>/assets/sfx/impact/impactSoft_medium_00{0,1,2,3,4}.ogg assets/sfx/impact/
cp <skill-dir>/assets/sfx/interface/{drop_001,drop_002,bong_001}.ogg assets/sfx/interface/
cp <skill-dir>/assets/sfx/casino/chip-lay-1.ogg assets/sfx/casino/
```

The per-frame audio-reactive data is already inlined in `index.html`, so no extraction step
is needed. Without `assets/` the composition still lints and renders - silently, with
fallback fonts.

## Rebuild

```bash
npx hyperframes check                        # the gate: lint + runtime + layout + WCAG AA
npx hyperframes preview --background         # Studio timeline
npx hyperframes render --quality looks --output ../brag.mp4
```

`check` must report 0 errors. The five `nested_structure_needs_subcomposition` warnings are
expected and non-gating: this is a single-file composition, which Studio's timeline would
rather see split into per-scene sub-composition files.
