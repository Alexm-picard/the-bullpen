---
name: Retro notebook theme applied to authenticated app
description: Design tokens, fonts, and CSS utility classes used to style the authenticated app in the retro notebook aesthetic matching Landing.tsx
type: project
---

The authenticated app (Layout + all pages) was restyled to match the Landing page's retro "notebook" aesthetic.

**Key design tokens:**
- Cream background: `#faf9f0` (notebook-bg with blue rule lines + red margin line)
- Ink text: `#1c1410` (primary), `#3d2c22` (secondary/inkLight)
- Navy accent: `#1a3a5c` (replaces violet primary)
- Graph paper cards: `#f8f7ee` bg + `rgba(99,102,241,0.12)` 24px grid lines
- Sidebar bg: `#f5f3e8`, border: `#d4cdb8`
- Sticky yellow: `#fef08a` (upload buttons, user chat bubbles, "New Chat")
- Chalkboard green: `#1e3320` (flashcard CTA section)

**Fonts:** Kalam (headings, `.retro-heading`) + Patrick Hand (body/labels, `.retro-label`)
Both loaded via Google Fonts in `index.html` and via a `useEffect` in Layout.

**Global CSS utilities added to `index.css`:**
- `.notebook-bg` — lined paper background
- `.graph-bg` / `.retro-card` — graph paper card style
- `.sticky-note` — sticky yellow note style
- `.retro-heading` / `.retro-label` — font classes
- Keyframes: `wiggle`, `peel`, `float-slow`, `chalk-draw`
- `.sticky-wiggle:hover`, `.sticky-peel:hover`, `.card-float`

**Why:** User requested Landing page's retro style applied to entire authenticated app.

**How to apply:** When adding new components to the authenticated app, use `retro-card`/`retro-heading`/`retro-label` CSS classes, navy `#1a3a5c` for primary interactive color, and sticky yellow `#fef08a` for primary action buttons.
