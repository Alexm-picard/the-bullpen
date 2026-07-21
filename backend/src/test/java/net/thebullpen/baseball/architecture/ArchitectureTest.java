package net.thebullpen.baseball.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

/**
 * C1 (audit remediation) - the module boundaries of design.md section 6, enforced in CI.
 *
 * <p><b>Frozen rules</b> use {@link FreezingArchRule}: the CURRENT violations are baselined in
 * {@code src/test/resources/archunit-store} (committed), so the build stays green on the known debt
 * while any NEW violation fails immediately. C2 removed the baselined violations - NOT by adding
 * boundary mappers, but by moving the 17 row/value records those repositories return into {@code
 * domain/}, where they always belonged - which drained that store to empty and let the rule
 * graduate to strict. That shared-kernel shape is the RATIFIED decision (ADR-0015 / [181]): {@code
 * domain/} is a shared kernel for read-side query projections, with transport types (the two paging
 * envelopes) kept in {@code api/dto} and repositories returning a {@code domain/PagedRows} carrier.
 *
 * <p><b>Strict rules</b> (no baseline): {@code domain/} purity - the hexagonal-lite core must stay
 * a plain-records module (18 types since C2; keeping the rule strict stops the first impurity from
 * ever landing).
 */
@AnalyzeClasses(
    packages = "net.thebullpen.baseball",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /**
   * The persistence boundary, STRICT since C2. Repositories under {@code data/} must not depend on
   * {@code api/dto} types. This started as a FROZEN rule with 41 baselined violations across 8
   * repositories; C2 moved the 17 row/value records those repositories actually return into {@code
   * domain/}, which drained the baseline to zero, so the rule is now enforced outright and carries
   * no store entry. A new web-DTO reach-in from {@code data/} fails immediately.
   */
  @ArchTest
  static final ArchRule dataMustNotDependOnApiDtos =
      ArchRuleDefinition.noClasses()
          .that()
          .resideInAPackage("..baseball.data..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..baseball.api.dto..")
          .because(
              "repositories are the persistence boundary; returning web DTOs couples SQL row"
                  + " shapes to the HTTP contract");

  /**
   * domain/ purity, STRICT: the shared core must be plain Java records - no Spring, no SQL/JDBC, no
   * HTTP/web, no serialization framework. This is the hexagonal-lite end state CLAUDE.md documents;
   * enforcing it while the package is small keeps it pure as types migrate in (C2 moves LivePitch,
   * ScheduledGame, GameStatus here).
   */
  @ArchTest
  static final ArchRule domainMustStayPure =
      ArchRuleDefinition.noClasses()
          .that()
          .resideInAPackage("..baseball.domain..")
          .should()
          .dependOnClassesThat()
          // ALLOWLIST (review note N2): domain may depend only on itself and the JDK - stricter
          // than enumerating forbidden frameworks, and it also blocks domain -> data/inference/api
          // reach-ins (java.sql/javax.sql are excluded by NOT being allowlisted).
          .resideOutsideOfPackages(
              "..baseball.domain..", "java.lang..", "java.util..", "java.time..")
          .because(
              "the domain core is pure data - JDK-only records, no framework, persistence,"
                  + " logging, or app-module coupling");

  /**
   * N4 (C1 review follow-up): the other half of the design.md section-6 boundary set. Both are
   * STRICT because both are already clean - {@code simulation/} touches neither persistence nor
   * ingest, and {@code inference/} never reaches into the live-poll pipeline. Locking them now
   * keeps a future convenience import from quietly inverting the layering.
   *
   * <p>The one real {@code inference -> data} edge ({@code RoutingRepository -> data.JdbcTimes}) is
   * carved out BY NAME in the rule below rather than left unruled, so the exception is enforced as
   * an exception instead of being a hole.
   */
  @ArchTest
  static final ArchRule simulationMustNotDependOnPersistenceOrIngest =
      ArchRuleDefinition.noClasses()
          .that()
          .resideInAPackage("..baseball.simulation..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..baseball.data..", "..baseball.ingest..")
          .because(
              "the forward simulator is pure computation over the domain core; it must not acquire"
                  + " SQL or live-feed coupling");

  /**
   * The C1-review follow-up's own gap, closed: the carve-out below used to be documented in prose
   * with no rule behind it, which left the comment doing enforcement's job - nothing stopped a
   * future {@code PitchPredictionService -> LivePitchesRepository} import. The exception is named
   * explicitly so it is the ONLY one; the edge count really is exactly one.
   */
  @ArchTest
  static final ArchRule inferenceMustNotDependOnPersistenceExceptItsOwnRepository =
      ArchRuleDefinition.noClasses()
          .that()
          .resideInAPackage("..baseball.inference..")
          .and()
          .doNotHaveFullyQualifiedName(
              "net.thebullpen.baseball.inference.routing.RoutingRepository")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..baseball.data..")
          .because(
              "serving reads models and routing, not application tables; RoutingRepository is"
                  + " exempt because it IS a repository (JdbcTemplate over model_routing) that"
                  + " happens to sit beside the router it serves");

  @ArchTest
  static final ArchRule inferenceMustNotDependOnIngest =
      ArchRuleDefinition.noClasses()
          .that()
          .resideInAPackage("..baseball.inference..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..baseball.ingest..")
          .because(
              "serving must not depend on the ingest pipeline; ingest feeds inference through the"
                  + " domain core and the database, never by direct import");

  /**
   * Freeze the layer graph, expressed as its load-bearing direction: the web layer ({@code api/})
   * must be a LEAF - nothing outside it may depend on it. FROZEN: the existing back-edges (the
   * data/ repositories importing api.dto, and any service-layer reach-ins) are baselined; a NEW
   * inward api dependency fails immediately.
   *
   * <p>Why not {@code slices().beFreeOfCycles()}: the package graph currently carries 12
   * multi-slice cycles, and ArchUnit's cycle descriptions render path-dependently, so a frozen
   * cycles rule fails line-matching on re-run (non-deterministic baseline). Per-dependency
   * violations freeze stably; full cycle-freedom is the C2+/Phase-3 target once the api leaf rule
   * and the read-side shared kernel (ADR-0015: repos return {@code domain/} projections or a {@code
   * PagedRows} carrier, never {@code api/dto}) drain the biggest back-edges.
   */
  @ArchTest
  static final ArchRule apiLayerMustBeALeaf =
      FreezingArchRule.freeze(
          ArchRuleDefinition.noClasses()
              .that()
              .resideOutsideOfPackage("..baseball.api..")
              .should()
              .dependOnClassesThat()
              .resideInAPackage("..baseball.api..")
              .because(
                  "controllers + DTOs are the outermost layer (design.md section 6); inward"
                      + " dependencies invert the layering and couple services to the HTTP"
                      + " contract"));
}
