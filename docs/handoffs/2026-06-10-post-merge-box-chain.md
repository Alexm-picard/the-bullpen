# Box handoff - 2026-06-10 post-merge chain

**To:** the desktop prod-box Claude agent team.
**From:** the Mac authoring session (2026-06-10).
**Read first:** CLAUDE.md, ADR-0006 (dev/prod boundary), decision [154] + ADR-0011, and the runbooks
referenced below. This brief ORCHESTRATES those runbooks; it does not replace them.

## Your boundary (ADR-0006)

You OPERATE; the Mac AUTHORS. You run `./deploy.sh`, box-only validation, backfills, and operation.
You do NOT edit code on the box - a real fix is "open the laptop, fix, push, redeploy". SSH/observe
is read-only. The desktop working copy is owned by `deploy.sh` alone.

## Hard gates (do not relax)

- **No deploys during live games** (rule 3, evenings Apr-Oct).
- **No live flip until WS1 (merged) + WS4 (validated on the box, step 2) + C-3 (step 5) are all
  green.** WS1 + WS4 code is merged; WS4 still needs box validation; C-3 is yours to run.
- Snapshot before any destructive ClickHouse op (rule, enforced by the block-destructive-ch hook).

## What the Mac merged this session (and what it means for you)

| PR  | Change                                                 | Box implication                                                                                                                                                                          |
| --- | ------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| #30 | decision [154] + ADR-0011                              | The live flip is **ingest-only**: no `pitch_outcome_pre` champion is promoted, so live serves NO user-visible pitch prediction (the verified degrade path). This is expected, not a bug. |
| #31 | WS1 live-poller robustness                             | Per-game tick isolation, failed-key dedup, null-matchup skip, `ModelUnavailable -> 503/degrade`. Exercised in the C-3 dry-run.                                                           |
| #32 | WS2-i drift watches SHADOW + DiscordNotifier real POST | Drift jobs now iterate champion+shadow; DiscordNotifier POSTs when `BULLPEN_DISCORD_WEBHOOK` is set.                                                                                     |
| #34 | WS4 watchdog stack                                     | node-exporter + Alertmanager + Prometheus rules + worker-:8081 deploy smoke + snapshot dead-man. **Needs box validation (step 2).**                                                      |
| #35 | WS3 DP2 PitcherFormRefreshJob                          | Nightly 02:40 ET job fills `pitcher_form_current` from `pitches`. Run once on first deploy (step 4).                                                                                     |
| #37 | WS2-ii real PredictionDistributionFetcher              | Shadows now produce real PSI rows once predictions are logged.                                                                                                                           |
| #38 | WS6 hygiene                                            | `install.sh` now installs the snapshot timer (step 2); `registry.sqlite` untracked (Flyway recreates).                                                                                   |

## The ordered chain

### C-0. Deploy main, off-window

Use the `deploy-safely` skill (wraps `./deploy.sh` with the live-game-window check + smoke). The
new JAR carries WS1/WS2-i/WS3/WS2-ii. The deploy now smokes the **worker on :8081** too (WS4) -
both `bullpen-api` and `bullpen-worker` must go green or it rolls back. Confirm the deployed SHA
matches `origin/main`.

### C-1. Prod-side env + error tracking

- `/etc/default/bullpen` (chmod 600): confirm `BULLPEN_REGISTRY_DB=/opt/bullpen/data/registry.sqlite`,
  `BULLPEN_DISCORD_WEBHOOK=...`. Add `BULLPEN_HC_PING_URL=...` (step 2) and `SENTRY_DSN=...` here.
- GlitchTip bring-up + `SENTRY_DSN` (api) per `docs/runbooks/error-tracking.md` (ADR-0008, P4).

### C-2. Box-validate + bring up the WS4 watchdog stack (the Mac could NOT run these)

Follow `docs/runbooks/monitoring-setup.md`. In order:

1. `promtool check config infra/prometheus/prometheus.yml`
2. `promtool check rules infra/prometheus/rules/bullpen-alerts.yml`
3. `amtool check-config infra/alertmanager/alertmanager.yml`
4. `docker compose -f infra/docker-compose.yml --profile monitoring config >/dev/null`
5. Create `infra/alertmanager/secrets/discord_url` from the `.example` (the RAW Discord webhook
   URL, NO `/slack` suffix - the Slack-compat shim 400s; the config uses the native
   `discord_configs` receiver as of the 2026-06-11 fix).
6. `docker compose -f infra/docker-compose.yml --profile monitoring up -d`.
7. Re-run `infra/systemd/install.sh` to install the snapshot timer (D1, now handled); verify
   `systemctl status bullpen-snapshot.timer` and `systemctl list-timers | grep snapshot`.
8. Set `BULLPEN_HC_PING_URL` in `/etc/default/bullpen`; create the Healthchecks.io check (period 1d,
   grace ~2h).
