package net.thebullpen.baseball.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import net.thebullpen.baseball.registry.ExperimentResultsRepository;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ExperimentResult;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.experiment.ExperimentException;
import net.thebullpen.baseball.registry.experiment.OfflineGateImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Closes the [166]/ADR-0012 integration loop: a committed OFFLINE non-inferiority gate (negative
 * threshold, scored offline) cannot enter via the online start/evaluate/complete lifecycle, so the
 * {@code import-offline} path turns it into the terminal {@code passed} experiment_results row the
 * promote gate reads. This IT proves the row is ACTUALLY readable by {@code
 * assertPromotionCriteriaMet} end-to-end: register v1 -&gt; promote (bootstrap) -&gt; register v2
 * -&gt; import gate -&gt; promote v2 SUCCEEDS. Plus the anti-bypass validations (only bundled,
 * self-consistent, current-champion-bound evidence imports).
 *
 * <p>The load gate is disabled here (this IT exercises the rule-5 evidence gate on stub snapshots,
 * not ONNX loading - that is {@link PromotionLoadGateIT}'s job). The committed test evidence is
 * {@code src/test/resources/offline-gate-evidence/test_promotion_gate*.json} (model
 * carry_it_model).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"api", "registry-controller-it"})
// Each method mutates the shared registry (promotes/archives), so isolate: a fresh context (and a
// fresh random SQLite via @DynamicPropertySource) per method.
@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
class OfflineGateImportIT {

