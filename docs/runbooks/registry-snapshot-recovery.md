# Runbook — Recovering a registry snapshot from R2

**Owner:** alex · **Last reviewed:** 2026-05-24 · **Phase:** 3a.5

This runbook explains how to restore an archived model snapshot from
Cloudflare R2 back to local disk on the prod host (or to the MacBook
for offline debugging). It applies whenever you see one of:

- `RegistryService.findById(id).artifactPath()` returns an `s3://...` URI
  but the live serving path needs the model loaded locally.
- A drift incident requires inspecting an archived snapshot's
  `feature_pipeline.json` to confirm the training-time schema.
- Disk filled aggressively and the local-retention sweep archived
  something that turns out to be needed (rare but possible after a
  surprise champion-rollback).

## Pre-checks

1. **You are on the right host.** Restore writes to
   `${BULLPEN_MODELS_DIR}` (default `/var/lib/thebullpen/models`).
   Running this on the MacBook against a prod-shaped path is fine for
   inspection, harmful for serving. Per ADR-0006, this should normally
   run on the desktop only — the MacBook path is for "what was in this
   snapshot" forensics, never for "patch live serving."

2. **The version ID exists.** Query the registry via the read-only Ops
   endpoint:

   ```bash
   curl -s http://localhost:8080/v1/ops/registry/<model_name> | jq '.[] | {id, version, stage, artifact_path}'
   ```

   Find the row you want to restore. Note its `id` and confirm
   `artifact_path` starts with `s3://` — if it already points at a
   local path, this runbook does not apply.

3. **R2 credentials are available.** The Spring process holds them via
   `S3_ACCESS_KEY_ID` + `S3_SECRET_ACCESS_KEY` from the systemd
   `EnvironmentFile` — you don't need to handle them yourself. Just
   confirm the bullpen-api service is running:

   ```bash
   systemctl status bullpen-api
   ```

## Restore via the registry service

Call the service directly via the admin endpoint (HTTP-Basic credentials
required, leaf 3a.4):

```bash
curl -u "$THEBULLPEN_ADMIN_BASIC_AUTH" -X POST \
  http://localhost:8080/v1/admin/registry/<model_name>/restore/<version_id> \
  -H "Content-Type: application/json"
```

> **Note**: as of 3a.5 the restore endpoint is wired via
> `RegistryService.restoreVersion(versionId)` but the HTTP route is
> intentionally left for the next leaf (3b) which is when an admin UI
> will need it. For now, restore is exercised via a one-shot JVM tool:
>
> ```bash
> cd /opt/bullpen
> sudo -u bullpen java -cp app.jar \
>   -Dloader.main=net.thebullpen.baseball.registry.RestoreVersionMain \
>   <version_id>
> ```
>
> Or, if you prefer to drive it programmatically, log into the JVM via
> `bin/spring-shell` (once that's wired) and call
> `service.restoreVersion(<id>)` directly.

## What restore does

1. Reads `model_versions.artifact_path` for the row — must be `s3://`.
2. Downloads every key under
   `models-archive/<model_name>/<version>/` from R2 to
   `${BULLPEN_MODELS_DIR}/<model_name>/<version>/`.
3. Updates `model_versions.artifact_path` + `metadata_path` to point at
   the local copies.
4. Bumps `updated_at`.

The corresponding S3 keys are **not** deleted — restore is reversible
by the next retention sweep, which will re-archive the row if it's
still CANDIDATE / ARCHIVED and beyond `bullpen.snapshot.keep-locally`.

## Verifying restore

```bash
# 1. Local files present
ls -la /var/lib/thebullpen/models/<model_name>/<version>/
# Expect: model.onnx, metadata.json, feature_pipeline.json (and optionally
# calibrator.json / training_data.parquet depending on what was archived).

# 2. Registry row updated
curl -s http://localhost:8080/v1/ops/registry/<model_name>/<version_id> \
  | jq '{stage, artifact_path, metadata_path, updated_at}'
# Expect: artifact_path now a local path (not s3://), updated_at fresh.

# 3. Load smoke-test (only if you intend to serve from it):
sudo -u bullpen java -cp /opt/bullpen/app.jar \
  -Dloader.main=net.thebullpen.baseball.inference.ModelLoadSmokeMain \
  /var/lib/thebullpen/models/<model_name>/<version>/model.onnx
# Expect: prints input + output shapes; exits 0.
```

## Failure modes

| Symptom                                                      | Probable cause                                                                                                                      | Fix                                                                                                                                                                                                                                      |
| ------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SnapshotStorageException: S3 object not found`              | The S3 keys under the model+version prefix were deleted (R2 lifecycle policy mis-configured, or someone manually `rclone purge`-d). | Check R2 web console for the prefix. If gone, the snapshot is unrecoverable — register a fresh version from the Python training artifacts.                                                                                               |
| `IllegalStateException: bullpen.s3.endpoint-url must be set` | Spring booted in dev mode without S3 configured.                                                                                    | This shouldn't happen on the prod host (systemd EnvironmentFile sets it). On the MacBook for forensics, set `S3_ENDPOINT_URL=https://<account>.r2.cloudflarestorage.com` plus the credentials before invoking.                           |
| `requires an R2 client (S3_ENDPOINT_URL unset)`              | Same as above — `R2ArchiveClient` bean didn't materialize.                                                                          | Same fix.                                                                                                                                                                                                                                |
| Restore succeeds but model fails to load at runtime          | Schema hash mismatch — the production feature pipeline drifted while this snapshot was archived.                                    | Either roll the production pipeline back to the snapshot's `schema_hash`, or accept that this snapshot is no longer servable. The schema-hash check at registration (rule 7, 3a.3) blocks promotion of stale-schema snapshots by design. |
| `updatePaths` returns 0                                      | The `versionId` you passed doesn't exist in `model_versions`.                                                                       | Re-query via `/v1/ops/registry/<name>` to find the right id.                                                                                                                                                                             |

## After-action

- **Update `docs/decisions.md`** if the restore was driven by a real
  incident — add a numbered entry referencing the postmortem.
- **Write a `docs/postmortems/<date>_<name>.md` entry** if the restore
  was triggered by an outage or data-loss event.
- **Bump the snapshot retention** (`bullpen.snapshot.keep-locally`)
  if you hit this runbook twice for the same model — a higher local
  retention reduces the chance you need restore in the first place,
  at the cost of disk usage. Default is 5; bumping to 10 is safe at
  the project's scale.

## Related

- [ADR-0007 — S3-compatible storage](../adr/0007-s3-compatible-storage.md)
- [Leaf 3a.5 — Training snapshot storage](../plans/phase-3-ml-wrapper/3a-registry/3a.5-training-snapshot-storage.md)
- `SnapshotStorage.restoreVersion(...)` source —
  `backend/src/main/java/net/thebullpen/baseball/registry/SnapshotStorage.java`
