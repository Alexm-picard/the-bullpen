-- V012 — Phase 3b.5
-- Add model_version_id (registry FK) to prediction_log so 3a.5's reconciliation
-- job + future drift queries can join precisely against model_versions.id
-- without round-tripping through the (model_name, version) string pair.
--
-- Additive + nullable for backwards compatibility: V1 rows written by 1.7
-- get NULL (no FK known at write time); V2 rows from 3b.3's InferenceRouter
-- carry the resolved id from RoutingConfig. Reconciliation in 3a.5 uses
-- (model_name, version) DISTINCT today; once this column is populated for
-- a full TTL window, it can switch to the cheaper integer join.

ALTER TABLE prediction_log
    ADD COLUMN IF NOT EXISTS model_version_id Nullable(Int64) AFTER model_version;
