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

## D5 RESULT — fielder margin re-tuned 45 → 15 ft (applied, code-side)

`scripts/calibrate_fielder.py --sample 8000` on the desktop (2015-2025,
empirical fences via `BULLPEN_PARK_GEOMETRY_DIR`, Phase-1 physics) swept
`hr_min_dist_past_fence_ft` against the sample's observed HR rate (4.61 %):

| margin (ft) | pred HR rate |
| ----------- | ------------ |
| 45 ([132])  | 2.85 %       |
| 30          | 3.98 %       |
| 20          | 4.16 %       |
| **15**      | **4.19 %**   |

The +45 margin over-suppressed badly (2.85 % vs the [132] **4.2 %** target) —
the carry it offset is gone. Recommendation **floored at 15** (4.19 % ≈ the
4.2 % league target; the sample's 4.61 % skews high on era-mix). Applied
`DEFAULT_HR_MIN_DIST_PAST_FENCE_FT = 15.0` in `parks/_classify.py` (which
`_fused` imports).

### D5 UPDATE — distance-only is insufficient; the HEIGHT margin is the porch lever

A `compare_park_factors` run after the distance change came back **flat**
(`physics vs observed_norm` 0.585 ≈ the 0.588 geometry baseline). Two findings:

1. **Those labels were stale** — the `physics` column averaged **~2.75 %** HR,
   the margin-45 rate (calibrate_fielder: 45 → 2.85 %), not the ~4.2 % margin-15
   produces. The re-retrodiction hadn't run; the compare read the old
   geometry-run labels. (Tell: the counterfactual grand-mean prob_hr ≈ the home
   HR rate when calibrated; 2.75 % ⇒ margin 45.)
2. **The 25 ft _height_ margin is the binding constraint on short porches.** The
   classifier needs `z_at_fence > fence_h + 25` AND `landing > fence + dist`. A
   liner clearing NYY's ~8 ft RF wall at 20 ft of height is a real HR but fails
   `20 > 8+25`. **Lowering distance alone can't free the porches** — the height
   gate still rejects their characteristic low line-drive HRs. The two margins
   were both inflated by [131]'s no-wind over-carry, but **asymmetrically**: the
   height gate is what suppresses the short-porch signal the gate [52] needs.

**Fix:** `calibrate_fielder.py` upgraded to a **2-D (dist × height) sweep**
(commit pending) that holds the global rate at observed while picking the
fielder _shape_ that best reproduces per-park reality (max Spearman of per-park
predicted vs observed home HR rate — an in-sample proxy; `compare_park_factors`
confirms on the roster-stripped counterfactual). Expectation: the height margin
drops to a physical few-ft (a leaping fielder robs ~3–5 ft over the wall), with
distance rising to hold the rate — redistributing HR credit toward the porches.

### D5 RESULT (2-D) — applied (dist=0, height=20); shape confirmed, gain bounded

The 2-D sweep (30000 BIPs, observed global 4.65 %) confirmed the thesis **and**
its ceiling:

- **Shape matters, height-dominated wins.** Moving from the old distance-
  dominated corner (`dist≈35, height≈0`, rho **+0.38**) to height-dominated
  (`dist=0, height=20`, rho **+0.54**) improves per-park rank fidelity at the
  same global rate. Distance is near-irrelevant once height binds (the whole
  `dist 0-25, height 20` band is flat at 4.97 %/+0.54). Applied **(0, 20)** in
  `parks/_classify.py` — `dist=0` is also the physically-correct value now that
  carry is calibrated (a ball landing past the fence IS a HR).
- **But the gain is bounded by a real rate↔rank tension.** The _highest_ rho
  (+0.62 … +0.69) lives at `height ≥ 25`, which under-predicts the global rate
  (2.7–4.1 %). The rate constraint caps the feasible home-rho at ~+0.54. **Two
  global knobs cannot fully fix the ranking** — the residual is per-park
  effects (D3 humidor / D4 marine-layer), not the fielder model. Expect the
  re-retrodict to lift `physics vs observed_norm` off 0.588 _modestly_, not to
  the ceiling.
- Also surfaced: `height=20` (not the ~5 ft pure-robbery guess) is needed
  because the sim has **no wall-collision** — the height margin doubles as a
  wall-ball proxy (a low fence-crosser hits the wall in reality). A real
  wall-collision model is a candidate future lever.

One fused-parity boundary case (barreled shot @ SF's deep CF) moved to a 3/10-MC
split (dominant class still HR); tolerance bumped 0.3 → 0.35, documented.

**Morning sequence (code now applied):** (1) re-retrodict (geometry override
set) so the labels pick up (0, 20); (2) `compare_park_factors`. NYY/PHI should
climb and `physics vs observed_norm` rise off 0.588 (modestly — see tension
above). Then on to **D3/D4** (the over-ranked side), which need `/decide` on a
_physical_ (non-circular) approach. Ranking, not the absolute rate, is the gate.

### D5 CONFIRMED — re-retrodict moved `physics vs observed_norm` 0.585 → 0.649

The (0, 20) re-retrodict (1.25M BIPs, empirical geometry) lifted the headline
**0.585 → 0.649** (+0.064) — better than the "modest" call. Lever stack so far:
raw **0.294** → +geometry **0.588** → +fielder **0.649**, vs the 0.935 ceiling.
The porches climbed as predicted (NYY #18→#11, PHI #9→#6, MIL #8→#5-exact; CIN's
#4→#7 is tight-cluster reshuffle at 0.042, not a regression).

**The surviving gap is the per-park EFFECTS, now named by the table** — all
_over_-ranked (physics thinks they're more HR-friendly than reality):

| park | physics | obs_norm | over-rank | cause                                  |
| ---- | :-----: | :------: | :-------: | -------------------------------------- |
| COL  |   #1    |    #9    |     8     | humidor (COR not modeled) → **D3**     |
| SEA  |   #4    |   #17    |    13     | marine air (cool/dense, deep) → **D4** |
| ATH  |   #16   |   #28    |    12     | marine layer / foul → **D4**           |
| DET  |   #9    |   #22    |    13     | deep + cold-April air → audit          |
| CWS  |   #2    |    #6    |     4     | (watch)                                |

Fixing COL (humidor) + the Pacific/cold cluster (SEA/ATH/DET) is where the next
~0.1-0.2 of rho lives. The fielder model is done; the rest is per-park physics.

### D4 still-air interim RESULT — `physics vs observed_norm` 0.649 → 0.704

The [138] still-air interim (away parks flown through the destination's seasonal
**temperature**/density, **no wind**; home park keeps real game weather) lifted
the headline **0.649 → 0.704** (+0.055). Stack: raw 0.294 → geometry 0.588 →
fielder 0.649 → **temperature 0.704**, ceiling 0.935 (~64 % of raw→ceiling
closed). Because wind is excluded, this is a clean **temperature-only isolation**,
and the cool cluster split three ways:

| park | obs_norm | @0.649 | @0.704 | read                                     |
| ---- | :------: | :----: | :----: | ---------------------------------------- |
| ATH  |   #28    |  #16   |  #23   | temperature was ATH's **dominant** cause |
| SEA  |   #17    |   #4   |   #7   | barely moved → residual is **wind**      |
| DET  |   #22    |   #9   |  #10   | unmoved → **not climate** (geometry)     |
| SF   |   #30    |  #29   |  #29   | already matched                          |

Consequences for the open levers:

- **Foul-territory (ATH) is looking unnecessary.** Temperature moved ATH to #23
  (near observed #28); the ~5-slot residual is most likely wind, not foul ground.
  **Decision: do not build the foul model; re-check ATH after the wind backfill —
  if it lands ~#28, close the foul line.**
- **SEA justifies the backfill.** Its residual (#7 vs #17) is the marine onshore
  **wind** the interim excludes — the strongest case for [138] stage 2
  (`park_daily_weather` real per-date wind, A/B-gated).
- **DET is a geometry problem, not weather.** Temperature did nothing (#9→#10);
  audit Comerica's deep-CF fence estimate separately.
- **COL #1 vs #9 untouched** — that's **D3 humidor** (locked [137], not yet
  implemented), the biggest single remaining over-rank.
- New watch: **CHC #9 vs #18** over-ranked; CIN/NYY/PHI now slightly _under_.

## CAP (decision [139]) — fidelity work capped at the full lever stack

The humidor [137] was implemented and re-retrodicted, completing the lever
stack. Against `observed_norm`'s **0.935** reliability ceiling:

| lever                                    | `physics vs observed_norm` |
| ---------------------------------------- | :------------------------: |
| raw physics                              |           0.294            |
| + empirical-geometry fences              |           0.588            |
| + D5 fielder re-tune (dist=0, height=20) |           0.649            |
| + [138] destination-temp still-air       |           0.704            |
| + [137] humidor                          |         **0.689**          |

**Within-noise observation (the cap trigger).** The last three runs (0.649,
0.704, 0.689) are _within Spearman noise_ for n=30: SE ≈ (1−ρ²)/√(n−1) ≈
**0.095**, and the whole spread is ~0.16 SE. The headline can no longer
distinguish levers; further per-park physics chases sub-noise ρ. **Stop here.**

**Humidor KEPT despite a COL overshoot.** At Nathan's literature magnitude the
humidor moves **COL physics #1 (error 8 vs `observed_norm` #9) → #13 (error 4)** — error halved, but overshooting #9 to the _under_-ranked side. Kept:
principled (Nathan-anchored, non-circular), improves the single worst
over-rank, free on the noise-flat headline. The magnitude was **deliberately
NOT tuned to land COL at #9** — that would be the circular cheat the gate's
integrity depends on avoiding (ADR-0009). (Mid-implementation correction: the
ambient-RH input moved from outdoor climate normals to climate-controlled
clubhouse storage ~52 % — dry exceptions COL 30 % / AZ 45 % — after outdoor
values invented spurious humid-park EV _boosts_, e.g. Miami +1.82 mph, that
degraded the gate 0.704 → 0.679.)

**Deferred to the improvement backlog** (an asset, not a gap — the
drift-detection + retraining machinery exists to operate + improve the model
over a season):

1. [138]'s `park_daily_weather` wind backfill + the SEA marine-wind residual.
2. The DET deep-CF geometry audit (temperature ruled out climate for DET).
3. The CHC over-rank.
4. Humid-park climate-RH polish + the humidor EV→HR-sensitivity / magnitude
   refinement (the COL overshoot — investigate the EV→HR mapping, **do not**
   tune the humidor to the gate).
5. Promoting the staged empirical geometry to prod (open D1).

**Next — the ship sequence (NOT part of this cap):** **D2** — re-aim the 2c.7
gate target from the noisy published file (`observed_norm` vs published only
0.638) to `observed_norm` at a reliability-derived threshold (amends [52]) →
retrain the MLP on the capped labels → run the real `test_cross_park_sanity` →
register the model.

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

> **CAP — decision [139] (2026-06-02).** The fidelity push is **capped at the
> full lever stack** (physics vs `observed_norm` ~0.689; see the CAP section
> above). **D2 is the next action**; **D1 and the items below are backlog.**
> Remaining levers chase sub-noise ρ (SE ≈ 0.095, n=30) — lower-leverage than
> the live-data poller (issue #1), the Ops dashboard, and ML-wrapper polish.

- **D1** — empirical geometry replaces the 5-point polyline (pending Phase 0).
  **BACKLOG (per [139]):** the staged fences are validated and in use via
  `BULLPEN_PARK_GEOMETRY_DIR`; promoting them to prod (`--apply` + commit the
  regenerated `infra/park_geometry/*.json` from the Mac, ADR-0006) is backlog
  item (5).
- **D2** — re-aim the 2c.7 gate target + threshold. **NEXT ACTION (per
  [139]):** re-aim from the noisy published file (`observed_norm` vs published
  only 0.638) to `observed_norm` at a reliability-derived threshold (amends
  [52]) → retrain the MLP on the capped labels → run the real
  `test_cross_park_sanity` → register the model.
- **D3** — humidor model (Branch A Phase 1A). **RESOLVED 2026-06-02** —
  approach decided = Option A: a uniform, physically-sourced,
  ambient-relative, era-aware per-(destination park, BIP season) EV
  reduction in the retrodiction labels — a humidor-vs-ambient COR delta
  scaled to batted-ball speed, with zero per-park free parameters →
  non-circular with the 2c.7 gate. See decision [137] / ADR-0009.
  **IMPLEMENTED + CAPPED (per [139], 2026-06-02):** `humidor.py` + wired into
  both retrodiction paths in `labels.py`, re-retrodicted. At literature
  magnitude it **over-corrects COL** (#1 / error 8 → #13 / error 4 — halved but
  overshooting #9 to the under-ranked side). **Kept**: principled, improves the
  worst over-rank, headline within Spearman n=30 noise; magnitude deliberately
  NOT tuned to land COL at #9 (the non-circularity discipline). Ambient RH was
  corrected from outdoor climate normals to climate-controlled clubhouse storage
  (~52 %, dry exceptions COL 30 % / AZ 45 %) after outdoor values invented
  spurious humid-park boosts (Miami +1.82 mph) that degraded the gate 0.704 →
  0.679. The EV→HR-sensitivity / magnitude refinement (the overshoot) is
  **backlog item (4)** — investigate the EV→HR mapping, do not tune to the gate.
- **D4** — destination-weather in the counterfactual (was scoped here as the
  foul-territory model for ATH; the audit found the dominant cause of the
  cool-coastal over-rank — SEA/ATH/SF/DET — is the away-park branch flying
  each ball through the BIP's _origin_-game temp + wind instead of the
  destination park's). **RESOLVED 2026-06-02** — approach decided = Option A:
  fly each ball through the **destination park's real measured weather
  (game-time temp + wind) on that ball's date**, backfilled for all 30 parks
  × all dates 2015–2025 into a new `park_daily_weather` (park_id, date) table
  from Open-Meteo's historical archive, with **seasonal still-air as the
  documented fallback** for gaps. Staged: (1) still-air interim (temp +
  humidity + altitude, no wind, no backfill) lands first; (2)
  `park_daily_weather` backfill upgrades to real per-date weather; (3) wind
  A/B. The re-introduced wind is **A/B-gated** because the prior per-park
  seasonal-wind path (`get_atmosphere`) was tried and reverted — a single
  estimated seasonal wind vector per BIP scrambled the cross-park ranking —
  so real daily wind is kept only if it raises cross-park rho over still-air
  (the bet: accuracy, not wind per se, was the problem). The home park keeps
  its real game weather ([88]). Composes with the [137] humidor so the
  counterfactual flies each ball through each park's full real conditions
  (altitude + humidity + temp + wind + humidor COR). See decision [138] /
  ADR-0010. Implementation pending (still-air interim → backfill →
  re-retrodict + `compare_park_factors` → wind A/B → MLP retrain for the real
  gate). The re-retrodict also separates whether DET's over-rank is climate
  (this helps) or geometry/deep-CF (this won't). The original foul-territory
  lever for ATH remains available if its residual persists after the
  destination-weather fix.
- **D5** — fielder/HR-margin recalibration, per-park-aware (Branch B suspect 1).
