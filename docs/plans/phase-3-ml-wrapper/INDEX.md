# Phase 3 — ML Systems Wrapper · INDEX

> Registry, A/B routing, drift detection, retraining triggers — the FAANG-grade signal.
> Weeks 18–22 · ~80–100 hours. See [`../../plan.md`](../../plan.md) §Phase 3.
>
> **Phase exit criterion**: Trigger retrain manually → new candidate registered with eval → promote through shadow → champion via API → traffic shifts visible in logs → old champion archived. Full lifecycle, end-to-end.
>
> **Soft cuts** (in priority order): 3. End of Wk 20 if behind: cut real-A/B routing, keep shadow only (~10 h). 4. End of Wk 22 if behind: cut automated drift retraining, keep drift measurement and manual retrains (~5 h).
>
> **Hard rule (Discipline 5)**: NEVER cut the registry. It's the spine; everything attaches.

---

## Cross-cutting docs to read alongside any leaf in this phase

- [`../00-MASTER.md`](../00-MASTER.md)
- [`../00-CONVENTIONS.md`](../00-CONVENTIONS.md)
- [`../00-OBSERVABILITY-STRATEGY.md`](../00-OBSERVABILITY-STRATEGY.md) — alert thresholds, drift-metric naming
- [`../00-RISK-REGISTER.md`](../00-RISK-REGISTER.md) — G2, G3, G7, G8, I8 directly addressed in this phase
- [`../../design.md`](../../design.md) §3

---

## Sub-tree

### Phase 3a — Model Registry → [`3a-registry/`](3a-registry/)

Weeks 18–19. The spine. Everything else attaches to it.

Leaf plans:

- `3a.1-registry-schema-and-flyway.md` — `model_versions`, `model_routing`, `experiment_results` tables
- `3a.2-registry-service-crud.md` — Spring service + repository, no controllers yet
- `3a.3-feature-schema-hash-enforcement.md` — bootstrap rule and ongoing enforcement (Risk Register G3)
- `3a.4-promotion-api-admin-endpoints.md` — `/v1/admin/registry/{model_name}/promote/{version_id}` etc.
- `3a.5-training-snapshot-storage.md` — Parquet location, retention (5 versions local + R2 archive — was B2; switched per [128]/ADR-0007), reconciliation job (Risk Register G2, G7)

### Phase 3b — A/B Routing → [`3b-ab-routing/`](3b-ab-routing/)

Weeks 19–20. Shadow mode is the default. Real A/B available but reserved.

Leaf plans:

- `3b.1-routing-config-schema.md` — leverages `model_routing` from 3a.1
- `3b.2-murmur3-game-id-bucketing.md` — deterministic, sticky, no client state (decision [71])
- `3b.3-shadow-mode-default.md` — both models run; only champion returned; both logged
- `3b.4-experiment-results-table-and-promotion-criteria.md` — pre-declared criteria enforced (decision [72])
- `3b.5-async-batched-prediction-logger.md` — full version (replaces Phase 1.7's primitive)

This is the **third soft-cut candidate**. If by end of Wk 20 we're behind, drop 3b.2 and keep shadow only.

### Phase 3c — Drift Detection → [`3c-drift/`](3c-drift/)

Weeks 20–21. Three drift types tracked separately (decision [74]).

Leaf plans:

- `3c.1-drift-metrics-schema-clickhouse.md` — `drift_metrics` table with TTL = 36 months (Risk Register G8)
- `3c.2-psi-feature-batch.md` — daily; PSI per feature; chi-squared for categoricals (decision [75])
- `3c.3-psi-prediction-batch.md` — daily; PSI on predictions vs. training holdout
- `3c.4-calibration-batch.md` — daily; Brier and calibration error on observed outcomes (decision [76])
- `3c.5-weekly-segment-batch.md` — Sunday night; per-segment metrics, long-window comparisons
- `3c.6-synthetic-drift-tests.md` — inject known shifts; verify detector fires (decision [64])
- `3c.7-discord-alerting-page-notice-logged.md` — page / notice / logged-only severity routing (decision [78])

### Phase 3d — Retraining Triggers → [`3d-retraining/`](3d-retraining/)

Weeks 21–22. Three triggers, one queue, one Python job (decision [79]).

Leaf plans:

- `3d.1-retraining-queue-schema.md` — `retraining_queue` SQLite table; idempotency via unique constraints
- `3d.2-three-triggers-scheduled-drift-manual.md` — monthly floor + drift-based ceiling (calibration > 1.5× sustained 7d) + manual button
- `3d.3-python-retrain-job.md` — services all three trigger types; `trigger_id` propagation (Risk Register I8); atomic registration (decision [44] — registers only, no promotion)
- `3d.4-systemd-timer-2-6-am-window.md` — hourly check during 2–6 AM ET; self-healing on collision (decision [19])

This is the **fourth soft-cut candidate**. If behind, keep manual retrains and skip the automated trigger paths.

---

## Cross-cutting work that lands in Phase 3

- **Async batched logger** (decision [30]) is finalized in 3b.5 with role enum, queue depth metric, drop counter.
- **Reconciliation job** for cross-DB integrity (Risk Register G2) lands in 3a.5.
- **Snapshot retention to R2** (Risk Register G7) lands in 3a.5. (Was B2; switched per [128]/ADR-0007.)
- **TTL on `prediction_log`** (Risk Register G8) lands in 3b.5; on `drift_metrics` in 3c.1.
- **`trigger_id` propagation** (Risk Register I8) lands across 3d.1–3d.3.

---

## Phase 3 exit gate (end-to-end lifecycle test)

```bash
# 1. Trigger a manual retrain for an existing model:
curl -u admin:$PW POST /v1/admin/retrain -d '{"model_name":"pitch_outcome_pre"}'

# 2. Watch the queue process:
sqlite3 /var/lib/thebullpen/registry.sqlite "SELECT * FROM retraining_queue ORDER BY id DESC LIMIT 1;"

# 3. New model_version row appears as 'candidate':
sqlite3 /var/lib/thebullpen/registry.sqlite "SELECT * FROM model_versions ORDER BY id DESC LIMIT 1;"

# 4. eval/ artifact exists with metrics.json:
ls /var/lib/thebullpen/models/pitch_outcome_pre/v<N>/eval/

# 5. Promote to shadow via API:
curl -u admin:$PW POST /v1/admin/registry/pitch_outcome_pre/promote/<id> -d '{"target_stage":"shadow"}'

# 6. Run a few predictions; observe both champion + shadow rows in prediction_log:
clickhouse-client -q "SELECT model_version_id, role, count() FROM prediction_log WHERE model_name='pitch_outcome_pre' AND request_at > now() - INTERVAL 5 MINUTE GROUP BY 1,2"

# 7. Pre-declare criteria, write experiment_results row showing pass:
sqlite3 /var/lib/thebullpen/registry.sqlite "INSERT INTO experiment_results ... ;"

# 8. Promote to champion:
curl -u admin:$PW POST /v1/admin/registry/pitch_outcome_pre/promote/<id> -d '{"target_stage":"champion"}'

# 9. Old champion now has stage='archived':
sqlite3 /var/lib/thebullpen/registry.sqlite "SELECT id, version, stage FROM model_versions WHERE model_name='pitch_outcome_pre' ORDER BY id DESC;"

# 10. Force a synthetic drift event; verify Discord alert fires:
uv run python -m thebullpen.drift.fire_synthetic --psi=0.4 --feature=count_balls
```

If all 10 pass: Phase 3 done. Move to Phase 4 (frontend).
