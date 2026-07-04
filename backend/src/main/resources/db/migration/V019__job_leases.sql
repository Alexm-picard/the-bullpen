-- D-37: single-row heartbeat lease for the live poller (and any future singleton worker task). One
-- instance holds job_name='live_polling'; it renews heartbeat_at every tick, and another instance
-- takes over only after the lease goes stale (~30s of missed renewals = a crashed/paused holder).
-- Distinct from job_locks (V017, at-most-once-per-day markers) - this is a renewable single-owner
-- lease. heartbeat_at + datetime('now') comparisons are both UTC, so the staleness window is
-- timezone-agnostic.
CREATE TABLE job_leases (
  job_name     TEXT PRIMARY KEY,
  owner        TEXT NOT NULL,
  heartbeat_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