  private static final String ADMIN_USER = "it-admin";
  private static final String ADMIN_PASS = "it-password";
  private static final String BASIC =
      "Basic " + Base64.getEncoder().encodeToString((ADMIN_USER + ":" + ADMIN_PASS).getBytes());
  private static final String MODEL = "carry_it_model";

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-offline-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    registry.add("bullpen.admin.basicauth", () -> ADMIN_USER + ":" + ADMIN_PASS);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"), "bullpen-offline-it-snap-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
    // Disable the INC-2 load gate: this IT exercises the rule-5 evidence gate on stub snapshots.
    registry.add("bullpen.registry.promotion-load-gate.enabled", () -> "false");
  }

  @Autowired private MockMvc mvc;
  @Autowired private RegistryService service;
  @Autowired private ExperimentResultsRepository experiments;
  @Autowired private OfflineGateImportService offlineImport;
  @Autowired private ObjectMapper mapper;
  @TempDir Path artifactDir;

  @Test
  void imported_offline_gate_row_satisfies_the_promote_gate_end_to_end() throws Exception {
    // v1 is the first version -> bootstrap-promote to champion (no experiment row needed).
    ModelVersion v1 = service.register(stub("v1"));
    promote(v1.id()).andExpect(status().isOk());
    assertThat(service.findChampion(MODEL).orElseThrow().id()).isEqualTo(v1.id());

    // v2 re-arms rule 5 (bootstrap is first-version-only) -> it needs a passing row.
    ModelVersion v2 = service.register(stub("v2"));

    // Import the committed offline gate as the v2-vs-v1 passing row.
    ExperimentResult row =
        offlineImport.importGate(MODEL, v1.id(), v2.id(), "test_promotion_gate.json", "IT");
    assertThat(row.status()).isEqualTo(ExperimentResult.Status.PASSED);
    assertThat(row.championVersionId()).isEqualTo(v1.id());
    assertThat(row.challengerVersionId()).isEqualTo(v2.id());
    assertThat(row.primaryThreshold()).isEqualTo(-0.002); // the non-inferiority margin survived

    // The EXACT query assertPromotionCriteriaMet runs now finds the row.
    assertThat(
            experiments.findLatestPassing(
                MODEL, v2.id(), v1.id(), Instant.now().minusSeconds(3600)))
        .isPresent();

    // End-to-end: promoting v2 through the REAL gate now SUCCEEDS (the gap is closed).
    promote(v2.id()).andExpect(status().isOk());
    assertThat(service.findChampion(MODEL).orElseThrow().id()).isEqualTo(v2.id());
    assertThat(service.getById(v1.id()).orElseThrow().stage().name()).isEqualTo("ARCHIVED");
  }

  @Test
  void import_rejects_an_unbundled_artifact() {
    // Rejected at the artifact lookup (step 1), before any registry interaction.
    assertThatThrownBy(() -> offlineImport.importGate(MODEL, 1L, 2L, "nope.json", "IT"))
        .isInstanceOf(ExperimentException.OfflineGateInvalid.class)
        .hasMessageContaining("no committed offline-gate artifact");
  }

  @Test
  void import_rejects_a_failed_carry_gate_even_when_status_says_passed() {
    // test_promotion_gate_badcarry.json: status="passed" but carry_gate.passed=false. The importer
    // RE-DERIVES the pass (step 2, before the champion check), so it rejects regardless of status.
    assertThatThrownBy(
            () ->
                offlineImport.importGate(MODEL, 1L, 2L, "test_promotion_gate_badcarry.json", "IT"))
        .isInstanceOf(ExperimentException.OfflineGateInvalid.class)
        .hasMessageContaining("self-consistent PASS");
  }

  @Test
  void import_rejects_a_declared_pass_whose_metrics_do_not_pass() {
    // test_promotion_gate_inconsistent.json: status="passed"/verdict.passed=true, but
    // challenger_metric (0.20) + threshold (-0.002) > champion_metric (0.116) -> the importer
    // re-derives the primary from the numerics and rejects (NOTE 2: declared booleans are not
    // trusted).
    assertThatThrownBy(
            () ->
                offlineImport.importGate(
                    MODEL, 1L, 2L, "test_promotion_gate_inconsistent.json", "IT"))
        .isInstanceOf(ExperimentException.OfflineGateInvalid.class)
        .hasMessageContaining("primary_met=false");
  }

  @Test
  void import_rejects_a_champion_that_is_not_the_current_champion() throws Exception {
    ModelVersion v1 = service.register(stub("v1"));
    promote(v1.id()).andExpect(status().isOk());
    ModelVersion v2 = service.register(stub("v2"));
    // Pass v2 as the "champion" - it is not the current champion (v1 is) -> rejected.
    assertThatThrownBy(
            () ->
                offlineImport.importGate(MODEL, v2.id(), v1.id(), "test_promotion_gate.json", "IT"))
        .isInstanceOf(ExperimentException.OfflineGateInvalid.class)
        .hasMessageContaining("not the CURRENT champion");
  }

  // --- helpers ----------------------------------------------------------

  private org.springframework.test.web.servlet.ResultActions promote(long versionId)
      throws Exception {
    return mvc.perform(
        post("/v1/admin/registry/" + MODEL + "/promote/" + versionId)
            .header("Authorization", BASIC)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                mapper.writeValueAsString(
                    Map.of("targetStage", "champion", "reason", "offline-gate import IT"))));
  }

  private RegisterRequest stub(String version) throws Exception {
    Path artifact = write(version + "-model.onnx", "stub-not-a-real-onnx");
    Path metadata = write(version + "-meta.json", "{}");
    Path pipeline =
        write(
            version + "-pipeline.json",
            "{\"model_name\":\""
                + MODEL
                + "\",\"pipeline_version\":\"1.0.0\",\"feature_order\":[\"a\",\"b\"],"
                + "\"schema_hash\":\"recomputed-on-load\"}");
    return new RegisterRequest(
        MODEL,
        version,
        artifact.toString(),
        metadata.toString(),
        pipeline.toString(),
        "train-h-" + version,
        "[2024-01-01,2024-12-31]",
        "{\"brier\":0.18}",
        Instant.now(),
        "offline-gate-it",
        "registered by OfflineGateImportIT");
  }

  private Path write(String name, String content) throws Exception {
    Path p = artifactDir.resolve(name);
    Files.writeString(p, content);
    return p;
  }
}
