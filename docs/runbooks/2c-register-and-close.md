# Phase 2c — register the batted-ball model + close Phase 2

> **Scope:** the close-out that picks up where
> [`2c-overnight-pipeline.md`](2c-overnight-pipeline.md) stops. That runbook
> trains the batted-ball MLP + LGBM on the desktop GPU and writes artifacts to
> local disk; it explicitly does **not** register them. This runbook verifies the
> production gates, registers the model into the registry (SHADOW), and closes the
> Phase-2 exit criteria.
>
> **Who runs what:** you run the desktop pipeline + the registration calls
> (you're on the prod box); report the five numbers in
> [§6](#6-report-back-for-laptop-side-verification) back so the laptop side can
> cross-check ECE + the registry row.

What closing Phase 2 means (phase-status exit criteria still open):

- Multi-output MLP (shared backbone + 30 per-park heads) + 30 isotonic
  calibrators — trained on full data, **per-park ECE < 0.05** pre-cal.
- LR/LGBM baseline co-registered for the batted-ball role (rule 9).
- **ECE < 0.02 on test data** for the registered model.
- Served via `PredictBattedBallController` / `PredictAllParksController`.

> **Note on the `*_per_park` modules:** `mlp_per_park` / `lgbm_per_park` are an
> "Option A experiment" (30 fully-separate models), **not** the production path.
> The orchestrator (`run_2c_overnight.sh`) trains the shared-backbone `mlp` + the
> single `lgbm_baseline`, and the 2c.9 comparison + decision [45] are about those
> two. Ignore the per-park modules for this close.

---

## 0. Before you start

The overnight pipeline ([`2c-overnight-pipeline.md`](2c-overnight-pipeline.md))
has completed and the sanity-gate stage passed. Confirm the artifacts exist:

```bash
cd ~/code/the-bullpen/training
ls -lh artifacts/battedball_mlp_v1/              # model.pt model.onnx metadata.json calibrator.json
ls -lh artifacts/batted_ball_lgbm_baseline/v1/   # model.txt metadata.json calibrator.json
ls data/eval/reliability_diagrams_per_park/ | wc -l   # 30
```

If the sanity-gate stage FAILED, **stop** — the MLP is broken per decision [52];
do not register it. Investigate `data/cross_park_sanity_report.json`.

---

## 1. Verify the production gates

### 1a. Cross-park sanity (decision [52], re-aimed by [140]: ρ ≥ 0.65 vs observed_norm)

```bash
cat logs/2c-overnight/2c.7-sanity-gate.log | tail -20
```

Look for the Spearman ρ and the Coors→Oakland gap. **ρ ≥ 0.65 against
`observed_norm`** (the frozen `data/observed_norm_factors.json` anchor — _not_ the
published file) and the gap positive ⇒ pass. (This stage already ran under `pytest
-m production`; a green stage in the orchestrator log means it passed.) The gate
**fails loud** if the anchor is missing while a model exists — if you see that,
the one-time anchor emit (overnight-pipeline runbook prereqs) wasn't done.

> Threshold history: 0.80-vs-published was unreachable — the lever stack caps at
> ρ ≈ 0.69 vs `observed_norm` (reliability ceiling 0.935, decision [139]). 0.65 is
> the interim floor (decision [140]), to be tightened as the model improves.

### 1b. Per-park ECE (the 2c.6 calibrator output)

```bash
grep -E "per-park ECE" logs/2c-overnight/2c.6-calibrators.log
#   per-park ECE pre  : mean=0.0NN  max=0.0NN
#   per-park ECE post : mean=0.0NN  max=0.0NN
```

Gate: **post-calibration per-park ECE mean < 0.05** (the 2c bar). For the Phase-2
exit criterion you also want the **aggregate test ECE < 0.02** — read it from the
calibrated metadata:

```bash
python3 -c "import json,sys; d=json.load(open('artifacts/battedball_mlp_v1/metadata.json')); \
print('aggregate ECE:', d.get('ece') or d.get('test_ece') or d.get('metrics',{}).get('ece','(field name varies — grep the file)'))"
grep -iE "ece" artifacts/battedball_mlp_v1/metadata.json   # fallback: see what the field is called
```

If the aggregate ECE is ≥ 0.02, the model misses the Phase-2 bar — register it
SHADOW anyway (it's a candidate, not LIVE), but flag it in §6 so we decide whether
to retune before promotion.

### 1c. MLP-vs-LGBM comparison (decision [45])

```bash
python3 -c "import json; d=json.load(open('data/eval/batted_ball_comparison_v1.json')); \
print('prefer_for_production:', d.get('prefer_for_production')); \
print('aggregate:', d.get('aggregate'))"
# or open data/eval/batted_ball_comparison_v1.html
```

Record `prefer_for_production`. If it's `'lgbm'`, decision [45] (MLP as primary)
needs the conditional reversal — see [§5](#5-decision-45).

---

## 2. Schema-hash contract prerequisite (rule 7)

Registration hashes a `feature_pipeline.json` and pins it as the model's schema
hash (`RegistryService` bootstraps on first registration, then enforces match —
decision [67], rule 7). The batted-ball model needs its own contract; today
`contracts/` only has `feature_pipeline.json` (pre), `_post`, and `_toy`.

```bash
ls ../contracts/feature_pipeline_battedball.json 2>/dev/null \
  || echo "MISSING — create it before registering"
```

If missing, create `contracts/feature_pipeline_battedball.json` from the trained
metadata's feature ordering (the structure mirrors `feature_pipeline_toy.json`:
`feature_order`, per-column transforms, and a top-level `schema_hash: ""` that the
hasher zeroes + recomputes). Keep `feature_names` + `park_order` identical to
`artifacts/battedball_mlp_v1/metadata.json` or the bootstrap pins a hash the
serving pipeline can't reproduce. **Ping me if you want me to generate this
contract from the trained metadata** — it's a small laptop-side file I can write
once you paste the metadata's `feature_names` + `park_order`.

---

## 3. Register the model (SHADOW)

Registration goes through the admin API (`RegistryAdminController`), so the `api`
JVM must be running and able to read the artifact paths. On the desktop that's the
live `bullpen-api` unit reading `~/code/the-bullpen/training/artifacts/...`.

```bash
ADMIN="$THEBULLPEN_ADMIN_BASIC_AUTH"          # user:pass already in the unit env
BASE=http://localhost:8080                     # or https://api.thebullpen.net
ART=/home/alepic/code/the-bullpen/training/artifacts   # absolute, as the JVM sees it
```

**Rule 9 — register the LGBM baseline first** (no primary without its baseline):

```bash
curl -s -u "$ADMIN" -X POST "$BASE/v1/admin/registry/batted_ball_lgbm_baseline/register" \
  -H 'Content-Type: application/json' -d "{
    \"modelName\": \"batted_ball_lgbm_baseline\",
    \"version\": \"v1\",
    \"artifactPath\": \"$ART/batted_ball_lgbm_baseline/v1/model.txt\",
    \"metadataPath\": \"$ART/batted_ball_lgbm_baseline/v1/metadata.json\",
    \"featurePipelinePath\": \"$(pwd)/../contracts/feature_pipeline_battedball.json\",
    \"trainingDataWindow\": \"2015-2024 train, 2025 val\",
    \"createdBy\": \"2c-overnight\",
    \"notes\": \"LGBM baseline for the batted-ball role (rule 9 partner)\"
  }" | jq '{id,modelName,version,stage}'
```

**Then the primary MLP** (it lands in SHADOW by default — rule 6, never LIVE at
registration):

```bash
curl -s -u "$ADMIN" -X POST "$BASE/v1/admin/registry/battedball_outcome/register" \
  -H 'Content-Type: application/json' -d "{
    \"modelName\": \"battedball_outcome\",
    \"version\": \"v1\",
    \"artifactPath\": \"$ART/battedball_mlp_v1/model.onnx\",
    \"metadataPath\": \"$ART/battedball_mlp_v1/metadata.json\",
    \"featurePipelinePath\": \"$(pwd)/../contracts/feature_pipeline_battedball.json\",
    \"trainingDataWindow\": \"2015-2024 train, 2025 val\",
    \"createdBy\": \"2c-overnight\",
    \"notes\": \"shared-backbone MLP + 30 park heads + 30 isotonic calibrators\"
  }" | jq '{id,modelName,version,stage}'
```

A **schema-hash mismatch** (rule 7) returns a 4xx — that means the contract in §2
doesn't match what the model was trained against. Fix the contract, don't force
the registration.

---

## 4. Smoke-load + confirm SHADOW routing

```bash
# Registry rows present, MLP in SHADOW:
curl -s -u "$ADMIN" "$BASE/v1/admin/registry/battedball_outcome" \
  | jq '.[] | {id,version,stage,feature_schema_hash}'

# The api JVM loads the ONNX session without error (check the unit log):
journalctl -u bullpen-api --since "5 min ago" | grep -iE "battedball_outcome|ModelLoader|ONNX"

# Serving still works (toy or registered model, depending on routing):
curl -s -X POST "$BASE/v1/predict/batted-ball" -H 'Content-Type: application/json' \
  -d '{"launchSpeedMph":100,"launchAngleDeg":28,"releaseSpeedMph":95,"parkId":"NYY","stand":"R"}' | jq
```

The new model is a SHADOW candidate — predictions are logged, not user-visible,
until promoted via `/promote` (rule 6, human-gated). That promotion is a separate,
deliberate step (see the `promote-model` skill) and is **not** part of closing
Phase 2.

---

## 5. Decision [45]

If §1c reported `prefer_for_production: 'lgbm'`, the original decision [45] (MLP as
the batted-ball primary) is reversed by its own conditional-acceptance clause. Run
`/decide` (or ping me) to append the reversal to `docs/decisions.md`:

> `[N]` 2026-XX-XX — **Reverse decision [45]: LGBM, not the MLP, is the
> batted-ball primary** — the 2c.9 head-to-head set `prefer_for_production='lgbm'`
> (Brier {lgbm} < {mlp}); register the LGBM as the champion candidate instead.

If `prefer_for_production: 'mlp'`, decision [45] stands — nothing to record.

---

## 6. Report back (for laptop-side verification)

Paste these five back so I can cross-check + flip the phase-status:

1. **Sanity ρ** (§1a) and pass/fail.
2. **Per-park ECE post** mean + max (§1b).
3. **Aggregate test ECE** (§1b) — is it < 0.02?
4. **`prefer_for_production`** (§1c).
5. The two **registry rows** (`{id, version, stage, feature_schema_hash}`) from §4.

I'll then verify ECE < 0.02 against the eval artifact, confirm the schema-hash
pin, mark the Phase-2 exit criteria done in `docs/phase-status.json`, and (if
needed) record the decision-[45] reversal.
