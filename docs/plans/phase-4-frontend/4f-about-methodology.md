# 4f — About / Methodology page

> Owning phase: `phase-4-frontend` · Estimated effort: `5–8 h` · Authored: 2026-05-10

---

## Scope boundaries

**IN scope**:
- React page at `/about` with editorial visual treatment (decision `[100]`, `[102]`, `[111]`)
- Editorial layout pattern: max-width 720 px, generous vertical rhythm
- Source Serif 4 headlines at 48–64 px, weight 600+
- Long-form sections:
  - What this project is
  - The models (with links to eval artifacts)
  - Training data and licensing
  - Eval methodology
  - Drift detection + retraining
  - "v2 ideas" (cherry-picked from `design.md` §11)
- Embedded illustrations (a small ASCII / SVG architecture diagram)

**OUT of scope**:
- Storybook / a11y pass — Phase 5.4
- Public launch posts — Phase 5.6

**Soft cut**: "visual ambition on About" can be cut by end of Wk 30 if behind. Page stays minimal-functional.

---

## Objectives

1. About page reads like an Athletic article, not a SaaS landing page.
2. Source Serif headlines visibly bold at 48–64 px.
3. Tabular figures used in any inline data references.
4. Page is the project's "elevator pitch" — recruiters spend 1 minute here and learn what's interesting.

---

## Dependencies

**Upstream**: `4a` (design tokens), all prior phases (so the eval artifact links resolve).

**Decisions**: `[100]`, `[102]`, `[103]`, `[104]`, `[111]`.

---

## Required files / modules

**New**:
- `frontend/src/pages/about/about-page.tsx`
- `frontend/src/pages/about/sections/`:
  - `intro.tsx`
  - `models.tsx`
  - `training-data.tsx`
  - `eval-methodology.tsx`
  - `drift-and-retraining.tsx`
  - `whats-next.tsx`
- `frontend/src/components/editorial/section.tsx` — wraps content in editorial layout

**Modified**:
- `App.tsx` — add `/about` route.

---

## Step-by-step implementation tasks

1. Author `<EditorialSection title body />`:
   ```tsx
   <article className="mx-auto max-w-[720px] py-24">
     <h2 className="font-display text-scale-7 leading-display tracking-tight">{title}</h2>
     <div className="prose mt-8">{body}</div>
   </article>
   ```
2. Author each section file with prose drawn from `design.md`:
   - `intro.tsx`: framing from §1.
   - `models.tsx`: short paragraph per model, link to its `/ops` registry detail.
   - `training-data.tsx`: source list + ToS link (Risk Register I7).
   - `eval-methodology.tsx`: rolling-origin CV explainer.
   - `drift-and-retraining.tsx`: how the wrapper works.
   - `whats-next.tsx`: 5–7 cherry-picked v1.5 items.
3. Add a small inline SVG architecture diagram showing data → model → API → frontend (pulled from `design.md` §2).
4. Wire all sections into `<AboutPage />`.
5. Lighthouse: this page must score >90 on accessibility (long-form prose is easy).
6. Tests: Playwright loads page; checks that headlines render in serif font (`getComputedStyle` font-family contains 'Source Serif 4').

---

## Testing requirements

**Component**: snapshot of `<EditorialSection />`.
**E2E**: page loads; serif font verified; no broken internal links.
**CI**: standard.

---

## Acceptance criteria

- [ ] `/about` page accessible.
- [ ] Six sections present with editorial typography.
- [ ] Source Serif headlines visible at 48–64 px.
- [ ] All internal links (to `/ops/...`) resolve.
- [ ] Lighthouse accessibility ≥ 90.
- [ ] Tests pass; lint passes.

---

## Known edge cases

- **Mobile viewport**: max-width 720 px is the cap; on smaller screens, padding shrinks. Test at 320px.
- **Long prose**: keep each section under ~500 words. Recruiters skim.
- **External links**: include `rel="noopener noreferrer"` on any external `target="_blank"`.
- **Soft cut path**: if cut, replace each section with a single paragraph; remove the architecture diagram.

---

## Risks

- `I7` — closes (data licensing language lives here + in 5.6 README).

---

## Status log

| 2026-05-10 | Authored. |
