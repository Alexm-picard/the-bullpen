# ADR-0007: Object storage via S3-compatible endpoint (MinIO offline, B2 in prod)

- **Status**: Accepted
- **Date**: 2026-05-21
- **Deciders**: alex
- **Related**: `decisions.md` entries [13] [28] [68] [127], ADR-0006, `plan.md` Phase 0, `design.md` §6 §8

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
- Decision [13] specifies the backup path: `clickhouse-backup` → `rclone`
  → Backblaze B2, with 7-day local / 4-week weekly / 12-month monthly
  retention. The backup target is already object storage.

Both workloads share the same access pattern: write-once / read-many,
keyed by path, no fine-grained mutation. Filesystem-shaped storage would
work, but the backup leg already requires an S3 client (rclone speaks S3
natively to B2). Introducing a second abstraction for model artifacts
would double the surface area for the same operational need.

ADR-0006 establishes the MacBook as the dev environment. The MacBook is
frequently offline (travel, weak Wi-Fi, cafes, planes), and training
iterations during those windows still need access to a representative
sample of the data, the registered models, and the feature pipeline
snapshot. Pulling multi-GB blobs over a hotspot on every script run is
not acceptable. The natural answer is a local mirror, but the _client
code_ should not branch on whether it's reading from B2 or a local
mirror — that branching is exactly the kind of dev/prod skew that
decision [67] (feature schema hashing) and ADR-0006 (no in-place edits)
exist to prevent.

S3 is the only object-storage API that has a mature self-hostable
implementation (MinIO) with the same wire protocol as a hosted service
(B2 via its S3-compatible endpoint, or AWS S3 directly). Pointing the
same client at a different endpoint is a one-env-var change.

## Decision

All object-storage access in the project uses an **S3-compatible client**
(`boto3` in Python, `software.amazon.awssdk.s3` in Java), parameterized
by a single environment-specific variable `S3_ENDPOINT_URL`:

- **Prod (desktop, online):**
  `S3_ENDPOINT_URL=https://s3.us-west-002.backblazeb2.com`
  Read-write credentials. Backups land here; model artifacts also land
  here once registered.
- **Dev (MacBook, online):**
  `S3_ENDPOINT_URL=https://s3.us-west-002.backblazeb2.com`
  Read-only credentials. Pulls samples and registered models for local
  iteration.
- **Dev (MacBook, offline):**
  `S3_ENDPOINT_URL=http://localhost:9000`
  Local MinIO instance serving from `/Volumes/MyDrive/bullpen-data` on a
  portable USB drive. MinIO is launched via `make minio-up`; the drive
  is the user's existing data drive, not the BULLPEN_BAK backup target.

No code anywhere reads from `file://` paths for object data. No code
hard-codes a bucket host. The only environment-specific knob is
`S3_ENDPOINT_URL` plus the credentials pair.

Sync from B2 to the portable drive uses `rclone sync` (manual, run when
the user remembers to before traveling). Bucket layout:

- `samples/dev/` — small stratified samples (≤ 500 MB) used by
  `make train-sample`. Always mirrored offline; sync is mandatory before
  travel.
- `snapshots/v{N}/` — registered model snapshots (Parquet + ONNX + JSON).
  Mirrored selectively; default mirror is the current LIVE champion
  plus the most recent SHADOW candidate.
- `raw/` — full historical Statcast in canonical form. Prod + B2 only;
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
- B2 → MinIO migration would be a one-env-var change, should B2 ever
  go away or get expensive. Not a planned move, but a recoverable one.
- The backup path already needs an S3 client; reusing it for model
  artifacts amortizes the operational learning (credentials handling,
  retry policy, lifecycle rules).

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

**Locked into:**

- Any future storage need with non-S3 semantics (transactional metadata,
  fine-grained mutation, blob streaming with range writes) is a
  re-decision via `/decide`, not a quiet bypass. The discipline this
  ADR enforces is "one storage abstraction, no second class."

## Alternatives Considered

### Alternative A: Filesystem paths only, no S3 abstraction

- Treat ONNX artifacts and Parquet snapshots as files under
  `training/artifacts/`. Use `rclone copy` to upload to B2 separately
  from the registration code path.
- Rejected: prod already requires an S3 client for the backup leg
  (decision [13]). Maintaining two abstractions for what is structurally
  the same workload (write-once / read-many blobs) is more code, more
  branches, more tests. The "single endpoint URL is the only knob"
  property is cheap to keep and lossy to give up.

### Alternative B: AWS S3 in prod (drop B2)

- Use AWS S3 directly as the backup + artifact target.
- Rejected: B2 is already locked (decision [13]) for cost reasons
  (~1/5th the per-GB egress of AWS S3 at portfolio scale). Switching
  re-opens settled scope; the S3-compatible decision means B2 and AWS S3
  are interchangeable later if needed. No reason to unlock a settled
  decision now.

### Alternative C: rsync to the portable drive (no MinIO)

- Use `rsync` from B2 (via rclone) to the portable drive, then read
  directly from `/Volumes/MyDrive/bullpen-data/...` in offline mode.
- Rejected: requires filesystem-path code paths separate from the prod
  S3 paths. Defeats the whole point of the abstraction — there is now
  a `if offline: read_file else: read_s3` branch in every loader. That
  branch is exactly the silent-bug surface this ADR is trying to close.

### Alternative D: Tailscale + always-online dev

- Put the desktop on Tailscale, have the MacBook tunnel to the desktop's
  MinIO/B2 client over Tailscale even when "offline" (i.e., off home
  Wi-Fi but on cellular).
- Rejected: still doesn't work on a plane without Wi-Fi, still costs
  cellular data on multi-GB pulls, and adds operational coupling
  between the two machines that ADR-0006 explicitly avoids. Tailscale
  may be useful later as the read-only observation transport, but it's
  not the right answer for the storage abstraction problem.

## Revision History

(none)
