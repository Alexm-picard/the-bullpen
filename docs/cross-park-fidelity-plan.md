# Cross-park HR-factor fidelity — contingency plan

> **Goal:** get the 2c.7 cross-park gate (decision [52]) to pass — the model's
> per-park P(HR) must rank-correlate with a trustworthy park-factor target.
>
> **Where we are (measured):** the Phase-1 physics overhaul fixed the carry
> _bias_ (+21 → ~0 ft, faithful physical spin + `cd_scale` 1.0483) but moved the
> cross-park _ranking_ almost not at all: `physics vs observed_norm` **0.282 →
> 0.294**. That's the expected result of a _global_ correction — it shifts every
> park's carry by ~the same amount, so it can't change the relative order. The
> deficit is therefore **per-park geometry + per-park effects**, confirmed by the
> surviving structural mis-ranks:
>
> - **Under-ranked bandboxes** (geometry): CIN real #1 → physics #13, NYY #4 →
>   #26, MIL #5 → #15, PHI #2 → #16.
> - **Over-ranked** (per-park effects): COL #9 → #1 (altitude over-weight +
>   unmodeled humidor), ATH #28 → #4 (unmodeled foul territory).
>
> **Targets / ceilings:** `observed_norm` (the self-computed multi-year road
> factor) has split-half reliability **0.935** — that's the achievable ceiling.
> `observed_norm vs published` is only **0.638**, so the external published file
> is a noisy target (D2: re-aim the gate at `observed_norm`). Current physics is
> at **0.294** vs that 0.935-reliable target → large headroom, but it needs the
> per-park levers, not more global physics.

**Fast proxy vs real gate.** `compare_park_factors` `physics vs observed_norm`
is the **label-level proxy** — minutes per iteration (re-retrodict + measure),
no MLP retrain. The **real** 2c.7 gate runs on the _trained MLP_
(`test_cross_park_sanity`). Iterate on the proxy; only retrain the MLP once the
proxy looks good (the MLP faithfully distills the labels — proven at 0.49 ≥
0.40 earlier — so the proxy predicts the gate).

---

## Phase 0 RESULT — geometry WORKS → Branch A active

