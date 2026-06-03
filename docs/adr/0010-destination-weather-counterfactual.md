# ADR-0010: Fly each batted ball through the destination park's real per-date weather in the cross-park counterfactual

- **Status**: Accepted
- **Date**: 2026-06-02
- **Deciders**: alex
- **Related**: `decisions.md` entries [47] [52] [86] [88] [131] [132] [137] [138], ADR-0006, ADR-0009, `plan.md` Phase 2c, `design.md` §2 (data sources) §4.2, `docs/cross-park-fidelity-plan.md` (D4)

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
(decision [132]'s thresholds re-tuned to `dist=0`/`height=20`) **0.649**,
and ADR-0009 (decision [137], the humidor) is the resolution of the COL
over-rank on the over-ranked side of the table. The surviving structural
errors are the rest of the over-ranked parks, and after COL the cluster
that stands out is the **cool-coastal** one: **SEA physics #4 vs
`observed_norm` #17, ATH #16 vs #28**, plus SF and **DET #9 vs #22**. The
proxy says the counterfactual believes these parks are far more
HR-friendly than reality.

The cause is mechanical and documented. The retrodiction is a
counterfactual: it takes each real batted ball and flies it through every
park's conditions (decision [47], the Path-A physics-retrodiction
labeling). The D4 audit found that the away-park branch in
`battedball/retrodict/labels.py` was applying the **BIP's _origin_-game
temperature and wind to every destination park** —
`weather_to_atmosphere(origin_game_weather, dest_park)` — so only altitude
and seasonal humidity were actually destination-specific. A home run hit
in Boston, asked "is this a home run in Seattle?", was being flown through
**Boston's warm air**, never Seattle's cool marine air. Warm air is less
dense, so the ball over-carries, so the cool coastal parks over-rank. The
counterfactual must answer "this ball at park P" using **P's** conditions;
on the temperature and wind axes it was using the origin park's.

That the lever exists is already confirmed by the per-park seasonal-temp
data: SF ~16 °C, SEA ~17 °C, ATH ~18 °C against MIA ~24 °C, HOU ~23 °C — a
~7–8 °C spread, which is several feet of carry. The home park is not at
issue: it keeps its real game weather (decision [88], PR #18), which is the
observed-label anchor and is correct as-is.

There is a known failure to respect. A per-park _seasonal-climate_ path
that **included a prevailing wind vector** (`get_atmosphere`) was already
built and reverted; its docstring records that "applying this single
seasonal wind to every BIP scrambled the cross-park HR ranking." A fixed,
hand-estimated prevailing-wind vector per park is therefore known-bad, and
any decision that re-introduces wind has to explain why it will not
repeat that failure.

This decision is upstream of the gate it improves, so the same
non-circularity discipline as ADR-0009 applies: the inputs are exogenous
(measured weather, not parameters fit to the gate), and the wind axis —
the one with a prior failure — is settled by an A/B comparison, not by
assertion.

## Decision

We fly each batted ball through the **destination park's real measured
weather (game-time temperature + wind) on that ball's game date** in the
cross-park counterfactual. The Boston ball asked "home run in Seattle?"
uses **Seattle's measured weather that date** (cool, dense, real marine
wind), so it carries less and Seattle ranks correctly lower. The origin
park's weather is irrelevant for away parks. The home park keeps its real
game weather (unchanged, decision [88]). This is **Option A** of the
candidates below — the highest-fidelity completion of the
destination-conditions counterfactual that ADR-0009 (the humidor) started.

This requires a **weather backfill**. The per-game `weather_observed`
table (decision [88]) only covers the ~half of (park, date) cells where a
park actually hosted a game that day; the counterfactual needs **every**
park's weather on **every** date a ball was hit somewhere. We pull
historical daily/hourly temperature + wind for **all 30 park locations
across all dates 2015–2025** (~60k (park, date) rows, ~30 location pulls)
into a **new `park_daily_weather` table keyed by (park_id, date)**, sourced
from **Open-Meteo's free historical archive** (decision [86]'s locked
weather source). For a (park, date) where the park had no game, we use the
representative first-pitch hour for that date. This extends decision [88]'s
weather harness from per-game to per-(park, date); it is an **offline
labeling** dependency, not on the serving path.

