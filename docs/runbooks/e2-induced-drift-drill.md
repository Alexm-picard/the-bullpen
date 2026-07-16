# Runbook - E-2 induced-drift drill (controlled injection)

**Owner:** alex · **Last reviewed:** 2026-07-15 · **Phase:** 6 (drift postmortem) · **Decision:** [175]

> **Scope:** deliberately inject a known distribution shift into the live
> `prediction_log`, let the real 2 AM drift chain detect + alert on it, verify,
> then clean up - producing the Phase-6 drift-postmortem centerpiece honestly
> labelled as synthetic. This is the live-path successor to the in-test
> `DriftInductionDrillIT` and the 2026-05-30 induced-drift drill
> (`docs/postmortems/`), which proved the mechanism in an ephemeral container.
> This runbook drives the SAME proven shift design against production.
>
> **Why it exists:** no natural drift event may fire before season end. [175]
> guarantees the postmortem exists regardless by inducing a controlled one. The
> injector reuses the real serving row-writer and the champion's own training
> baseline, so the induced rows are byte-identical to organic traffic except for
> their `drill:` correlation-id prefix and the shifted feature.
>
> **Boundary (ADR-0006):** the Mac authors this tool; the box runs it. Every
> command below executes on the desktop.

---

## What this drill can and cannot induce (read first)

The injector writes synthetic `battedball_outcome` rows into `prediction_log`
with one continuous feature shifted +N-sigma off the champion's training
baseline. It therefore trips the **feature-PSI** half of the drift chain:

`PsiFeatureJob` (24h window) -> `PSI_FEATURE` row in `drift_metrics` ->
`DriftAlertEvaluator` feature-PSI **NOTICE** -> Discord + `alert_history`.

It does **NOT** induce a `CALIBRATION_ERROR` **PAGE**. Calibration drift is
computed by `CalibrationJob` over a settled-truth join
(`ClickHouseTruthJoinedPredictionFetcher`), which is **pitch-family only**
(`game_id IS NOT NULL`, `{"probabilities"}` payload, pitch 5-class vocab). The
served `battedball_outcome` champion has no live truth-join, and the All-Star
break has no live pitch traffic to join against. So the over-confident-output /
ECE leg of the original `DriftInductionDrillIT` design is **not reproducible on
the live battedball path** and is deliberately out of scope for this drill.

If a full feature-PSI-**and**-calibration induction is wanted later, it needs a
pitch-family variant run during live games (post-season follow-up, tracked
separately). The feature-PSI NOTICE is the primary, live-proven, break-window
signal and is sufficient for the [175] postmortem.

---

## Prerequisites on the desktop

- Repo synced to `origin/main` past the injector PR, and the API redeployed via
  `./deploy.sh` so the new admin endpoint is present.
- A registered **active CHAMPION** for the target model
  (`battedball_outcome` by default) whose `metadata.json` carries a
  `feature_distributions` baseline for the shift feature. Verify the baseline is
  present (else the injector refuses, loudly, listing the available keys - run
  the backfill CLI from `feature-drift-investigation.md` first):
  ```bash
  # the champion bundle's metadata.json should have feature_distributions.launchSpeedMph
  jq '.feature_distributions | keys' <champion bundle dir>/metadata.json
  ```
- Off a live-game window (rule 3). The All-Star break is the intended window.
- Admin Basic-auth credentials (the `/v1/admin/**` matcher requires role ADMIN).

---

## Sequence

### 1. Arm the drift tag on BOTH units + enable the injector, then restart both

> **Two JVMs, one box env - arm both.** The tag is consumed in two different
> processes: the **api** process runs the injector (whose refusal guard reads
> `bullpen.drift.tag`), and the **worker** process runs `PsiFeatureJob` ->
> `DriftMetricsRepository`, which is where the tag is actually stamped onto the
> `drift_metrics` rows (the [175] choke point). Both are bind-at-startup
> (`@Value` / `@ConditionalOnProperty`), so arm the tag on both and restart both.
> If the worker is NOT armed when the 2 AM job runs, the induced metric rows land
> `tag=''` and look organic - the exact inverse of the hygiene guard.