Re-retrodict with the staged empirical fences moved `physics vs observed_norm`
**0.294 → 0.588** (doubled). Bandboxes rose as predicted (CIN #13→#4, MIL
#15→#7; NYY #26→#20 and PHI #16→#10 _partially_). COL/ATH/DET stay over-ranked
(their non-geometry levers). The _partial_ NYY/PHI recovery is the [132]
HR-margin coupling (Branch B suspect 1) confirmed: the shorter porch fence
can't fully express because `landing > fence + 45 ft` caps it → **fielder-margin
recalibration (D5) is now the evidence-backed top remaining bandbox lever.**
Remaining gap: 0.588 vs the 0.935 ceiling; remaining mis-ranks map to D3
(humidor/COL), D4 (foul/ATH), D5 (fielder/NYY,PHI), + data-correctness audits
(DET deep-fence, SD/MIA marine-layer/roof).

## Phase 0 — Empirical geometry (DONE — worked)

Re-retrodict with the staged per-park empirical fences
(`infra/park_geometry_estimated/`, via `BULLPEN_PARK_GEOMETRY_DIR`) layered on
the new physics, then `compare_park_factors`.

**What to check (paste/eyeball):**

1. **Headline:** `physics vs observed_norm` vs the 0.294 baseline.
2. **Bandboxes specifically** — did CIN / NYY / MIL / PHI physics-ranks _rise_
   toward their real top-5 positions? (These are the high-leverage rank slots;
   fixing them should move Spearman more than a mid-pack shuffle.)
3. **Over-ranked parks** — COL / ATH should be ~unchanged (geometry isn't their
   problem). If they _move_, understand why.
4. **Sanity / regressions** — did any park's physics-rank move the _wrong_ way?
   That flags a bad fence estimate (the p90 wall-ball method going wrong in a
   sparse bin). Cross-check that park's staged fence vs its documented dims.

**Decision thresholds (rough):**

- **Works:** `physics vs observed_norm` climbs meaningfully (≳ **0.45**) **and**
  CIN/NYY/MIL/PHI each rise several slots → **Branch A**.
- **Doesn't:** stays ≲ **0.35**, or the bandboxes don't rise despite shorter
  fences → **Branch B** (diagnose before more geometry).

---

## Branch A — geometry WORKS

1. **Promote the staged geometry to prod.** Review the staged JSON diffs vs
   documented dims (spot-check NYY porch, CIN, COL unchanged), then
   `estimate_park_fences.py --apply` and **commit the regenerated
   `infra/park_geometry/*.json` from the Mac** (ADR-0006). Re-run loader tests.
2. **Phase 1A — Humidor (fixes COL over-rank).** Model the humidor-stored ball's
   reduced COR → lower effective EV off the bat at humidor parks (COL, AZ, and
   the others that added humidors). `/decide` (changes labels). Per-park, so it
   _will_ move the ranking.
3. **Phase 2A — Foul territory (fixes ATH over-rank).** Foul-out probability from
   `foul_territory_sqft` + spray near the lines → converts some balls to outs at
   big-foul parks. `/decide`. Per-park.
4. **Re-measure** after each (proxy). Stop when `physics vs observed_norm`
   approaches the reliability ceiling / the re-aimed threshold.
5. **D2 — re-aim the gate** to `observed_norm`, threshold from the 0.935
   reliability (not the a-priori 0.80). `/decide`, amends [52].
6. **Close the loop:** wire the validation harness (Phase 1c-validate), retrain
   the MLP on the new labels, run the _real_ `test_cross_park_sanity` gate.

---

## Branch B — geometry DOESN'T work (diagnose, in priority order)

1. **Prime suspect: the [132] HR-margin is masking geometry.** The classifier
   only calls HR when `landing > fence_dist + 45 ft AND z_at_fence > fence_h +
25 ft` (tuned to a _global_ 4.2% HR rate). A **shorter fence doesn't produce
   more HR** unless that +45 ft margin is also relaxed — so geometry and the
   fielder model are **coupled**. **Check:** re-retrodict a sample with
   `hr_min_dist_past_fence_ft` lowered (it's a `classify_outcome` kwarg) and see
   if CIN/NYY HR-rate now responds to the shorter fence. If yes → the **fielder
   recalibration (Phase 3) is the real gate**, and it must be done _with_
   geometry, per-park-aware (not one global margin).
2. **Fence-estimate quality.** Confirm the staged fences are actually shorter at
   the bandboxes (`estimate_park_fences.py --park CIN --dry-run` notes). Is the
   p90 wall-ball estimate noisy/biased? Re-estimate on the desktop's _full_ data
   (vs the Mac dev sample), tighten the percentile, or add explicit porch/corner
   points instead of uniform 5° bins.
3. **Resolution.** 5° bins may still smooth a sharp porch corner. Try finer bins
   or explicit discontinuity points (the schema already allows variable-length
   polylines).
4. **If geometry genuinely can't move it:** the per-park signal may be dominated
   by the _effects_ (humidor/foul) + the _target_. Jump to Phase 1A/2A, and
   reconsider whether a same-balls-everywhere geometric counterfactual can ever
   match `observed_norm` — maybe the gate should compare physics to a
   _counterfactual_ target (a fixed league sample through each park's physics),
   not the roster-shaped `observed_norm`. That's a target re-think (extends D2).

---

## Cross-cutting (any branch; can run in parallel)

- **D2 gate re-aim** — `observed_norm` (reliability 0.935) vs the noisy published
  file (0.638). About what the gate _measures_; decide independently of the
  physics work. `/decide`, amends [52].
- **Phase 1c-validate** — wire the calibration into the _validation harness_
  (fixtures spin + `validate.py --weather` `cd_scale`), handle the no-weather CI
  gate, and set the tightened **carry** gate threshold from the weather-corrected
  MAE ([131]'s deferred re-tighten). Separate from the cross-park gate.
- **The real gate is on the MLP.** `compare_park_factors` is the cheap proxy;
  registration is gated by `test_cross_park_sanity` on the retrained model. Final
  sequence per branch: best physics+geometry+effects → re-retrodict → retrain
  MLP → run the gate.

## Decisions still open (route through `/decide` before locking)

- **D1** — empirical geometry replaces the 5-point polyline (pending Phase 0).
- **D2** — re-aim the 2c.7 gate target + threshold.
- **D3** — humidor model (Branch A Phase 1A).
- **D4** — foul-territory model (Branch A Phase 2A).
- **D5** — fielder/HR-margin recalibration, per-park-aware (Branch B suspect 1).
