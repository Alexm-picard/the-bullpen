# ADR-{NNNN}: {Short title in the present tense, e.g. "Use ONNX Runtime Java for in-process inference"}

- **Status**: Proposed | Accepted | Superseded by ADR-NNNN | Deprecated
- **Date**: YYYY-MM-DD
- **Deciders**: alex
- **Related**: `decisions.md` entry [N], plan.md Phase X, design.md §Y (link any that apply)

## Context

What forces are at play (technical, business, project-stage). What problem are
we solving. What constraints exist. What's *currently* in place that motivates
this decision now. 2–6 paragraphs. Be specific — "we want it to be fast" is
not context; "p99 inference latency must stay under 50ms to keep the live
prediction UX feeling instant per design.md §3" is context.

## Decision

State the decision in a single sentence. Then expand: what is being adopted,
what is being explicitly rejected, what scope this applies to. Use present
tense ("We use X") not future tense ("We will use X").

## Consequences

What becomes easier as a result. What becomes harder. What new failure modes
appear. What we're now locked into. What follow-on work this implies. Be
honest about the negatives — an ADR with no listed downsides is a red flag.

## Alternatives Considered

For each meaningful alternative we evaluated:

### Alternative A: {name}

- What it would look like
- Why we did not pick it (be specific — not "it's worse", but "it adds a
  Python sidecar to the serving path, which violates the no-RPC-in-inference
  rule from design.md §5")

### Alternative B: {name}

- Same structure.

(Drop the section if there were genuinely no alternatives considered — but
that's rare and usually a sign the decision wasn't actually evaluated.)

## Revision History

ADRs can be revised in place via this section (unlike `decisions.md`, which
is strictly append-only). Use this for:
- Corrections to factual errors in the original ADR
- Updated information after operating the system
- Explicit supersession by a later ADR

Format:

- **YYYY-MM-DD** — Brief description of the revision. Why. What changed.

(Leave empty until the first revision.)

---

## Notes on writing good ADRs

- **Write in the present tense.** "We use X." Not "We will use X."
- **Length: 1–3 pages**. If shorter, you probably skipped the Alternatives
  section. If longer, you're probably mixing in implementation detail that
  belongs in the code or a design.md section.
- **One decision per ADR.** Bundling causes confusion when one part needs
  revision.
- **Numbering is sequential and never reused.** Same discipline as
  `decisions.md`. If you abandon a draft ADR, leave the number reserved and
  start the next one at NNNN+1.
- **`decisions.md` is the index.** Every ADR should have a matching entry in
  `decisions.md` of the form: `[N] DATE — short decision — see ADR-NNNN`.
  Not every `decisions.md` entry needs an ADR (one-line decisions can stand
  alone), but every ADR needs a `decisions.md` entry.
- **Status transitions matter.** When an ADR is superseded, update its
  Status to `Superseded by ADR-NNNN` *and* add a Revision History entry on
  the original explaining what changed. The superseding ADR should explain
  what it's replacing and why.
