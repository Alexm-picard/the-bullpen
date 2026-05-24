-- V011 — Phase 3a.1
-- The model_routing table holds the live routing decision per
-- model_name: which version is the champion, which version is the
-- challenger, what fraction of traffic goes to the challenger, and
-- whether the routing is in shadow mode (always 0% live, prediction
-- logged separately) or real A/B (challenger gets the requested
-- fraction of user-facing traffic).
--
-- One row per model_name (UNIQUE). The A/B router (3b) reads this
-- table on every request via a TTL-cached lookup; promotion (3a.4)
-- writes to it inside the same transaction as the stage update on
-- model_versions.
--
-- mode='shadow' is the default new-model state per rule 6
-- (no-auto-promotion). Decision [71] documents the shadow-first
-- promotion ladder.

CREATE TABLE model_routing (
    id                       INTEGER PRIMARY KEY AUTOINCREMENT,
    model_name               TEXT      NOT NULL UNIQUE,
    champion_version_id      INTEGER   NOT NULL REFERENCES model_versions(id),
    challenger_version_id    INTEGER   REFERENCES model_versions(id),
    challenger_traffic_pct   REAL      NOT NULL DEFAULT 0
        CHECK (challenger_traffic_pct >= 0 AND challenger_traffic_pct <= 100),
    mode                     TEXT      NOT NULL DEFAULT 'shadow'
        CHECK (mode IN ('shadow','ab')),
    updated_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
