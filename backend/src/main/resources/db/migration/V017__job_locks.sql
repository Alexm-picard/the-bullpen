-- D-36: at-most-once-per-ET-day lock for the non-idempotent worker jobs. A worker acquires the
-- (job_name, fire_date) row before running; a UNIQUE violation means another instance already owns
-- that fire date, so it skips. Semantics: at-most-once per ET day - a crashed winner's day is a
-- missed day (same as today's single-instance failure mode). fire_date is YYYY-MM-DD in
-- America/New_York (the zone every guarded @Scheduled already uses).
CREATE TABLE job_locks (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  job_name    TEXT NOT NULL,
  fire_date   TEXT NOT NULL,
  acquired_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (job_name, fire_date)
);
