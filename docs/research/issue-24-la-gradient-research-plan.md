# Issue #24: Launch-Angle Carry Gradient - Research Plan

**Status:** scoped, parked. Execution waits for owner prioritization against the
frontend rework (Phase C pages).

**Finding (confirmed at scale, 2026-08-09):** the physics simulator over-carries
low-LA balls and under-carries high-LA balls by +56 ft gradient swing (calibrated,
6000 HRs, real weather). The mean drag calibration zeroes overall bias (-1.3 ft)
but barely touches the gradient (+59.8 uncalibrated -> +56.0 calibrated), proving
this is angle-dependent structure error, not something a scalar correction can absorb.

Canonical numbers (from the box run posted to issue #24):

| LA bucket | n    | bias (ft) |
| --------- | ---- | --------- |
| 15-22     | ~443 | +33       |
| 22-26     | 1422 | +13.97    |
| 26-30     | 1767 | -2.24     |
| 30-35     | 1660 | -13.62    |
| 35-50     | 708  | -22.61    |

## Three candidates

Each makes a different prediction. The goal is to kill two cheaply before
modeling anything.

### Candidate A: Statcast hit_distance_ft semantics (label artifact)

**Hypothesis:** Statcast's `hit_distance_ft` for home runs is extrapolated from
the trajectory, not measured at landing. If the extrapolation systematically
over-estimates distance for flat line drives and under-estimates for high flies,
the "gradient" is in the labels, not our physics.

**Discriminator:** rebuild the LA-binned residuals on non-HR outfield air balls
(flies and liners caught or landing in the field of play, 250-380 ft) where
distance is tracked at the actual landing point, not projected beyond the fence.
If the low-LA over-carry vanishes on tracked-distance balls, the gradient is a
label artifact and the fix is corpus/eval-side (exclude projected distances from
carry validation, or use a different truth source for HR carry).

**Why first:** cheapest test. It can invalidate the entire physics-model track.
No model changes, no new parameters - just a different sample selection in the
existing diagnostic script.

**Stage script:** `training/scripts/issue_24_stage_a.py`

- Pull non-HR air balls from `pitches` (events IN ('field_out', 'single',
  'double', 'triple'), 250 <= hit_distance_ft <= 380, launch_angle >= 15)
- Reuse the same simulate_batch + weather join as the diagnostic
- Bin residuals by LA, print the same table
- If gradient swing < 10 ft: **verdict A confirmed** (label artifact)
- If gradient persists: A rejected, proceed to B

### Candidate B: Spin decay over flight

**Hypothesis:** our simulator holds spin rate constant through the entire
trajectory. Real baseballs lose spin to boundary-layer friction. The balls
most dependent on lift duration (high-LA, long flight time) are the ones
where constant spin over-lifts the most - producing the observed over-carry
at low LA (short flight, spin decay doesn't matter) and under-carry at
high LA (long flight, spin has decayed but our sim still applies full lift).

Wait - that's backwards. If spin decays, we OVER-lift (predict too much
carry) for long-flight balls (high LA). But we UNDER-carry high LA and
OVER-carry low LA. So spin decay would make the gradient WORSE, not better.

Unless: the spin model assigns higher spin to low-LA balls (the backspin
model maps (EV, LA, spray) to spin rate, and low-LA liners may get more
backspin than high-LA flies in the model). The interaction is:

- Low LA: high modeled spin, short flight -> no decay matters -> over-lift
  because we apply full spin for the whole (short) flight
- High LA: lower modeled spin, long flight -> decay matters but we don't
  model it -> we still over-lift but less than the LA-driven geometry
  suggests

This needs the actual spin assignments to reason about. Simpler
discriminator: add a decay term and see what happens.

**Discriminator:** modify `simulate_batch` to accept an optional exponential
spin-decay time constant tau (spin(t) = spin_0 \* exp(-t/tau)). Run the
full 6000-HR diagnostic at tau = 3s, 5s, 8s (literature range for MLB
fastballs per Nathan 2012). If any single tau flattens the gradient to
< 15 ft swing without breaking overall bias by more than +/- 3 ft, spin
decay is the answer.

**Stage script:** `training/scripts/issue_24_stage_b.py`

- Import the existing simulator, monkey-patch or fork the integration
  loop to apply spin_rate \*= exp(-dt/tau) at each timestep
- Run the diagnostic at tau = [3, 5, 8] seconds
- Print the table for each tau + the overall bias
- If any tau flattens gradient < 15 ft AND |bias| < 3 ft: **B confirmed**
- If no tau works: B rejected, proceed to C

### Candidate C: Lift-coefficient saturation

**Hypothesis:** the lift coefficient (Cl) model is a linear function of
spin parameter, but real Cl saturates at high spin rates. Our model
over-lifts at high spin, and if high-spin balls correlate with certain
LA bands, that produces an LA-dependent error.

**Discriminator:** the key prediction is that the residual correlates
with modeled spin rate WITHIN fixed LA bands, not with LA at fixed spin.
Bin residuals by (LA band, spin-rate quartile). If residual tracks spin
within a band, it's the Cl curve. If it tracks LA at fixed spin, it's
NOT Cl saturation (it's something geometric about the trajectory, which
points back to A or B).

**Stage script:** `training/scripts/issue_24_stage_c.py`

- Run the full diagnostic but also record modeled spin_rate per ball
- Create a 2D table: rows = LA bins, columns = spin-rate quartiles
  (within each LA bin), cells = mean residual
- If within-LA-bin spin gradient > 10 ft: **C confirmed**
- If residual is flat across spin within each LA bin: C rejected

## Execution plan

Run in this order. Each stage is independently runnable and produces
its own verdict. Stop as soon as one confirms.

```
cd ~/code/the-bullpen/training

# Stage A (cheapest - can kill the whole physics track)
uv run python -m scripts.issue_24_stage_a --sample 6000

# Stage B (only if A rejects)
uv run python -m scripts.issue_24_stage_b --sample 6000

# Stage C (only if A and B reject)
uv run python -m scripts.issue_24_stage_c --sample 6000
```

Each script:

- Rule 13: seasons 2015-2025, 2026 holdout-only
- Real weather via `weather_observed` (box-side, needs ClickHouse)
- Same thermal discipline as `calibrate_spin.py` (single `simulate_batch`
  call per run, ~6000 rows, same compute profile)
- Prints a verdict line at the end

## Deliverable

`docs/research/issue-24-la-gradient-report.md` with:

- Discriminator results (the table from each stage that ran)
- Ranked verdict (which candidate confirmed, or "none - further research")
- Go/no-go on a model change
- If go: the specific parameter or code change, with the expected carry
  improvement and any side effects on overall bias

## Constraints

- 2026 data is holdout-only (rule 13)
- No unphysical parameter values (the [131] lesson: no lift_scale ~0.47,
  no sub-clamp spin to force a fit)
- Scripts must be Mac-authored, box-executed (ADR-0006)
- Evidence relayed verbatim from the box per the carve-out
