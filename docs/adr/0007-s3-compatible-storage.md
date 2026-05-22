# ADR-0007: Object storage via S3-compatible endpoint (MinIO offline, R2 in prod)

- **Status**: Accepted
- **Date**: 2026-05-21
- **Deciders**: alex
- **Related**: `decisions.md` entries [13] [28] [68] [127] [128], ADR-0006, `plan.md` Phase 0, `design.md` §6 §8

## Context

Two locked decisions already imply object storage:

- Decision [28] defines the Python → Java contract as four files per
  registered model: ONNX weights, JSON metadata, `feature_pipeline.json`,
  and a Parquet snapshot of the training data. Decision [68] elaborates:
  full Parquet snapshots, not hashed-and-windowed pulls, because MLB
  historical data is mutable and bitwise reproducibility matters. These
  artifacts are large (Parquet snapshots will be multi-GB by the time
  the historical backfill is registered), immutable once written, and
  read by both training (next iteration) and inference (audit trail).
- Decision [13] specified the backup path: `clickhouse-backup` → `rclone`
  → object storage (originally Backblaze B2, reversed to Cloudflare R2
  by decision [128] before any backup code was written against B2),
  with 7-day local / 4-week weekly / 12-month monthly retention. The
  backup target is already object storage.

Both workloads share the same access pattern: write-once / read-many,
keyed by path, no fine-grained mutation. Filesystem-shaped storage would
work, but the backup leg already requires an S3 client (rclone speaks S3
natively to R2). Introducing a second abstraction for model artifacts
would double the surface area for the same operational need.

ADR-0006 establishes the MacBook as the dev environment. The MacBook is
frequently offline (travel, weak Wi-Fi, cafes, planes), and training
iterations during those windows still need access to a representative
sample of the data, the registered models, and the feature pipeline
snapshot. Pulling multi-GB blobs over a hotspot on every script run is
not acceptable. The natural answer is a local mirror, but the _client
code_ should not branch on whether it's reading from R2 or a local
mirror — that branching is exactly the kind of dev/prod skew that
decision [67] (feature schema hashing) and ADR-0006 (no in-place edits)
exist to prevent.

S3 is the only object-storage API that has a mature self-hostable
implementation (MinIO) with the same wire protocol as multiple hosted
services (Cloudflare R2, Backblaze B2, AWS S3, and others). Pointing the
same client at a different endpoint is a one-env-var change — so the
vendor itself is interchangeable later if R2 ever stops being the right
fit.

## Decision

All object-storage access in the project uses an **S3-compatible client**
(`boto3` in Python, `software.amazon.awssdk.s3` in Java), parameterized
by a single environment-specific variable `S3_ENDPOINT_URL`:

- **Prod (desktop, online):**
  `S3_ENDPOINT_URL=https://<account-id>.r2.cloudflarestorage.com`
  Cloudflare R2. Read-write credentials. Backups land here; model
  artifacts also land here once registered.
- **Dev (MacBook, online):**
  Same R2 endpoint as prod, with read-only credentials. Pulls samples
  and registered models for local iteration.
- **Dev (MacBook, offline):**
  `S3_ENDPOINT_URL=http://localhost:9000`
  Local MinIO instance serving from `/Volumes/MyDrive/bullpen-data` on a
  portable USB drive. MinIO is launched via `make minio-up`; the drive
  is the user's existing data drive, not the BULLPEN_BAK backup target.

No code anywhere reads from `file://` paths for object data. No code
hard-codes a bucket host. The only environment-specific knob is
`S3_ENDPOINT_URL` plus the credentials pair.

Sync from R2 to the portable drive uses `rclone sync` (manual, run when
the user remembers to before traveling). Bucket layout in `bullpen-prod`:

- `samples/dev/` — small stratified samples (≤ 500 MB) used by
  `make train-sample`. Always mirrored offline; sync is mandatory before
  travel.
- `snapshots/v{N}/` — registered model snapshots (Parquet + ONNX + JSON).
  Mirrored selectively; default mirror is the current LIVE champion
  plus the most recent SHADOW candidate.
- `raw/` — full historical Statcast in canonical form. Prod + R2 only;
  not mirrored. ~200 GB, not a useful offline payload.

Each snapshot directory ships a `manifest.json` carrying the feature
schema hash (rule 7), the row count, and per-file SHA-256s. Clients
validate the manifest before consuming the snapshot and fail loud on
mismatch — local mirror staleness becomes a loud error, not a silent
training-on-old-data bug.

## Consequences

**Easier:**

- One code path for object access across prod, online dev, and offline
  dev. The S3 client is the integration test surface; pointing it at
  MinIO is a CI-realistic test target.
- Offline training iteration works without network. Important during
  travel and during MLB-Stats-API outages.
- Vendor consolidation on Cloudflare (already DNS + Tunnel, planned
  monitor) — fewer credentials, fewer billing surfaces, fewer dashboards.
- R2 → MinIO migration would be a one-env-var change, should R2 ever
  go away or get expensive. Not a planned move, but a recoverable one.
- The backup path already needs an S3 client; reusing it for model
  artifacts amortizes the operational learning (credentials handling,
  retry policy, lifecycle rules).
- Cost: R2 at portfolio scale (≤ 10 GB storage, ≤ 1M Class A ops, ≤ 10M
  Class B ops per month) lands inside the free tier — and R2's $0 egress
  policy removes the largest unknown in the cost projection.

**Harder:**

- Adds MinIO as a dev-time dependency on the MacBook. Cost: a ~50 MB
  Go binary launched on demand, no daemon, no service exposed beyond
  `localhost:9000`. Negligible.
