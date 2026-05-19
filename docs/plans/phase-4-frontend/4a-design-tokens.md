# 4a — Design tokens (Mantine + Tailwind)

> Owning phase: `phase-4-frontend` · Estimated effort: `6–8 h` · Authored: 2026-05-10

---

## Scope boundaries

**IN scope**:
- `src/design/tokens.ts` — single source of truth for colors, type, spacing, motion, layouts
- Tailwind config consumes `tokens.ts` (deduplicated; do not duplicate values in two places)
- Mantine theme consumes the same tokens
- `@font-face` + `<link rel="preload">` for Source Serif 4, Inter, JetBrains Mono
- A `pnpm lint:hex-codes` script that fails CI if any non-allowlisted file contains `#[0-9A-Fa-f]{3,8}`
- Storybook NOT installed (cut for v1)

**OUT of scope**:
- Page-level styling — that's per-page leaves
- Storybook / documentation site
- Accessibility audit — Phase 5.4

---

## Objectives

1. A single sample `<TokenSampleCard />` renders correctly using only tokens.
2. `pnpm lint:hex-codes` returns 0 hits in `src/` outside `src/design/`.
3. Tailwind utility classes produce the same colors as Mantine theme.
4. Source Serif loads visibly bold at large sizes (≥48px) on About + Park Explorer headlines.

---

## Dependencies

**Upstream**: `0.6` (Vite + Mantine + Tailwind installed).

**Decisions**: `[100]`–`[114]` design system; CLAUDE.md rule 1 ("hex codes in components are defects").

**Conventions** (`../00-CONVENTIONS.md`): TypeScript style; design tokens.

---

## Required files / modules

**New**:
- `frontend/src/design/tokens.ts`
- `frontend/src/design/theme.ts` (Mantine theme built from tokens)
- `frontend/src/design/fonts.css` (`@font-face` declarations)
- `frontend/src/components/_token-sample.tsx`
- `frontend/scripts/lint-hex-codes.mjs`
- `frontend/tailwind.config.ts` (modified: consume `tokens.ts`)
- `frontend/package.json` (modified: `lint:hex-codes` script)
- `.github/workflows/test-frontend.yml` (modified: run `lint:hex-codes`)

---

## Step-by-step implementation tasks

1. Author `tokens.ts`:
   ```ts
   export const colors = {
     bgBase: '#FAFAF7', bgElevated: '#FFFFFF', bgSubtle: '#F2F1ED', bgEmphasis: '#E8E6E0',
     textStrong: '#161513', textDefault: '#2D2B27', textMuted: '#6B6862', textSubtle: '#9A968F',
     accent: '#B53D2C',
     viz: { viridis: [...], categorical: ['...', '...', '...', '...', '...'] }
   } as const;
   export const typography = {
     fonts: { ui: 'Inter, sans-serif', data: 'JetBrains Mono, monospace', display: 'Source Serif 4, serif' },
     scale: [12, 14, 16, 20, 24, 32, 48, 64],
     lineHeights: { body: 1.5, display: 1.2 },
   } as const;
   export const spacing = [4, 8, 12, 16, 24, 32, 48, 64, 96] as const;
   export const motion = {
     durationsMs: { fast: 150, base: 200, slow: 300 },
     easing: 'cubic-bezier(0.4, 0, 0.2, 1)',
   } as const;
   export const layouts = {
     editorialMaxWidth: 720,
     analyticalMaxWidth: 1200,
     analyticalSidebar: 280,
   } as const;
   ```
2. Author `theme.ts` (Mantine):
   ```ts
   import { createTheme } from '@mantine/core';
   import { colors, typography, spacing } from './tokens';
   export const theme = createTheme({
     colors: { brand: [/* shades derived from accent */] },
     primaryColor: 'brand',
     fontFamily: typography.fonts.ui,
     headings: { fontFamily: typography.fonts.display },
     fontFamilyMonospace: typography.fonts.data,
     spacing: { xs: '4px', sm: '8px', md: '16px', lg: '24px', xl: '32px' },
   });
   ```
3. Modify `tailwind.config.ts`:
   ```ts
   import { colors, typography, spacing } from './src/design/tokens';
   export default {
     content: ['./index.html', './src/**/*.{ts,tsx}'],
     theme: {
       colors: { ...colors, accent: colors.accent },
       fontFamily: { ...typography.fonts },
       fontSize: typography.scale.reduce((a,s,i)=>({...a, [`scale-${i}`]: `${s}px`}), {}),
       spacing: spacing.reduce((a,v,i)=>({...a, [v]: `${v}px`}), {}),
     },
   };
   ```
4. `fonts.css` — `@font-face` for Source Serif 4 (woff2), Inter (system fallback), JetBrains Mono (system fallback). Self-host via `frontend/public/fonts/`.
5. Author `<TokenSampleCard />` showing one of every primitive: a paragraph in Inter, a number in JetBrains Mono, a heading in Source Serif 4 at 48px, the brick-red accent.
6. Author `lint-hex-codes.mjs`:
   ```js
   import fg from 'fast-glob';
   import fs from 'fs';
   const files = fg.sync(['src/**/*.{ts,tsx,css}', '!src/design/**']);
   const hits = [];
   const HEX = /#[0-9a-fA-F]{3,8}\b/g;
   for (const f of files) {
     const lines = fs.readFileSync(f,'utf8').split('\n');
     lines.forEach((l,i)=>{ const m=l.match(HEX); if (m) hits.push(`${f}:${i+1}  ${m.join(' ')}`); });
   }
   if (hits.length) { console.error('hex code violations:\n' + hits.join('\n')); process.exit(1); }
   ```
7. Add `"lint:hex-codes": "node scripts/lint-hex-codes.mjs"` to `package.json`.
8. Update CI workflow to run `pnpm lint:hex-codes`.
9. Wire `MantineProvider theme={theme}` and `import './design/fonts.css'` in `main.tsx`. Move existing 0.6 styles to use tokens (no hex codes left in pages).

---

## Testing requirements

**Unit**: `tokens.ts` exports validate (e.g., `colors.accent` is `'#B53D2C'`).
**Integration**: `<TokenSampleCard />` Vitest snapshot stable across rerenders.
**CI**: `pnpm typecheck`, `pnpm test`, `pnpm lint:hex-codes`.

---

## Acceptance criteria

- [ ] Tailwind config consumes `tokens.ts`; no duplicate values.
- [ ] Mantine theme consumes `tokens.ts`.
- [ ] `pnpm lint:hex-codes` returns 0 hits in `src/` outside `src/design/`.
- [ ] Source Serif 4 visibly applied to a 48px headline.
- [ ] Existing 0.6 health page still renders correctly using tokens (no regression).

---

## Known edge cases

- **Tailwind JIT cache**: changing tokens needs a `pnpm dev` restart. Don't fight it.
- **Mantine breaking minor versions**: pin Mantine version in `package.json`.
- **Unicode in fonts**: Source Serif 4 covers Latin, Cyrillic, Greek. We only need Latin; OK.
- **Storybook absent**: rely on `<TokenSampleCard />` and visual review during page leaves.

---

## Risks

None directly. CLAUDE.md rule 1 enforced by `lint-hex-codes`.

---

## Status log

| 2026-05-10 | Authored. |
