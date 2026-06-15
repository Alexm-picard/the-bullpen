-- V016 - enforce the single-champion invariant at the DB level.
--
-- model_versions.stage permits 'champion' (V010's CHECK), but nothing stopped two rows for the
-- same model_name from both being 'champion'. The promotion logic (rules 5/9) is supposed to
-- guarantee at most one champion per model, but that was an in-code invariant only. A PARTIAL
-- UNIQUE index makes it a hard DB constraint: at most one row per model_name with stage='champion'
-- (candidate/shadow/archived rows are unconstrained, so a model can keep history + a shadow while
-- it has one champion). SQLite supports partial indexes via the WHERE predicate.
--
-- This will FAIL LOUD at migration time if the registry already holds a duplicate champion - that
-- is the desired signal (a latent promotion bug), not a regression. The single-champion invariant
-- is expected to already hold, so the index applies cleanly on the live registry.
CREATE UNIQUE INDEX idx_mv_one_champion_per_model
    ON model_versions (model_name)
    WHERE stage = 'champion';