Set the env in `/etc/default/bullpen` (the shared EnvironmentFile both units
read). `BULLPEN_DRIFT_INJECT_ENABLED` only affects the api (the injector bean),
but the tag must reach both:

```
BULLPEN_DRIFT_TAG=induced-drill-2026-07
BULLPEN_DRIFT_INJECT_ENABLED=true
```

```bash
sudo systemctl restart bullpen-api bullpen-worker
```

`BULLPEN_DRIFT_TAG` is the [175] choke point: every drift job's output is now
tagged `induced-drill-2026-07` at `DriftMetricsRepository`, so the induced
metric rows are excludable from organic baselines via `WHERE tag = ''`. Both
units log a self-announcing WARN at startup while the tag is set (so a lingering
tag after the drill is caught). Confirm the tag is live on both:

```bash
sudo systemctl show -p Environment bullpen-api bullpen-worker | grep -o 'BULLPEN_DRIFT_TAG=[^ ]*'
```

**Keep the tag armed on both units until step 6 (cleanup) completes** - not just
until detection. Any 2 AM job that runs over still-present `drill:` rows while
the tag is disarmed would mis-tag them as organic.

### 2. Inject

A bare POST uses the drill defaults (model `battedball_outcome`, N=5000, +1-sigma on
`launchSpeedMph`, spread over the last 20h so the PSI 24h window captures it):

```bash
curl -sS -u admin:<pw> -X POST https://thebullpen.net/v1/admin/drift/induce | jq
```

Override any field as needed:

```bash
curl -sS -u admin:<pw> -X POST https://thebullpen.net/v1/admin/drift/induce \
  -H 'Content-Type: application/json' \
  -d '{"n":8000,"shiftSigmas":1.0,"shiftFeature":"launchSpeedMph","lookbackHours":20}' | jq
```

The response echoes `baselineMean` / `baselineStd` / `shiftedMean` (the
self-calibrated shift) and the `[windowStart, windowEnd]` the rows span. If it
refuses with `not a continuous baseline feature`, pass `--shiftFeature` matching
a key from the listed available continuous features (the champion's baseline
keys). If it refuses naming `BULLPEN_DRIFT_TAG`, step 1's restart did not pick up
the tag.

Sanity-check the rows landed and are all `drill:`-prefixed:

```bash
docker compose -f infra/docker-compose.yml exec clickhouse clickhouse-client -q \
  "SELECT count(), countIf(correlation_id LIKE 'drill:%') FROM prediction_log
   WHERE request_at > now() - INTERVAL 24 HOUR"
```

Both counts should match (every injected row is prefixed; organic break traffic
is ~zero).

### 3. Let the 2 AM ET cron detect it (or force it)

Wait for the next `PsiFeatureJob` run (2 AM ET, worker profile). To avoid an
overnight wait, force one cycle from the worker (off-window):

```bash
# Optional: force an immediate PSI + alert cycle rather than waiting for 02:00 ET.
# (runOnce is @VisibleForTesting; the operator path is simply to wait for the cron.)
```

### 4. Verify detection + alert

```bash
# PSI_FEATURE row for the shifted feature, tagged with the drill tag, past 0.25:
docker compose -f infra/docker-compose.yml exec clickhouse clickhouse-client -q \
  "SELECT toDate(computed_at), feature_or_segment, metric_value, sample_size, tag
   FROM drift_metrics
   WHERE model_name='battedball_outcome' AND metric_type='psi_feature'
     AND feature_or_segment='launchSpeedMph'
   ORDER BY computed_at DESC LIMIT 5"
```

