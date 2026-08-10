# ADR-0015: domain/ is a shared kernel for read-side query projections

- **Status**: Accepted
- **Date**: 2026-07-21
- **Deciders**: alex
- **Related**: decisions.md [181]; the C1/C2 architecture-enforcement work (ArchUnit + domain extraction); the 2026-07-19 engineering audit; ADR-0006

## Context

C2 (extract a real domain/ module) satisfied C1's ArchUnit boundary rule by
MOVING shared types into domain/ rather than adding the boundary mappers C1's
prose had described. Today 13 of the 18 domain types are referenced directly in
api/, so GameSummary, LivePitchRow, BattedBallRow and the other *Row records are
simultaneously the SQL projection a repository returns and the JSON payload a
controller serves. This was shipped without being stated as a decision. It needs
one, because "is domain/ a shared kernel or a pure core behind mappers" is an
architecture split that future changes will be measured against.

## Decision

domain/ is a **shared kernel for read-side query projections**. A single record
serving both the SQL projection and the JSON payload is the INTENDED shape for
read-only query results.

Two boundaries make this safe rather than a latent coupling trap:

1. **Scope: read-side only.** This ratification covers query projections. A
   future WRITE-side domain record - e.g. a mutable Pitch that simulation/ and
   inference/ both reason about and mutate - does NOT inherit this precedent and
   requires its own decision. The shared-kernel argument here rests entirely on
   these being immutable read results; it does not transfer to a write model.
2. **Transport concerns stay in api/dto.** The two paging envelopes
   (OpsEventsPage, PostPredictionsPage) move BACK to api/dto. A "page" exists only
   because an HTTP endpoint took ?page=&size= and hasNext is a LIMIT size+1
   transport trick; it is not a baseball concept. Repositories return a small
   rows-plus-hasNext carrier; the controller maps it to the api/dto Page envelope.
   Stated as one principle: "domain/ holds baseball concepts; transport concerns
   stay in api/dto."

C1's ArchUnit rule (data/ must not import api/dto) stays green: repositories
return domain/ records or the carrier, never api/dto. C1's PROSE describing
"boundary mappers" is superseded by this ADR.

## Consequences

**Easier:** no ~34 files of read-side pass-through mapping; the query path stays
one shape end to end; the principle is statable in a sentence.

**Trade-off (named and accepted):** a ClickHouse column rename on a projection
becomes a public wire change, and there is no API-versioning story today. Accepted
because there are no versioned external consumers yet. **Documented exit:** when a
versioned external consumer appears, introduce a view/mapping layer at that
boundary - do not retrofit mappers project-wide before then.

**Locked into:** the read-side shared kernel. Reversing it (adding mappers) is a
re-decision. Extending it to a write-side record is explicitly NOT covered and
must be decided separately.

## Alternatives Considered

- **A - shared kernel for all 18 including the paging envelopes.** Rejected: the
  paging envelopes are transport, not domain; leaving them in domain/ makes the
  rule "convenience" instead of a statable principle.
- **B - build the mappers C1's prose promised (repositories -> domain, controllers
  -> api/dto).** Rejected: ~34 pass-through files on the READ side read as ceremony,
  not judgment; mapping buys nothing on the query side (CQRS read model; Young,
  Vernon).

## Revision History

- 2026-07-21 - Created. Ratifies the shared-kernel shape C2 shipped, scoped to
  read-side, with the paging-envelope carve-out.
