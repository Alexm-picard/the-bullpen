-- V022 — #387: snapshot the dead-letter counter at experiment start so the
-- delta across the experiment window is observable at completion.
-- Nullable: null means "started before this column existed" or "counter
-- was unavailable at start time" (JVM restart, metric not registered).

ALTER TABLE experiment_results ADD COLUMN dead_lettered_at_start REAL;
