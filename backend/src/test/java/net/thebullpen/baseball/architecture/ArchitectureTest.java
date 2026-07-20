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
 * while any NEW violation fails immediately. C2 removes the baselined violations (the 8 {@code
 * data/} repositories returning {@code api.dto} types get boundary mappers) and drains the store;
 * once a store file is empty the rule is effectively strict.
 *
 * <p><b>Strict rules</b> (no baseline): {@code domain/} purity - the hexagonal-lite core must stay
 * a plain-records module (today it holds only {@code GameMatchup}; keeping the rule strict stops
 * the first impurity from ever landing).
 */
@AnalyzeClasses(
    packages = "net.thebullpen.baseball",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /**
   * data/ must not depend on the web layer's DTOs. FROZEN: 8 known repositories currently return
   * api.dto types (PredictionLog, LivePitches, OpsEvents, PlayerPredictions, BatterBattedBalls,
   * PitcherArsenal, Calibration, Player) - baselined, drained by C2's boundary mappers.
   */
  @ArchTest
  static final ArchRule dataMustNotDependOnApiDtos =
      FreezingArchRule.freeze(
          ArchRuleDefinition.noClasses()
              .that()
              .resideInAPackage("..baseball.data..")
              .should()
              .dependOnClassesThat()
              .resideInAPackage("..baseball.api.dto..")
              .because(
                  "repositories are the persistence boundary; returning web DTOs couples SQL row"
                      + " shapes to the HTTP contract (C2 introduces mapping at the api boundary)"));

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
   * Freeze the layer graph, expressed as its load-bearing direction: the web layer ({@code api/})
   * must be a LEAF - nothing outside it may depend on it. FROZEN: the existing back-edges (the
   * data/ repositories importing api.dto, and any service-layer reach-ins) are baselined; a NEW
   * inward api dependency fails immediately.
   *
   * <p>Why not {@code slices().beFreeOfCycles()}: the package graph currently carries 12
   * multi-slice cycles, and ArchUnit's cycle descriptions render path-dependently, so a frozen
   * cycles rule fails line-matching on re-run (non-deterministic baseline). Per-dependency
   * violations freeze stably; full cycle-freedom is the C2+/Phase-3 target once the api leaf rule
   * and the dto mappers drain the biggest back-edges.
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
