# ADR-0001: Use strict Java 21, not Kotlin, on the JVM

- **Status**: Accepted
- **Date**: 2026-05-19
- **Deciders**: alex
- **Related**: `decisions.md` entries [22] [23] [24], `plan.md` Phase 0, `design.md` §10

## Context

The Bullpen is framed as a portfolio project for FAANG ML/SD engineering hiring.
Recruiter ATS filtering and HM resume-scan habits both grep for **"Java"** as a
keyword. The StudyForesight project (Python/FastAPI + TypeScript) already
demonstrates Python depth; this project needs to demonstrate a different ecosystem
without diluting it.

Spring Boot 3.x ships with first-class Kotlin support, and Kotlin-on-Spring is
common in startup land. From a pure ergonomics standpoint — null-safety,
extension functions, coroutines, less ceremony — Kotlin is the more pleasant
language to write in 2026. But "pleasant" is not the optimization function
here.

The decision was framed as: do we get **more or less hiring signal** by mixing
Kotlin into the JVM layer? Resume keyword matching, code-screen expectations,
and HM mental models all anchor to "Java" when the bullet says "Spring Boot on
JVM with ONNX Runtime inference." Mixing Kotlin in produces a fuzzier signal
that splits attention between two languages without committing to either.

Java 21 ships records, sealed types, pattern matching, switch expressions, and
virtual threads — the modern-Java surface area that closes most of Kotlin's
ergonomic lead while keeping the unambiguous "this is a Java project" framing.

## Decision

We use **strict Java 21** for all JVM code in `/backend`. No Kotlin source
files, no Kotlin Gradle plugin, no Kotlin DSL anywhere it can be avoided. The
Gradle build script stays in Kotlin DSL (`build.gradle.kts`) because that's
Gradle's documented default in 2026 and the only Kotlin in the project — but
no application code is written in Kotlin.

## Consequences

**Easier:**

- One language to keep current with. Java 21 idioms (records, sealed types,
  pattern matching, virtual threads) become the project's idiomatic style.
- ATS keyword match is unambiguous.
- Spotless + google-java-format is well-trodden tooling; the equivalent
  Kotlin chain (ktfmt, ktlint) is less universally configured.

**Harder:**

- More verbose at call sites — explicit `Optional`, getter ceremony on
  non-record DTOs, no extension functions for fluent helpers.
- Coroutines are off the table; we lean on virtual threads instead (covered
  by decision [24]).
- Null-safety discipline relies on Error Prone + careful `Optional` usage,
  not the type system.

**Locked into:**

- All future code in this project is Java. If we want Kotlin elsewhere later,
  it goes in a separate project.

## Alternatives Considered

### Alternative A: Kotlin-on-Spring

- Same Spring 3.x foundation, Kotlin source throughout.
- Rejected: dilutes the "Java" keyword signal that's the project's stated
  framing goal. The ergonomic win does not pay for the framing loss in a
  portfolio-evaluation context.

### Alternative B: Mixed (Java for inference path, Kotlin for app code)

- Inference path stays in Java because ONNX Runtime Java bindings are the
  thing being showcased; Kotlin for the rest.
- Rejected: produces the worst of both worlds — two languages to maintain,
  no clean signal either way. Reviewers either grep "Java" (finds it but
  notes the mix) or grep "Kotlin" (same), with neither feeling load-bearing.

### Alternative C: Scala

- Different JVM language, strong type system, FP-leaning.
- Rejected: vanishingly rare in the target hiring pool. Sends "I optimize for
  language taste over team fit." Wrong signal.

## Revision History

(none)