**Seasonal still-air is the documented fallback.** For any (park, date)
gap the backfill cannot cover, the counterfactual falls back to the
existing per-park `default_atmosphere` (seasonal temperature + humidity +
altitude, **no wind**) rather than failing or silently using origin
weather. The fallback is explicit, not silent.

This decision **re-introduces wind**, which was reverted. The distinction
from the prior failure is the bet that **accuracy, not wind per se, was
the problem**: the reverted path used a single hand-estimated seasonal wind
vector per park; this path uses the real measured daily wind on each ball's
date — real magnitudes, real directions, averaging to the park's true
climatology rather than to a guess. Because the prior wind attempt
scrambled the ranking, the real-wind version is **A/B-gated**: it is
implemented as a counterfactual-atmosphere mode alongside the still-air
mode, both are re-retrodicted, and the real-daily-weather (with wind)
version is kept **only if it raises cross-park rho** versus still-air. The
A/B settles the wind question with data and guards against re-scrambling.

Implementation is **staged**:

1. **Still-air interim** lands first — destination seasonal temperature +
   humidity + altitude, **no wind, no backfill needed**. This is an
   immediate temperature/density fix for the cool-marine over-rank and is
   feasible without waiting on the data pull.
2. **`park_daily_weather` backfill** upgrades the counterfactual from
   seasonal still-air to real per-date temperature + wind.
3. **Wind A/B** confirms (or rejects) the real wind: keep the
   with-wind labels only if rho improves over the still-air interim.

Scope: this is a change to the retrodiction _labels_ (the away-park
atmosphere) and adds an offline data source + table. It does not touch the
air-side simulator's physics, the fielder model (decision [132]), the
empirical geometry fences, or the humidor adjustment (ADR-0009) — it
composes with the humidor so that each ball is flown through each park's
**full real conditions** (altitude + humidity + temperature + wind +
humidor COR). It does not apply to 2026 data, which is holdout-only
(rule 13).

## Consequences

**Easier:**

- The cool-coastal over-rank (SEA / ATH / SF, and the cold-April component
  of DET) gets the physically correct mechanism: a Seattle ball is flown
  through Seattle's cool, dense air, not Boston's warm air. Temperature and
  density are real per-date measurements, not parameters fit to the gate,
  so the gate (decision [52]) stays honest.
- Together with ADR-0009 the counterfactual is now complete on the
  destination-conditions axis: altitude, humidity, temperature, wind, and
  the humidor COR effect are all the destination park's, not the origin's.
  "This ball at park P" finally means P's conditions on every axis.
- Real daily wind is date-coherent — the wind a ball would have met that
  day, not a season-average guess — and it averages to the park's true
  climatology for free, which is exactly what the reverted single-vector
  path was trying and failing to approximate.
- The still-air interim is shippable tonight without the data pull, so the
  temperature/density half of the fix lands immediately and the backfill
  half follows without blocking it.

**Harder:**

- Changing the counterfactual atmosphere changes **all away-park labels** →
  a **re-retrodict** is required, plus an **MLP retrain** before the effect
  can land in the real 2c.7 gate (the proxy moves first; the gate follows).
  This is the standard cross-park iteration cost, paid twice here (still-air
  interim, then real weather).
- A **new external data source** (Open-Meteo historical archive, already
  the locked weather source per decision [86]) and a **new
  `park_daily_weather` (park_id, date) table** must be built and
  backfilled (~60k rows, ~30 location pulls). This is offline labeling
  infrastructure, version-controlled and Mac-authored (ADR-0006); it is not
  on the serving path, so its operational risk is low, but it is real
  build work.
- The **wind A/B is a required validation step** before the real-wind
  labels are trusted. The with-wind version cannot be assumed correct —
  it has to beat still-air on rho or it is discarded. That is a deliberate
  extra gate, not a free upgrade.

**New failure modes:**

- **Re-introduced wind re-scrambles the ranking.** The exact failure of the
  reverted seasonal-wind path could recur if the daily-wind effect is
  noisier than the climatology it averages to. Mitigation: the A/B gate —
  the with-wind labels are kept only if cross-park rho improves over the
  still-air interim; otherwise we ship still-air and the wind question is
  settled negative, with data.