- The user has to remember to `rclone sync` before going offline.
  Mitigation: a `make sync-mirror` target that runs the sync and prints
  the on-drive timestamp; checking the timestamp before a flight is the
  ritual.
- Slight latency tax on local reads — going through HTTP-to-MinIO
  instead of raw filesystem reads. Sub-millisecond at portable-drive
  speeds; irrelevant for the multi-second-per-iteration training loop.

**New failure modes:**

- Schema drift between local sample and prod data. A model trained on
  the stale local sample could be subtly miscalibrated relative to
  current prod data. Mitigation: the manifest's schema hash is checked
  on load; mismatch is a hard fail (rule 7, decision [67]). Local
  training that fails the schema check forces a sync before retry.
- MinIO process left running and exposing the data directory after the
  user closes the terminal. Mitigation: MinIO binds to `127.0.0.1:9000`
  only, never `0.0.0.0`. The `make minio-down` target stops it; the
  Makefile recommends running it inside a `trap` so killing the shell
  kills MinIO.
- Cloudflare-vendor concentration risk. R2 + Tunnel + DNS all live in
  one account; a billing dispute or account suspension takes the prod
  site and the storage down together. Mitigation: the S3-compatible
  abstraction means failover to AWS S3 or B2 is a one-env-var change
  on the prod side, plus a one-day data re-upload from the local Layer
  2 USB backup. Accept the risk for vendor-consolidation simplicity.

**Locked into:**

- Any future storage need with non-S3 semantics (transactional metadata,
  fine-grained mutation, blob streaming with range writes) is a
  re-decision via `/decide`, not a quiet bypass. The discipline this
  ADR enforces is "one storage abstraction, no second class."

## Alternatives Considered

### Alternative A: Filesystem paths only, no S3 abstraction

- Treat ONNX artifacts and Parquet snapshots as files under
  `training/artifacts/`. Use `rclone copy` to upload to R2 separately
  from the registration code path.
- Rejected: prod already requires an S3 client for the backup leg
  (decision [13]). Maintaining two abstractions for what is structurally
  the same workload (write-once / read-many blobs) is more code, more
  branches, more tests. The "single endpoint URL is the only knob"
  property is cheap to keep and lossy to give up.

### Alternative B: Backblaze B2 (originally chosen, then reverted)

- Use B2 as the prod target, per the original decision [13]. Same
  S3-compatible abstraction; only the endpoint URL differs.
- Rejected by decision [128] after considering vendor surface: the
  project already runs on Cloudflare for DNS (decision [9]'s Tunnel,
  decision [10]'s frontend on Vercel uses Cloudflare-fronted domains),
  and a future Better Stack monitor will live alongside. Consolidating
  storage onto Cloudflare R2 frees one vendor account, one set of
  credentials, one billing surface, and one dashboard. R2's $0 egress
  policy and 10 GB free tier are at least as good as B2's at portfolio
  scale. The S3-compatible abstraction makes the choice reversible if
  R2's posture changes — switching back to B2 (or to AWS) is a
  one-env-var change.

### Alternative C: AWS S3 in prod

- Use AWS S3 directly as the backup + artifact target.
- Rejected: R2 is locked (decisions [127] [128]) for cost reasons —
  AWS S3's per-GB egress at portfolio scale would be the dominant line
  item in the cost projection, and R2's $0 egress removes that risk
  entirely. AWS S3 stays as a viable swap-in target should R2's posture
  ever change (it's the canonical S3 reference implementation; any
  R2-shaped bug would have an AWS-shaped workaround). No reason to
  unlock the settled vendor choice now.

### Alternative D: rsync to the portable drive (no MinIO)

- Use `rsync` from R2 (via rclone) to the portable drive, then read
  directly from `/Volumes/MyDrive/bullpen-data/...` in offline mode.
- Rejected: requires filesystem-path code paths separate from the prod
  S3 paths. Defeats the whole point of the abstraction — there is now
  a `if offline: read_file else: read_s3` branch in every loader. That
  branch is exactly the silent-bug surface this ADR is trying to close.

### Alternative E: Tailscale + always-online dev

- Put the desktop on Tailscale, have the MacBook tunnel to the desktop's
  R2 client over Tailscale even when "offline" (i.e., off home Wi-Fi
  but on cellular).
- Rejected: still doesn't work on a plane without Wi-Fi, still costs
  cellular data on multi-GB pulls, and adds operational coupling
  between the two machines that ADR-0006 explicitly avoids. Tailscale
  may be useful later as the read-only observation transport, but it's
  not the right answer for the storage abstraction problem.

## Revision History

- **2026-05-21** — Switched the prod (and online-dev) endpoint from
  Backblaze B2 to Cloudflare R2 before any code was written against B2.
  Triggered by Group-B credentialing during Phase 0 wrap (the user
  proposed R2 after considering vendor surface). Substance of the
  decision is unchanged: S3-compatible client, single `S3_ENDPOINT_URL`
  env knob, MinIO offline. Updated:
  - Title parenthetical: `(MinIO offline, B2 in prod)` →
    `(MinIO offline, R2 in prod)`
  - Decision body's prod / dev-online endpoint URLs
  - Context narrative around `rclone speaks S3 natively to {vendor}`
  - Consequences: added vendor-consolidation note + R2-specific cost +
    egress lines; added Cloudflare-concentration risk as a new failure
    mode (acceptable per the abstraction's one-env-var swap-back)
  - Alternatives: added Alternative B (B2, considered and rejected by
    [128]); promoted prior Alternative B (AWS S3) to C; rsync to D;
    Tailscale to E
  - Related decisions list: added [128]
  - Status stays Accepted — the ADR's discipline ("one S3-compatible
    abstraction, endpoint URL is the only env-specific knob") holds
    exactly as written; only the endpoint string changed.
