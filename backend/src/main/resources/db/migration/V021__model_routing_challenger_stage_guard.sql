-- V021 - the challenger-side mirror of V020: model_routing.challenger_version_id, when set, must
-- reference a SHADOW version OF THE SAME MODEL.
--
-- V011 gave challenger_version_id a foreign key to model_versions(id), which proves the referenced
-- version EXISTS but says nothing about its STAGE or its model. V020 closed that hole on the
-- champion column only, so the challenger slot kept the whole bypass: a hand-written or buggy
-- routing row could point a model's challenger at an ARCHIVED version (or at another model's
-- version), and under mode='ab' with challenger_traffic_pct > 0 that version takes REAL USER
-- TRAFFIC. That is a rule-6 violation in substance - a version serving users without having passed
-- the rule-5 gates - even though nothing was ever called a promotion.
--
-- Two BEFORE triggers make the stage part of the invariant a hard DB constraint:
--
--   INSERT  - a new routing row that names a challenger must name a shadow-stage one.
--   UPDATE  - ALL updates, not UPDATE OF challenger_version_id, for the V020 reason:
--             RoutingRepository.upsert is a single `INSERT ... ON CONFLICT(model_name) DO UPDATE
--             SET challenger_version_id = excluded.challenger_version_id, ...` statement and BOTH
--             trigger events are reachable from it (SQLite fires BEFORE INSERT against the proposed
--             row even when the statement resolves to DO UPDATE, then BEFORE UPDATE against the
--             merged row). Every update that statement performs rewrites challenger_version_id
--             anyway, so the wider event costs nothing and leaves no gap for a future UPDATE this
--             migration did not anticipate.
--
-- THREE DIFFERENCES FROM THE V020 CHAMPION TRIGGERS, each deliberate:
--
--   (a) NULLABLE, and NULL is the COMMON HEALTHY STATE. champion_version_id is NOT NULL, so V020's
--       WHEN clause needs no null guard; challenger_version_id is nullable and champion-only
--       routing (no challenger) is the normal steady state - prod's rows are NULL right now. The
--       leading `NEW.challenger_version_id IS NOT NULL` term is what keeps this guard from firing
--       on every champion-only row. Without it the correlated subquery would return NULL for an
--       absent challenger, `NULL IS NOT 'shadow'` would be true, and the trigger would abort every
--       legitimate write in the system. That term is load-bearing, not defensive.
--
--   (b) THE LEGITIMATE STAGE IS 'shadow', NOT 'champion'. RoutingService.setChallenger refuses any
--       candidate whose stage is not SHADOW (RoutingException.ChallengerNotInShadow), so shadow is
--       the only stage the application ever writes into this column. Note the asymmetry with V016:
--       that partial unique index enforces at most one CHAMPION per model_name, and has no bearing
--       here - a model may hold many shadow versions, only one of which is routed. The guard is
--       about the stage of the referenced row, not about uniqueness.
--
--   (c) THE SUBQUERY BINDS model_name AS WELL AS stage (rule 9), per the V020 lesson. Stage alone
--       would accept a routing row naming one model while referencing ANOTHER model's shadow
--       version - a cross-head reference the two-heads separation exists to prevent, and one that
--       would log (and eventually evaluate) that version's predictions under the wrong head.
--       Binding model_name makes a cross-model reference read as "no such shadow version" and
--       raise. RoutingService.setChallenger performs the same model_name check in Java, so no
--       legitimate path regresses.
--
-- Stage literals are LOWERCASE to match V010's model_versions.stage CHECK constraint and
-- Stage.dbValue().
--
-- The WHEN clause uses `IS NOT` rather than `<>` so it is NULL-safe on the SUBQUERY result (the
-- explicit IS NOT NULL term already handles the column itself). A dangling challenger_version_id
-- makes the subquery return NULL; under `<>` the WHEN would evaluate to NULL, the trigger would not
-- fire, and the row would be waved through to the FK check. `IS NOT` treats that NULL as "not
-- shadow" and raises. foreign_keys=true is on the JDBC URL (see application.yml and
-- RegistryForeignKeyEnforcementIT), so a dangling id would be caught anyway, but the guard is total
-- on its own rather than dependent on a pragma. NOTE the same ordering consequence V020 recorded:
-- this BEFORE trigger runs ahead of FK enforcement, so a dangling challenger_version_id now reports
-- the invariant message below instead of "FOREIGN KEY constraint failed". With V020 + V021 both
-- columns on model_routing are trigger-claimed, so any test that wants to prove raw FK enforcement
-- must probe a table this migration does not touch (experiment_results carries the same FK to
-- model_versions on two untriggered columns).
--
-- SQLite RAISE() messages are static string literals - the offending version id CANNOT be
-- interpolated. The message names the invariant precisely so the failure is self-describing; the id
-- is recoverable from the rejected statement's parameters, and RoutingService.setChallenger runs
-- the same check first so the normal path fails with a typed, id-carrying refusal long before
-- SQLite is reached.
--
-- DELIBERATELY NO TRIGGER ON model_versions, for V020's exact mid-transaction reason and one more
-- specific to this column. A model_versions-side trigger would have to fire on the stage flip
-- (shadow -> archived) of a currently-routed challenger. That flip is legitimate and routine - it
-- is how a challenger is retired - and the routing row is cleaned up in the SAME transaction,
-- immediately after, by the Java layer. A trigger would abort in that window, and SQLite has no
-- deferred triggers, so there is no way to defer the check to commit time. Stage flips that strand
-- a routing row are closed in the Java layer (transitionStage clears a routed challenger's slot
-- when archiving it) and caught by the boot-time registry integrity check, both landing in this
-- same change.
--
-- DELIBERATELY NO TRIGGER ON DELETE either. That is not an omission: the Java clearing paths need
-- deletes and clears to stay unblocked. The INC-1 rollback (decision [150]) and the bootstrap-reset
-- escape hatch both DELETE the routing row outright (RoutingRepository.deleteByModelName), and the
-- bootstrap reset does so AFTER archiveAllForModel has already archived every version including a
-- routed challenger. A DELETE trigger validating the outgoing row would turn both recovery paths
-- into hard failures at exactly the moment the operator is trying to recover.
--
-- LEGITIMATE WRITE PATHS, VERIFIED AGAINST THE CODE (all five reach RoutingRepository.upsert):
--
--   RoutingService.setChallenger        - validates candidate.stage() == SHADOW and
--                                         candidate.modelName().equals(modelName) BEFORE the
--                                         upsert, so the row it writes is exactly what the trigger
--                                         admits. Java refuses first with a typed exception; the
--                                         trigger is the backstop for anything that is not this
--                                         method.
--   RoutingService.ensureRoutingForChampion - two sub-cases, and the ORDERING matters:
--                                         RegistryService.promoteToChampionAtomically calls
--                                         repo.updateStage(incoming.id(), CHAMPION) BEFORE calling
--                                         ensureRoutingForChampion, in the same transaction. So
--                                         when the version being promoted IS the current
--                                         challenger, that version is ALREADY at stage 'champion'
--                                         by the time the routing row is written - carrying it
--                                         forward in the challenger slot would make the subquery
--                                         return 'champion', not 'shadow', and the trigger WOULD
--                                         fire. It does not, because ensureRoutingForChampion's
--                                         `challengerVersionId != null && challengerVersionId ==
--                                         championVersionId ? null : challengerVersionId` branch
--                                         clears the slot to NULL for exactly that case (and only
--                                         that case). The other sub-case - promoting some version
--                                         that is NOT the challenger - carries a still-shadow
--                                         challenger forward unchanged, which the guard admits. The
--                                         first-promotion sub-case inserts with a NULL challenger.
--   setTrafficPct / setMode / clearChallenger - carry forward current.challengerVersionId(), which
--                                         is shadow-or-null by the above, or write an explicit NULL
--                                         (clearChallenger). All admitted.
--
-- Scope: triggers gate FUTURE writes only. Validating rows that already exist is not this
-- migration's job (the boot-time integrity check owns those), so this applies cleanly to a DB that
-- already holds routing rows - prod's rows currently carry NULL challengers, which is the fast path
-- this guard leaves untouched.

CREATE TRIGGER trg_model_routing_challenger_stage_insert
BEFORE INSERT ON model_routing
FOR EACH ROW
WHEN NEW.challenger_version_id IS NOT NULL
     AND (SELECT stage FROM model_versions
          WHERE id = NEW.challenger_version_id AND model_name = NEW.model_name) IS NOT 'shadow'
BEGIN
    SELECT RAISE(ABORT,
        'model_routing.challenger_version_id, when non-null, must reference a model_versions row of the same model_name at stage shadow');
END;

CREATE TRIGGER trg_model_routing_challenger_stage_update
BEFORE UPDATE ON model_routing
FOR EACH ROW
WHEN NEW.challenger_version_id IS NOT NULL
     AND (SELECT stage FROM model_versions
          WHERE id = NEW.challenger_version_id AND model_name = NEW.model_name) IS NOT 'shadow'
BEGIN
    SELECT RAISE(ABORT,
        'model_routing.challenger_version_id, when non-null, must reference a model_versions row of the same model_name at stage shadow');
END;
