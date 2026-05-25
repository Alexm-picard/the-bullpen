# OSS contribution targets

Phase 6 requires at least one merged OSS PR in a project adjacent to
The Bullpen's stack. Drive-by typo fixes don't count — the PR has to
land a real fix or a real feature in a project with maintainers who
review.

## Targets, ranked by accessibility-to-merge

### Tier 1 — easiest path, real signal

**clickhouse-java** — the JDBC driver this project lives on.

- Repo: https://github.com/ClickHouse/clickhouse-java
- Why: heavily used in this project. Active maintainers. Issues
  labeled `good first issue` are typically real fixes.
- Surface this project gave you: `ClickHouseDataSource` wiring,
  `JdbcTemplate` row-mapper edge cases (Nullable types coming back
  as `getDouble` + `wasNull`), partition-key tuning.
- Candidate scope: a small RowMapper / type-coercion bug; doc fix on
  a Nullable type behavior; a Testcontainers integration sample.

**onnxruntime / Java bindings** — the inference layer.

- Repo: https://github.com/microsoft/onnxruntime
- Why: every prediction in this project goes through ONNX Runtime
  Java. Fewer active first-time PRs but the Java bindings see less
  attention than the C++/Python sides.
- Candidate scope: javadoc fix on a session-options method; example
  in the Java samples directory; a small test for a Nullable-input
  edge case.

### Tier 2 — bigger projects, still tractable

**LightGBM** — the pitch-outcome model.

- Repo: https://github.com/microsoft/LightGBM
- Why: 2b.2's stdout-buffering hang taught a real Python-binding
  ergonomic gap. A PR that documents (or fixes) the `print` vs
  `logging` interaction under `tee` would be merge-worthy and useful
  to future debuggers.
- Candidate scope: doc PR on the Python API page covering logger
  registration; a real fix routing the default callback through
  Python's `logging`.

**Mantine** — the frontend component library.

- Repo: https://github.com/mantinedev/mantine
- Why: v9 migration surfaced at least one silent prop rename
  (`<Grid gutter>` → `<Grid gap>`). Active maintainers. PRs land
  weekly.
- Candidate scope: doc PR adding a v8→v9 migration note for any
  prop rename not already in their migration guide; a typescript
  type tweak for a component that accepts `string | number` where
  one path was missing.

### Tier 3 — bigger lift, deeper signal

**TanStack Query** — the data layer.

- Repo: https://github.com/TanStack/query
- Why: this project's `useLivePitches` pattern (mutable ref for
  cursor + stable queryKey + merged-array) is a real-world example
  of a pattern that's not in their docs.
- Candidate scope: a `useCursor` recipe PR to their docs site; a
  type-safety improvement on `placeholderData: keepPreviousData`'s
  generic inference.

**Spring Boot** — the serving stack.

- Repo: https://github.com/spring-projects/spring-boot
- Why: the `@ConditionalOnBean` ordering bug across two component-
  scanned beans (4b.1 status-log) is a real footgun that deserves at
  minimum a documentation warning.
- Candidate scope: a doc PR on the conditional-bean reference page
  warning about the ordering issue with two component-scanned beans;
  a real fix would be substantial (touches the bootstrap evaluator).

## Scoring rubric (when picking)

- **Reviewer responsiveness**: check the last 10 merged PRs. If the
  median is < 14 days from open to merge, the project is alive.
- **First-time-contributor friendliness**: any `good first issue`
  label, any CONTRIBUTING.md walking through dev setup, any signal
  that maintainers triage external PRs.
- **Adjacency to this project's stack**: must be one of the deps;
  must be plausible that a future interviewer connects the dot.
- **Scope**: small enough to land in one weekend. If it's >50 lines
  changed, narrow further.

## Process

1. Read CONTRIBUTING.md before doing anything.
2. Find or file an issue describing the problem; wait for a
   maintainer "yes please" before coding.
3. Write the PR.
4. After it merges, link from the README under "Operating evidence"
   and from this doc with the merge date + the PR link.

## Track record

| Date       | Project    | PR  | Status | Notes |
| ---------- | ---------- | --- | ------ | ----- |
| YYYY-MM-DD | _none yet_ |     |        |       |
