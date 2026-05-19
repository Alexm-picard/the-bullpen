# ADR-0004: Use Mantine for components + Tailwind for layout, not pure Tailwind

- **Status**: Accepted
- **Date**: 2026-05-19
- **Deciders**: alex
- **Related**: `decisions.md` entries [93] [95] [109], `plan.md` Phase 4a, `design.md` §7 §10

## Context

The frontend is a pure SPA (React 18 + TypeScript + Vite) serving five pages:
Player Lookup, Park Explorer, Game/Live, Ops Dashboard, About. Three of those
pages (Player, Park, Ops) are **data-dense** — tables, segmented controls,
sliders, charts, multi-axis filters. About is editorial. Game/Live is a
streaming feed.

The data-dense pages need a substantial set of pre-built form / table /
overlay primitives: data tables with sorting, dropdowns with search,
debounced sliders, modal dialogs, tooltips. Building these from scratch on
top of Tailwind utilities is a real time investment (months) and the result
tends to drift visually as one-off styling decisions pile up.

The candidates were:

- **Pure Tailwind + shadcn/ui** — Tailwind for everything, copy-paste shadcn
  primitives as needed. This is what StudyForesight uses.
- **Mantine + Tailwind** — Mantine for prebuilt components (forms, tables,
  overlays, hooks), Tailwind for layout, spacing utility classes, and
  one-off ad-hoc styling.
- **MUI / Chakra / Radix-only** — other prebuilt component libraries.

The locked design identity (decisions [100]–[112]) is "editorial-data" —
Observable / Pudding / The Athletic — with three custom fonts and a tight
color palette. Whatever component library we pick has to be themeable enough
to honor those tokens without fighting the framework.

## Decision

We use **Mantine 7+ for components** (DataTable, Slider, Combobox, Modal,
Notifications, the hooks library) and **Tailwind for layout** (flex/grid
containers, spacing utilities, one-off `text-*` / `bg-*` utility classes
when they're driven by Tailwind-managed tokens).

Mantine's theme object holds the editorial-data palette, type scale, and
spacing scale as the canonical source of truth. Tailwind's `tailwind.config.js`
mirrors the same tokens so the two systems agree.

**No hex codes in component files** — discipline rule from `plan.md`
(rule 1 of the discipline rules) and CLAUDE.md's hard "never" list. Component
authors reach for `theme.colors.brick[5]` or `bg-brick-500`, never `#B53D2C`
inline.

## Consequences

**Easier:**

- The data-dense pages (Player, Park, Ops) get production-quality form and
  table primitives out of the box. Park Explorer in particular benefits —
  the 30-stadium HR-probability heatmap leans heavily on Mantine's
  RangeSlider, Tooltip, and Combobox.
- Mantine's hooks library (`use-debounced-value`, `use-hotkeys`,
  `use-resize-observer`) covers ergonomic plumbing we'd otherwise hand-roll.
- Tailwind still owns layout, which is where utility-first really shines.
  Side-by-side layouts, responsive grid, spacing scales — all of it.
- Different paradigm from StudyForesight's pure-Tailwind/shadcn world.
  Differentiation goal of the project.

**Harder:**

- Two theming systems must agree. Mantine theme tokens and Tailwind config
  must define the same palette, the same type scale, the same spacing
  scale. Drift between them is the dominant defect class to watch for.
- Mantine ships with its own CSS reset and base styles. We import Mantine's
  styles _before_ Tailwind's preflight so Tailwind wins where they
  overlap.
- Bundle size: Mantine adds ~80kB gzipped over pure Tailwind. Phase 4 exit
  criterion (bundle < 300kB gzipped initial) gives headroom but is no
  longer trivially achievable — code-splitting per route becomes important.

**New failure modes:**

- A developer reaches for a hex code "just this once" in a one-off chart
  color. The `frontend-reviewer` agent flags this in PR review; the
  discipline rule is non-negotiable.
- Mantine v7→v8 breaking changes (the library churns yearly). Pinning
  Mantine to one major version per phase and bumping intentionally is the
  mitigation.

**Locked into:**

- Mantine's component vocabulary defines the project's interaction
  language. If we want a behavior Mantine doesn't ship (e.g., a custom
  combobox with virtualized rows), we extend Mantine, we don't pull in a
  second component library.

## Alternatives Considered

### Alternative A: Pure Tailwind + shadcn/ui (StudyForesight-style)

- Tailwind for utilities, copy-paste shadcn primitives for components.
- Rejected: shadcn primitives are excellent but more work for the
  data-dense surfaces — DataTable, Combobox-with-search, RangeSlider,
  Notifications all become substantial builds. Time we'd rather spend on
  the models and the registry. Also fails the "different paradigm from
  StudyForesight" differentiation goal.

### Alternative B: MUI (Material UI)

- Heavyweight component library with a strong opinion.
- Rejected: MUI's visual identity is "Material Design" — fighting it to
  produce the editorial-data look would be more work than the components
  save. Bundle size is also worse than Mantine.

### Alternative C: Radix Primitives + Tailwind

- Unstyled Radix primitives for behavior, Tailwind for everything visual.
- Rejected: same time-cost problem as pure Tailwind/shadcn — Radix gives
  you a11y-correct behavior but you still hand-build every visual layer.
  Phase 4's ~70–90h estimate doesn't fit hand-rolling that many primitives
  on top of Radix.

### Alternative D: Chakra UI

- Themable component library, lighter than MUI.
- Rejected: smaller component vocabulary than Mantine for the specific
  primitives we need (DataTable, Combobox, RangeSlider). Mantine wins on
  the data-dense surface area.

## Revision History

(none)
