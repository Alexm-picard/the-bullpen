-- V020 - close the V011 bypass: model_routing.champion_version_id must reference a CHAMPION.
--
-- V011 gave champion_version_id a foreign key to model_versions(id), which proves the referenced
-- version EXISTS but says nothing about its STAGE. Any shadow / candidate / archived version
-- satisfies that FK, so a hand-written or buggy routing row could point the A/B router at an
-- unvetted model and silently bypass the rule-5 (pre-declared promotion criteria) and rule-6
-- (no auto-promotion) gates - the routing row IS the serving decision, so a bad one is an
-- un-audited promotion in everything but name.
--
-- Two BEFORE triggers make the stage part of the invariant a hard DB constraint:
--
--   INSERT  - a new routing row must name a champion-stage version.
--   UPDATE  - ALL updates, not UPDATE OF champion_version_id. RoutingRepository.upsert is a single
--             `INSERT ... ON CONFLICT(model_name) DO UPDATE SET champion_version_id =
--             excluded.champion_version_id, ...` statement, and BOTH trigger events are reachable
--             from it (SQLite fires BEFORE INSERT against the proposed row even when the statement
--             resolves to DO UPDATE, then BEFORE UPDATE against the merged row). Every update that
--             statement performs rewrites champion_version_id anyway, so the wider event costs
--             nothing and leaves no gap for a future UPDATE that this migration did not anticipate.
--
-- The WHEN clause uses `IS NOT` rather than `<>` so it is NULL-safe. A dangling champion_version_id
-- makes the correlated subquery return NULL; under `<>` the WHEN would evaluate to NULL, the
-- trigger would not fire, and the row would be waved through to the FK check. `IS NOT` treats that
-- NULL as "not champion" and raises. foreign_keys=true is on the JDBC URL (see application.yml and
-- RegistryForeignKeyEnforcementIT), so a dangling id would be caught anyway, but the guard is total
-- on its own rather than dependent on a pragma. NOTE the ordering consequence: these BEFORE
-- triggers run ahead of FK enforcement, so a dangling champion_version_id now reports the invariant
-- message below instead of "FOREIGN KEY constraint failed".
--
-- SQLite RAISE() messages are static string literals - the offending version id CANNOT be
-- interpolated into them. The message therefore names the invariant precisely so the failure is
-- self-describing; the id is recoverable from the rejected statement's parameters, and
-- RoutingService performs the same check first so the normal path fails with a typed, id-carrying
-- refusal long before SQLite is reached.
--
-- DELIBERATELY NO TRIGGER ON model_versions. The legitimate promote path
-- (RegistryService.promoteToChampionAtomically) archives the PRIOR champion via updateStage BEFORE
-- ensureRoutingForChampion repoints the routing row, all inside one transaction. A model_versions
-- side trigger would fire in that mid-transaction window - when the still-current routing row
-- points at a version that has just become 'archived' - and abort EVERY legitimate promotion.
-- SQLite has no deferred triggers, so there is no way to defer that check to commit time. Stage
-- flips that strand a routing row (for example a bootstrap archiving a live champion) are closed
-- in the Java layer and caught by the boot-time registry integrity check, both landing in this
-- same change.
--
-- Scope: triggers gate FUTURE writes only. Validating rows that already exist is not this
-- migration's job (the boot-time integrity check owns those), so this applies cleanly to a DB that
-- already holds a legitimate routing row - prod has exactly one, pointing at a champion-stage
-- version.
--
-- Stage literals are LOWERCASE to match V010's model_versions.stage CHECK constraint and
-- Stage.dbValue().
--
-- The subquery binds model_name AS WELL AS stage (rule 9). Stage alone would accept a routing row
-- naming one model while referencing ANOTHER model's champion - e.g. a 'pitch_type_pre' row
-- pointing at battedball's champion version - which re-merges the two-heads separation at the
-- serving switch: the router would serve model B's weights under model A's name, and the boot-time
-- integrity check would count it healthy. Binding model_name makes a cross-model reference read as
-- "no such champion" and raise. No legitimate path can regress: ensureRoutingForChampion is always
-- called with (incoming.modelName(), incoming.id()), and V016 guarantees at most one champion per
-- model_name.

CREATE TRIGGER trg_model_routing_champion_stage_insert
BEFORE INSERT ON model_routing
FOR EACH ROW
WHEN (SELECT stage FROM model_versions
      WHERE id = NEW.champion_version_id AND model_name = NEW.model_name) IS NOT 'champion'
BEGIN
    SELECT RAISE(ABORT,
        'model_routing.champion_version_id must reference a model_versions row of the same model_name at stage champion');
END;

CREATE TRIGGER trg_model_routing_champion_stage_update
BEFORE UPDATE ON model_routing
FOR EACH ROW
WHEN (SELECT stage FROM model_versions
      WHERE id = NEW.champion_version_id AND model_name = NEW.model_name) IS NOT 'champion'
BEGIN
    SELECT RAISE(ABORT,
        'model_routing.champion_version_id must reference a model_versions row of the same model_name at stage champion');
END;