9. **VERIFY end-to-end:** `systemctl stop bullpen-worker`, wait >2m, confirm a `WorkerDown` alert
   reaches Discord; `systemctl start bullpen-worker`, confirm a resolve. **WS4 is green only after
   this fires.**

### C-3. Fixture-replay dry-run (THE FLIP GATE) - do NOT touch the real MLB API yet

Per `docs/runbooks/live-data-setup.md`. Point ingest at the replay server
(`infra/live-replay/replay_server.py`): set `BULLPEN_INGEST_LIVE_BASE_URL=<replay url>` and
`BULLPEN_INGEST_LIVE_ENABLED=true`, restart `bullpen-worker`. Gates (ALL must hold):

- `pitches_live` row count increases over the replay;
- `prediction_log` keyed rows are about-distinct (no per-poll duplication - WS1's dedup);
- every fixture row carries `game_id >= 900000000` (the replay sentinel - real gamePks never reach it);
- `bullpen-worker` errors 0, restarts 0 over the whole replay (WS1 per-game isolation holds).
- Inject a deliberately-broken fixture game and confirm the tick keeps polling the OTHER games
  (WS1 C1). Then revert.
- Afterward, purge the sentinel rows (`game_id >= 900000000`) from `pitches_live` /
  `prediction_log` / `live_game_status` **after a snapshot** - drift/eval/training must never see
  replay data. (Check `live_game_status` UNFILTERED by date: pre-44a4192 replay rows keyed under
  the fixture's original date, not today.)

### C-4. Flip live, OFF-WINDOW

Only after WS1 (merged) + WS4 (C-2 step 9 green) + C-3 (green). Set
`BULLPEN_INGEST_LIVE_BASE_URL=https://statsapi.mlb.com` (or unset to use the default) and
`BULLPEN_INGEST_LIVE_ENABLED=true` in `/etc/default/bullpen`, restart `bullpen-worker`, in a
no-live-game window. Stage the abort one-liner first:
`sudoedit /etc/default/bullpen` -> `BULLPEN_INGEST_LIVE_ENABLED=false` -> `systemctl restart bullpen-worker`.
Remember [154]: ingest-only, no pitch prediction served.

### C-5. Operate (Step 6)

Watch the first real game end-to-end: schedule discovery -> per-game poll -> `pitches_live` writes ->
truth-join reconciliation as pitches land. This starts the drift watch and the season's first
postmortem material.

## Backfills (run after C-0; not a flip gate)

- **pitcher_form_current:** trigger `PitcherFormRefreshJob` once (or wait for the 02:40 ET nightly).
  Verify `SELECT count(DISTINCT pitcher_id) FROM pitcher_form_current FINAL` is sane (~active
  pitchers in the last 28d). Until this runs, live pitch form serves NaN (documented, [143]).
- **players (DP3): MERGED (PR #39, main `1e3eebb`).** On the C-0 deploy the worker self-backfills
  `players` at startup when the table is empty (seasons 2015..current, off the smoke path; weekly
  Monday 03:40 ET re-pull thereafter; `BULLPEN_INGEST_PLAYERS_ENABLED` defaults true). Verify with
  `SELECT count(DISTINCT id) FROM players FINAL` (expect ~4-6k). NOT a flip gate.

## Separate track (NOT a flip gate): dsla rebuild + pitch retrain

PR-1's dsla epoch fix means `days_since_last_appearance` is clean only after a `features` rebuild,
and both already-trained pitch heads consumed the poisoned feature. **Before ANY pitch promotion**
(not before the ingest flip):

1. Rebuild the `features` table (test_year-2 TE window + the dsla guard). The CH-gated
   `test_features_table_dsla_bounded` (reads `FROM features FINAL`) must go green (it red-bars the
   current poisoned table by design - run it with `BULLPEN_REQUIRE_CH=1`).
2. Retrain PRE + LR on clean dsla -> export (`export_lr_onnx` / `export_pre_onnx`) ->
   `register_pitch --from-artifacts` -> register SHADOW.
   Until then, pitch stays champion-less ([154]); pitch-prediction drift + the still-stubbed
   TruthJoinedPredictionFetcher (calibration drift) stay dataless - that is correct, not a gap.

## Still-deferred / dataless (FYI, not your job now)

- Real `TruthJoinedPredictionFetcher` (calibration drift) - dataless until a live pitch champion exists.
- WS3 intra-day form upsert + wiring `LivePitchPredictor` to READ `pitcher_form_current`.
- FE-H1 (numeric gamePk hrefs) - blocked on wiring the homepage slate to live data.

## Reference

- Runbooks: `desktop-box-operations.md`, `monitoring-setup.md`, `live-data-setup.md`, `error-tracking.md`.
- Decisions: [154] (ingest-only/champion-less), ADR-0011, ADR-0006, ADR-0007 (R2/MinIO), rule 3, rule 8.
- Issue #1 (live poller).
