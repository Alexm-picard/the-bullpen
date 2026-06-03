# ADR-0009: Humidor carry effect as a uniform physical ambient-relative per-destination-park EV adjustment in retrodiction

- **Status**: Accepted
- **Date**: 2026-06-02
- **Deciders**: alex
- **Related**: `decisions.md` entries [47] [52] [68] [88] [131] [132] [137], ADR-0006, `plan.md` Phase 2c, `design.md` §4.2, `docs/cross-park-fidelity-plan.md` (D3)

## Context

The 2c.7 cross-park sanity gate (decision [52]) requires the batted-ball
model's per-park P(HR) to rank-correlate with a trustworthy park-factor
target (`observed_norm`, split-half reliability 0.935 — the achievable
ceiling, per `cross-park-fidelity-plan.md`). The label-level proxy
(`compare_park_factors`, `physics vs observed_norm`) is the fast
iteration surface; the real gate runs on the trained MLP
(`test_cross_park_sanity`).

The lever stack so far has climbed the proxy from raw physics **0.294**
→ empirical-geometry fences **0.588** → the D5 fielder-margin re-tune
(decision [132]'s thresholds re-tuned to `dist=0`/`height=20`) **0.649**.
The fielder model is done; the surviving gap is **per-park physical
effects**, and the proxy names them — all _over_-ranked, i.e. physics
thinks they are more HR-friendly than reality.

The single largest surviving structural error is **COL: physics rank #1
vs `observed_norm` #9** — the counterfactual believes Coors is far more
HR-friendly than it actually is. The cause is mechanical and documented.
The retrodiction is a counterfactual: it takes each real batted ball's
_as-measured_ exit velocity (EV) and flies it through every park's air
(decision [47], the Path-A physics-retrodiction labeling). Those EVs were
measured at the hitters' home parks, mostly with normal-coefficient-of-
restitution (COR) balls. Flown through Coors' thin, low-density,
high-altitude air, they carry a long way — which the simulator computes
correctly. But in **reality**, balls hit at Coors come off the bat
**slower**: since 2002 Coors stores its baseballs in a **humidor at 50 %
relative humidity (RH)** against Denver's ~30 % ambient. Higher ball
moisture lowers the COR, which lowers EV off the bat, which cancels part
of the altitude carry. The same-balls-everywhere counterfactual misses
that cancellation, so it over-ranks COL.

Two properties of the humidor effect determine where it belongs in the
model:

- It is a **pre-contact, ball-COR effect, not an air effect.** It changes
  how fast the ball leaves the bat, not how the ball flies once airborne.
  The simulator already scales drag and Magnus by air density; the
  humidor is upstream of launch. So it belongs in the counterfactual as a
  per-destination-park EV adjustment ("if this ball were hit at park P,
  with P's humidor ball, its EV would differ"), not as another air-side
  correction.
- It is **ambient-relative.** COR falls as storage RH rises, so a 50 %
  humidor _suppresses_ carry in dry climates (Denver ~30 %, where 50 %
  wets the ball) but slightly _boosts_ it in humid climates (Miami ~75 %,
  where 50 % dries the ball relative to ambient). A "subtract a constant
  at COL" model would get the sign wrong for humid parks.

It is also **era-varying.** COL adopted the humidor in 2002, Arizona in
2018, several parks across 2018–2021, and **all 30 parks since 2022**
under an MLB mandate. In the 2015–2025 training window COL had it for the
whole window; most parks only from 2022. The counterfactual therefore
needs a season dimension, not just a park dimension.

This decision is upstream of the gate it improves, so non-circularity is
load-bearing: the gate (decision [52]) must remain an honest test, which
means the adjustment must contain **no per-park free parameter fit to the
gate**. Every input has to be exogenous — sourced from physics literature,
a published mandate timeline, and climate normals — so that a sensible
move in the gate is evidence the physics is right, not evidence we tuned
to the answer.

## Decision

We model the humidor's batted-ball-carry effect as a **uniform,
physically-sourced, ambient-relative, era-aware per-destination-park exit-
velocity reduction** applied in the retrodiction labeling
(`training/.../battedball/retrodict/labels.py`), keyed by
**(destination park, BIP season)**, added to `launch_speed_mph` before
spin/trajectory integration:

```
EV_delta(park, season) = k_EV · [ COR(RH_humidor=50%) − COR(RH_ambient(park)) ]   if park had a humidor that season
                       = 0                                                          otherwise
```

A negative delta (dry climate, e.g. Denver) reduces EV; a positive delta
(humid climate, e.g. Miami) slightly raises it. This is **Option A** of
three candidates: one physical function applied across all 30 parks, with
**zero per-park free parameters fit to the gate**. All inputs are
exogenous:

1. **COR-vs-RH slope, plus the COR→batted-ball-speed conversion**, from
   Alan Nathan's published humidor work (the same physics source as the
   ball-flight simulator, decision [47]). The exact constant `k_EV` and
   the COR(RH) function are to be sourced and sanity-checked against the
   literature magnitude before the constant goes into the code.
2. **`RH_humidor = 50 %`** — the MLB humidor standard; COL since 2002.
3. **`RH_ambient(park)`** — a static **30-row climate-normal table**
   (NOAA climate normals): the storage/equilibrium humidity a ball
   reaches at that park _without_ a humidor. Deliberately distinct from
   the per-game outdoor weather already joined into the pipeline
   (decision [88]) — the relevant baseline is the climate humidity the
   ball equilibrates to in storage, not the weather at first pitch.
4. **A per-park humidor-adoption timeline** (documented dates: COL 2002,
   AZ 2018, the 2018–2021 adopters, all 30 from 2022).

Verification is **the whole table, not just COL.** After re-retrodict +
`compare_park_factors`: COL should fall toward #9, humid parks
(MIA/HOU/TB) should tick up, dry parks should tick down, and nothing
perverse should appear. A literature-magnitude delta that moves the table
sensibly confirms the model. If COL barely budges, that is itself a
finding: its residual is partly altitude / Magnus / geometry, learned
honestly rather than tuned away — which is precisely Alternative D's
hypothesis, exposed rather than assumed.

Scope: this is a change to the retrodiction _labels_ only. It does not
touch the air-side simulator, the fielder model (decision [132]), or the
empirical geometry fences. It does not apply to 2026 data, which is
holdout-only (rule 13).

## Consequences

**Easier:**

- The single largest structural mis-rank (COL #1 vs #9) gets a physically
  correct mechanism instead of a fudge. The gate (decision [52]) it feeds
  stays honest because the adjustment is exogenous, not tuned to it.
- One physical function covers all 30 parks, so it generalizes for free to
  the league-wide 2022 humidor mandate and to the humid-park _boost_
  direction — no special-casing, no new parameter per park.
- The non-circularity story is the strongest of the three options: there
  is literally no per-park free parameter that could be over-fit to the
  gate. This is the property a reviewer of the cross-park work will probe.
- Reuses the project's existing physics provenance (Nathan, decision [47])
  and its existing climate/weather discipline (decision [88]) rather than
  introducing a new modeling paradigm.

**Harder:**

- Changing the labels means a **re-retrodict + MLP retrain** before the
  effect can land in the real 2c.7 gate (the proxy moves first; the gate
  follows). This is the standard cross-park iteration cost, but it is not
  free.
- The counterfactual EV adjustment gains a **season dimension** it did not
  have before (the adoption timeline). Every consumer of the labeling that
  assumed a park-only keying must now key on (park, season).
- Three new exogenous inputs must be sourced and version-controlled: the
  Nathan COR-vs-RH slope + conversion, the 30-row NOAA climate-normal
  table, and the documented adoption timeline. The magnitude of `k_EV`
  must be sanity-checked against the literature _before_ it is committed —
  a wrong constant moves the whole table the wrong amount.

**New failure modes:**

- **Climate-equilibrium simplification.** The model assumes a ball without
  a humidor equilibrates to local _climate_ humidity. Real pre-humidor
  clubhouse storage may have been partly climate-controlled, so the
  pre-2002/pre-adoption baseline RH is an approximation. Mitigation: the
  whole-table verification catches gross errors; the residual is what the
  MLP learns.
- **Wrong-magnitude constant.** A `k_EV` that is too large or too small
  moves COL past or short of its target and drags the rest of the table
  with it. Mitigation: the verification is the whole table, not COL alone —
  a perverse move anywhere (a dry park rising, a humid park falling) flags
  a bad constant before it is trusted.
- **Stale climate table.** A typo or stale RH row silently miscalibrates
  one park. Mitigation: the table is 30 static rows under version control,
  diff-reviewed like geometry (ADR-0006, Mac-authored, committed).

**Locked into:**

- The humidor enters the model as a **pre-launch EV adjustment**, never as
  an air-side correction. Re-modeling it as a density/Magnus effect would
  be a re-decision via `/decide`.
- Ambient RH is a **climate-normal table**, not derived from the per-game
  weather already in the pipeline (decision [88]). Switching the baseline
  to game-time weather would reopen the sub-choice below.

## Alternatives Considered

### Alternative A: Full uniform physical model (CHOSEN)

- One physical `EV_delta(park, season)` function over all 30 parks, fed by
  the Nathan COR-vs-RH slope, a 50 % humidor RH, a NOAA climate-normal
  ambient-RH table, and a per-park adoption timeline. Zero per-park free
  parameters.
- Chosen: it generalizes to the league-wide 2022 mandate and to the
  humid-park boost direction with no extra parameters, and it carries the
  strongest non-circularity guarantee for the gate it feeds — there is no
  per-park knob to over-fit. The user's framing was to fix "every park
  rather than just fixing the data to the single park."

### Alternative B: COL-only constant physical EV reduction

- Apply a single physically-sourced EV reduction at COL only — the one
  mis-ranked humidor park (Arizona, the other notable early adopter, is
  already well-ranked at physics #22 vs observed #24, so it needs no
  correction).
- Rejected: it special-cases one park and does not generalize to the 2022
  league-wide adoption or to the humid-park boost direction, where a
  constant subtraction would have the wrong sign. The user explicitly
  preferred a uniform all-park model over patching the single park. It is
  also a weaker non-circularity story — a hand-placed COL constant looks
  more like tuning to the gate than a league-wide physical law does, even
  if the magnitude is literature-sourced.

### Alternative D: Treat COL's over-rank as an altitude/Magnus modeling gap

- Explain COL's over-rank as a deficiency in the air-side model
  (recalibrate air density / Magnus scaling) rather than a humidor effect,
  and tune the density/Magnus treatment until COL falls.
- Rejected as the _primary_ explanation: the simulator already scales drag
  and Magnus by air density (the Phase-1 physics overhaul), and the
  humidor is the documented, physically specific, pre-contact cause of the
  EV suppression at Coors. Recalibrating air physics to absorb a ball-COR
  effect would be fitting the wrong knob. Retained, however, as the
  **fallback hypothesis** the whole-table verification will surface: if a
  literature-magnitude humidor delta under-moves COL, the residual is
  altitude/Magnus/geometry — a finding to act on, not a number to tune.

### Sub-choice: ambient-RH source — climate-normal table (CHOSEN) vs. per-game humidity

- The ambient RH in the `EV_delta` could be a static climate-normal table
  (chosen) or derived from the per-game humidity already stored in the
  pipeline (decision [88]).
- Rejected the per-game source: the physically relevant baseline is the
  storage/climate humidity the ball equilibrates to over time, not the
  outdoor humidity at first pitch. A static climate-normal table also
  keeps a clean separation from the weather pipeline (which models a
  different, air-side, per-game effect), avoiding a confusing double-use of
  the humidity column for two unrelated physical mechanisms.

## Revision History

- **2026-06-02** — Implementation outcome recorded (decision [139]). Status
  stays **Accepted** — the decision and approach hold exactly as written; this
  entry records what implementing them surfaced.
  - **The humidor was implemented** (`battedball/retrodict/humidor.py` +
    wired into both retrodiction paths in `labels.py`) and the labels were
    re-retrodicted. At Nathan's literature magnitude it **over-corrects COL**:
    COL moves from physics #1 (rank error 8 vs `observed_norm` #9) to **#13
    (error 4)** — the error is halved, but it overshoots #9 to the
    _under_-ranked side. **Kept** (per decision [139]): it is principled, it
    improves the single worst over-rank, and the headline is within Spearman
    n=30 noise (the lever-stack runs 0.649 → 0.704 → 0.689 are
    indistinguishable at SE ≈ 0.095). Crucially, the magnitude was **deliberately
    NOT tuned to land COL at #9** — that non-circularity is the discipline this
    ADR establishes, and honoring it means accepting the overshoot rather than
    fitting the constant to the gate. Likely cause of the overshoot: the fielder
    model's EV→HR sensitivity near the fence amplifies the −2.8 / −3.78 mph EV
    reduction. **Flagged as a backlog refinement** — investigate the EV→HR
    mapping; **do not** tune the humidor to the gate.
  - **The ambient-RH input was corrected** from the outdoor climate normals
    specified in the Decision body to **climate-controlled clubhouse storage**
    (~52 % default, with dry exceptions COL ~30 % / AZ ~45 %). The outdoor
    values produced spurious humid-park EV _boosts_ (e.g. Miami +1.82 mph) that
    don't physically exist — clubhouses are HVAC-controlled near the storage
    setpoint, not at outdoor humidity — and that error **degraded the gate
    0.704 → 0.679**. The clubhouse model makes non-dry parks rank-neutral and
    isolates the humidor effect to the genuinely-dry parks (COL, AZ). The
    Decision's sub-choice (ambient RH = a static table distinct from per-game
    weather) is unchanged; only the table's _values_ moved from outdoor-climate
    to clubhouse-storage humidity.