Expect `metric_value > 0.25` and `tag = 'induced-drill-2026-07'`. Confirm the
Discord feature-drift NOTICE fired and an `alert_history` row exists. Follow
`feature-drift-investigation.md` as if this were a real NOTICE - that is the
drill: exercise the human triage path, not just the machine detection.

### 5. Clean up the synthetic rows (BEFORE disarming)

Order matters: **clean up while the tag is still armed on both units.** If you
disarm first, any 2 AM job that fires before cleanup would compute PSI over the
still-present `drill:` rows and write those `drift_metrics` rows `tag=''`
(organic-looking) - the inverse of the [175] guard.

Per the hard rule "never touch live ClickHouse without a backup snapshot first,"
confirm a recent snapshot exists before the DELETE. The daily 03:00 snapshot
normally covers this; force one if in doubt:

```bash
# Optional but rule-mandated if no recent snapshot: force a ClickHouse snapshot first.
sudo /opt/bullpen/infra/backup/clickhouse-snapshot.sh   # or confirm the 03:00 timer ran

curl -sS -u admin:<pw> -X DELETE https://thebullpen.net/v1/admin/drift/synthetic | jq
```

The DELETE issues an async ClickHouse mutation
(`ALTER TABLE prediction_log DELETE WHERE correlation_id LIKE 'drill:%'`) and
returns the matched count. It is tightly scoped - only `drill:`-prefixed rows can
ever match, so the blast radius is bounded to the injected rows. It settles in
seconds on a small window. Confirm:

```bash
docker compose -f infra/docker-compose.yml exec clickhouse clickhouse-client -q \
  "SELECT count() FROM prediction_log WHERE correlation_id LIKE 'drill:%'"
```

Expect `0`. The tagged `drift_metrics` rows are left in place (they ARE the
drill evidence) and stay excluded from organic baselines by `WHERE tag=''`; drop
them separately only if you want a clean metrics table.

### 6. Disarm both units + restart

Only after cleanup confirms zero drill rows: remove both lines from
`/etc/default/bullpen` (or set them empty) and restart both units so the injector
bean disappears and organic rows go back to untagged:

```bash
# /etc/default/bullpen: delete BULLPEN_DRIFT_TAG + BULLPEN_DRIFT_INJECT_ENABLED
sudo systemctl restart bullpen-api bullpen-worker
```

The startup WARN about a set drift tag should be gone on both. A lingering
`BULLPEN_DRIFT_TAG` would silently tag every ORGANIC row and exclude it from
`WHERE tag=''` baselines - the inverse hazard - so confirm it is cleared on both.

### 7. Write the postmortem (E-3)

With the induced NOTICE captured and triaged, write
`docs/postmortems/{date}_induced-drift-drill.md` documenting: the injected shift
(feature, sigma, N, window), the detection latency (inject -> NOTICE), the triage
walk-through, and - honestly and prominently - that the drift was **synthetic
and induced**, feature-PSI only, per [175]. Cross-link ADR-0013 and this runbook.

---

## Safety properties (why this is reversible)

- **Tagged:** every induced `drift_metrics` row carries `BULLPEN_DRIFT_TAG`;
  organic baselines are `WHERE tag=''`, so induced metrics never pollute them.
- **Prefixed:** every induced `prediction_log` row's `correlation_id` starts
  `drill:`, so the cleanup DELETE is exact and total.
- **Disabled by default:** the injector bean only exists while
  `bullpen.drift.inject.enabled=true`; when unset the endpoint 404s.
- **Fail-loud:** the injector refuses unless the drift tag is armed, a champion
  with a baseline exists, and the shift feature is a continuous baseline key.

## Related

- `feature-drift-investigation.md` - the triage path this drill exercises
- `calibration-drift-investigation.md` - the PAGE path (pitch-family; NOT induced here)
- `docs/adr/0013-*.md` - scale-ready single-box (drift chain idempotency)
- Decision [175] - the induced-drill guarantee + tagging hygiene
- `DriftInjectionService` / `DriftInjectionServiceIT` - the injector + its CH round-trip proof
