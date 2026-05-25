# Park stadium SVGs

These files are **generated** — do not hand-edit. Run:

```
node frontend/scripts/build-park-svgs.mjs
```

from the repo root (or `npm run build:parks` once the script is wired) to
regenerate from the canonical fence polylines in
`infra/park_geometry/<park_id>.json` (Phase 2c.3).

## Convention

- `viewBox="0 0 500 500"` for every park.
- 1 SVG unit = 1 foot.
- Home plate at `(250, 480)`. Center field axis is straight up.
- Angles match the simulator: `+ = LF (3B side)`, `0 = CF`, `- = RF`.
- `stroke="currentColor"` so consumers theme the line via CSS `color`.
- Each file wraps the geometry in a `<symbol id="field">` so consumers
  can `<use href="/parks/<park_id>.svg#field" />` instead of inlining.

## Why generated vs hand-drawn

The leaf body (4c.1) called for 30 hand-drawn SVGs. We generate from
`infra/park_geometry/*.json` instead because:

- That JSON is the simulator's authoritative HR classification source.
  A hand-drawn SVG would risk silent drift (the leaf's own "known edge
  case" calls this out).
- Determinism: bumping a fence value in one JSON regenerates the SVG
  for free.
- The Phase-5.5 polish pass can still replace any one of these with a
  bespoke artwork file; the consumer (`<StadiumSvg>`) doesn't care how
  the SVG was produced as long as it exposes `<symbol id="field">`.