- **Backfill gaps.** A missing (park, date) row would otherwise corrupt a
  ball's destination atmosphere. Mitigation: the documented seasonal
  still-air fallback — gaps degrade to the per-park `default_atmosphere`,
  not to origin weather or a crash. The fallback is explicit and logged,
  not silent.
- **Stale or mis-keyed backfill.** A wrong (park_id, date) join would
  silently miscalibrate one park-date. Mitigation: the backfill is a
  bounded ~60k-row table built once and diff-reviewable; the
  representative-first-pitch-hour rule for no-game dates is documented so
  the join is reproducible.

**Locked into:**

- **Away-park atmosphere = destination conditions.** The origin park's
  weather is irrelevant for away parks; re-introducing origin weather (the
  status-quo bug) would be a re-decision. The home park keeps its real game
  weather (decision [88]).
- **Wind is kept only if A/B-validated.** Shipping wind without the rho
  comparison, or hand-estimating a prevailing wind vector again
  (Alternative C, the known-bad reverted path), would be a re-decision via
  `/decide`.

**Follow-on:**

- The re-retrodict separates a long-standing ambiguity: whether **DET**'s
  over-rank is climate (cold-April air, which this fixes) or geometry /
  deep-CF (which this will not). After the destination-weather labels land,
  DET's residual position tells us which lever it needs.

## Alternatives Considered

### Alternative A: Real per-(park, date) destination weather, temp + wind, still-air fallback, A/B-gated (CHOSEN)

- Fly each ball through the destination park's real measured temperature
  and wind on that ball's date, backfilled for all 30 parks × all dates
  2015–2025 into `park_daily_weather`, with seasonal still-air as the
  documented fallback and the re-introduced wind gated by an A/B rho
  comparison.
- Chosen: highest fidelity, real measured wind, date-coherent, and it
  completes the destination-conditions counterfactual with ADR-0009. The
  A/B gate neutralizes the prior wind failure by making "keep wind" a
  data-driven decision rather than an assertion.

### Alternative B: Still-air, temperature-only (destination seasonal temp + humidity + altitude, no wind)

- Apply the destination park's seasonal temperature + humidity + altitude
  with no wind and no backfill — the safe, tonight-feasible interim.
- Rejected **as the target**: a seasonal average is coarser than real
  per-date weather, and it discards wind entirely, so it may only partially
  fix the marine parks if their effect is partly wind-driven. **Retained**,
  though, as both the **staged interim** (it lands first, fixing the
  temperature/density half immediately) and the **fallback** for backfill
  gaps. It is the floor this decision builds on, not the ceiling it settles
  for.

### Alternative C: Full destination seasonal climate including a fixed prevailing wind vector (`get_atmosphere`)

- Use the destination park's full seasonal climate — temperature, humidity,
  altitude, **and** a single hand-estimated prevailing wind vector per
  park.
- Rejected: this is the path that was **already built and reverted**. Its
  own docstring records that the single seasonal wind vector applied to
  every BIP **scrambled the cross-park HR ranking**. A fixed,
  hand-estimated prevailing wind is known-bad; the fix is accurate
  per-date wind (Alternative A), not a better-guessed constant.

### Alternative D: Origin-game weather everywhere (status quo)

- Keep applying the BIP's origin-game temperature and wind to every
  destination park — the current `weather_to_atmosphere(origin_game_weather,
dest_park)` behavior.
- Rejected: this **is** the over-rank bug. Flying a Boston ball through
  Boston's warm air at Seattle is the mechanism that over-ranks the cool
  coastal parks. The whole point of the counterfactual is destination
  conditions; origin weather on the temperature/wind axes defeats it.

### Sub-choice: keep the origin game's wind at away parks (hybrid)

- A middle path: use the destination park's temperature/density but carry
  the origin game's measured wind to the away park.
- Rejected: Boston's wind at Seattle is semantically meaningless — there is
  no physical sense in which the wind that blew in Boston blows in Seattle.
  Across many balls it also ~averages to neutral, contributing noise
  without signal. If wind is to be used at an away park, it must be that
  park's wind (Alternative A) or none (Alternative B's still-air).

## Revision History

(none)
