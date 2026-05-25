package net.thebullpen.baseball.registry.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ExperimentResult;
import net.thebullpen.baseball.registry.dto.ExperimentResult.Status;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.dto.Stage;
import net.thebullpen.baseball.registry.experiment.dto.ExperimentVerdict;
import net.thebullpen.baseball.registry.experiment.dto.PrimaryMetric;
import net.thebullpen.baseball.registry.experiment.dto.StartExperimentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration test for {@link ExperimentService} — exercises every status outcome (running, passed,
 * failed-primary, failed-guardrail, aborted) using a controllable {@link PairedPredictionFetcher}
 * stub.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
@Import(ExperimentServiceIT.TestFetcherConfig.class)
class ExperimentServiceIT {

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-experiment-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-experiment-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  /**
   * Replaces the production {@link StubPairedPredictionFetcher} with a controllable list-of- pairs
   * so each test sets up its own scenario.
   */
  @TestConfiguration
  static class TestFetcherConfig {
    @Bean
    @Primary
    PairedPredictionFetcher controllableFetcher() {
      return new ControllableFetcher();
    }
  }

  static class ControllableFetcher implements PairedPredictionFetcher {
    List<PairedPrediction> nextPairs = new ArrayList<>();

    @Override
    public List<PairedPrediction> fetch(
        String modelName,
        String championVersion,
        String challengerVersion,
        Instant since,
        Instant until) {
      return nextPairs;
    }
  }

  @Autowired private RegistryService registry;
  @Autowired private ExperimentService experiments;
  @Autowired private PairedPredictionFetcher fetcher;
  @Autowired private JdbcTemplate jdbc;

  @TempDir Path artifactDir;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM experiment_results");
    jdbc.update("DELETE FROM model_routing");
    jdbc.update("DELETE FROM model_versions");
    ((ControllableFetcher) fetcher).nextPairs = new ArrayList<>();
  }

  // --- start lifecycle --------------------------------------------------

  @Test
  void start_inserts_running_row_with_pre_declared_criteria() throws Exception {
    long champId = registerAndPromoteChampion("start_ok_model");
    long challId = registerShadow("start_ok_model", champId);

    StartExperimentRequest req =
        new StartExperimentRequest(
            "start_ok_model",
            champId,
            challId,
            PrimaryMetric.BRIER,
            0.005,
            10L,
            Map.of("ece", 0.02),
            "v2 looks like a Brier improvement on the held-out set");
    ExperimentResult inserted = experiments.start(req);

    assertThat(inserted.status()).isEqualTo(Status.RUNNING);
    assertThat(inserted.modelName()).isEqualTo("start_ok_model");
    assertThat(inserted.championVersionId()).isEqualTo(champId);
    assertThat(inserted.challengerVersionId()).isEqualTo(challId);
    assertThat(inserted.primaryMetric()).isEqualTo("brier");
    assertThat(inserted.primaryThreshold()).isEqualTo(0.005);
    assertThat(inserted.sampleSizeTarget()).isEqualTo(10L);
    assertThat(inserted.startedAt()).isNotNull();
    assertThat(inserted.endedAt()).isNull();
  }

  @Test
  void start_with_another_running_experiment_throws_AlreadyRunning() throws Exception {
    long champ = registerAndPromoteChampion("ar_model");
    long chall = registerShadow("ar_model", champ);
    experiments.start(sampleStart("ar_model", champ, chall));

    long otherChall = registerShadowVersion("ar_model", "v3", champ);
    assertThatThrownBy(() -> experiments.start(sampleStart("ar_model", champ, otherChall)))
        .isInstanceOf(ExperimentException.AlreadyRunning.class);
  }

  // --- evaluate ---------------------------------------------------------

  @Test
  void evaluate_with_empty_pairs_returns_would_fail_primary_and_zero_sample() throws Exception {
    long champ = registerAndPromoteChampion("ev_empty_model");
    long chall = registerShadow("ev_empty_model", champ);
    ExperimentResult exp = experiments.start(sampleStart("ev_empty_model", champ, chall));

    ExperimentVerdict verdict = experiments.evaluate(exp.id());
    assertThat(verdict.outcome()).isEqualTo(ExperimentVerdict.Outcome.WOULD_FAIL_PRIMARY);
    assertThat(verdict.sampleSizeObserved()).isEqualTo(0L);
  }

  @Test
  void evaluate_with_challenger_clearly_better_returns_would_pass() throws Exception {
    long champ = registerAndPromoteChampion("ev_pass_model");
    long chall = registerShadow("ev_pass_model", champ);
    ExperimentResult exp = experiments.start(sampleStart("ev_pass_model", champ, chall));
    // Champion: 60% confidence in truth. Challenger: 90%. Brier delta easily exceeds 0.005.
    setPairs(buildPairs(20, new double[] {0.6, 0.4}, new double[] {0.9, 0.1}, 0));

    ExperimentVerdict verdict = experiments.evaluate(exp.id());
    assertThat(verdict.outcome()).isEqualTo(ExperimentVerdict.Outcome.WOULD_PASS);
    assertThat(verdict.sampleSizeObserved()).isEqualTo(20L);
    assertThat(verdict.challengerMetric()).isLessThan(verdict.championMetric());
  }

  @Test
  void evaluate_with_challenger_only_marginally_better_returns_would_fail_primary()
      throws Exception {
    long champ = registerAndPromoteChampion("ev_marginal_model");
    long chall = registerShadow("ev_marginal_model", champ);
    ExperimentResult exp = experiments.start(sampleStart("ev_marginal_model", champ, chall));
    // Champion 0.80, challenger 0.81 → Brier delta way under the 0.005 threshold.
    setPairs(buildPairs(20, new double[] {0.80, 0.20}, new double[] {0.81, 0.19}, 0));

    ExperimentVerdict verdict = experiments.evaluate(exp.id());
    assertThat(verdict.outcome()).isEqualTo(ExperimentVerdict.Outcome.WOULD_FAIL_PRIMARY);
  }

  // --- complete ---------------------------------------------------------

  @Test
  void complete_with_passing_data_marks_row_passed_and_records_metrics() throws Exception {
    long champ = registerAndPromoteChampion("cp_pass_model");
    long chall = registerShadow("cp_pass_model", champ);
    ExperimentResult exp = experiments.start(sampleStart("cp_pass_model", champ, chall));
    setPairs(buildPairs(50, new double[] {0.5, 0.5}, new double[] {0.95, 0.05}, 0));

    ExperimentResult after = experiments.complete(exp.id());
    assertThat(after.status()).isEqualTo(Status.PASSED);
    assertThat(after.endedAt()).isNotNull();
    assertThat(after.sampleSizeObserved()).isEqualTo(50L);
    assertThat(after.championMetric()).isNotNull();
    assertThat(after.challengerMetric()).isNotNull();
    assertThat(after.challengerMetric()).isLessThan(after.championMetric());
  }

  @Test
  void complete_with_insufficient_sample_throws_InsufficientSampleSize() throws Exception {
    long champ = registerAndPromoteChampion("cp_few_model");
    long chall = registerShadow("cp_few_model", champ);
    // Target is 10 (from sampleStart); provide only 3 pairs.
    ExperimentResult exp = experiments.start(sampleStart("cp_few_model", champ, chall));
    setPairs(buildPairs(3, new double[] {0.6, 0.4}, new double[] {0.9, 0.1}, 0));

    assertThatThrownBy(() -> experiments.complete(exp.id()))
        .isInstanceOf(ExperimentException.InsufficientSampleSize.class);
    // Status must stay running.
    assertThat(experiments.getById(exp.id()).status()).isEqualTo(Status.RUNNING);
  }

  @Test
  void complete_with_guardrail_violation_marks_row_failed() throws Exception {
    long champ = registerAndPromoteChampion("cp_guardrail_model");
    long chall = registerShadow("cp_guardrail_model", champ);
    // Primary metric BRIER passes (challenger much better), BUT log_loss regresses badly.
    // We construct pairs where challenger's max-prob is overconfident-wrong on half: champion
    // hedges 0.6/0.4 and is right half-the-time; challenger predicts 0.99 for class 0 but is
    // wrong 50% of the time → log_loss spikes.
    StartExperimentRequest req =
        new StartExperimentRequest(
            "cp_guardrail_model",
            champ,
            chall,
            PrimaryMetric.BRIER,
            0.001,
            10L,
            Map.of("log_loss", 0.5), // max allowed log_loss delta = 0.5
            "should pass brier but fail log_loss");
    ExperimentResult exp = experiments.start(req);

    List<PairedPrediction> pairs = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      // truth alternates 0,1,0,1,... — challenger always says class-0 at 0.99 conf, so half
      // its predictions are wrong with very high confidence → log_loss explodes.
      int truth = i % 2;
      pairs.add(
          new PairedPrediction(i, new double[] {0.55, 0.45}, new double[] {0.99, 0.01}, truth));
    }
    setPairs(pairs);

    ExperimentResult after = experiments.complete(exp.id());
    assertThat(after.status()).isEqualTo(Status.FAILED);
  }

  // --- abort ------------------------------------------------------------

  @Test
  void abort_running_experiment_flips_to_aborted_no_metrics() throws Exception {
    long champ = registerAndPromoteChampion("ab_model");
    long chall = registerShadow("ab_model", champ);
    ExperimentResult exp = experiments.start(sampleStart("ab_model", champ, chall));

    ExperimentResult after = experiments.abort(exp.id(), "regretted starting it");
    assertThat(after.status()).isEqualTo(Status.ABORTED);
    assertThat(after.endedAt()).isNotNull();
    assertThat(after.championMetric()).isNull();
    assertThat(after.challengerMetric()).isNull();
  }

  @Test
  void abort_terminal_experiment_throws_InvalidStateTransition() throws Exception {
    long champ = registerAndPromoteChampion("ab_terminal_model");
    long chall = registerShadow("ab_terminal_model", champ);
    ExperimentResult exp = experiments.start(sampleStart("ab_terminal_model", champ, chall));
    experiments.abort(exp.id(), "first abort");

    assertThatThrownBy(() -> experiments.abort(exp.id(), "second abort"))
        .isInstanceOf(ExperimentException.InvalidStateTransition.class);
  }

  @Test
  void evaluate_or_complete_for_unknown_id_throws_UnknownExperiment() {
    assertThatThrownBy(() -> experiments.evaluate(99999L))
        .isInstanceOf(ExperimentException.UnknownExperiment.class);
    assertThatThrownBy(() -> experiments.complete(99999L))
        .isInstanceOf(ExperimentException.UnknownExperiment.class);
  }

  // --- helpers ----------------------------------------------------------

  private void setPairs(List<PairedPrediction> pairs) {
    ((ControllableFetcher) fetcher).nextPairs = pairs;
  }

  /** Build N identical pairs (champion + challenger probs + truth class). */
  private static List<PairedPrediction> buildPairs(
      int n, double[] champProbs, double[] challProbs, int truthClass) {
    List<PairedPrediction> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(new PairedPrediction(i, champProbs.clone(), challProbs.clone(), truthClass));
    }
    return out;
  }

  private StartExperimentRequest sampleStart(String modelName, long champId, long challId) {
    return new StartExperimentRequest(
        modelName, champId, challId, PrimaryMetric.BRIER, 0.005, 10L, Map.of(), "test experiment");
  }

  private long registerAndPromoteChampion(String modelName) throws Exception {
    ModelVersion v = registry.register(sampleRequest(modelName, "v1"));
    registry.transitionStage(v.id(), Stage.CHAMPION);
    return v.id();
  }

  private long registerShadow(String modelName, long championId) throws Exception {
    return registerShadowVersion(modelName, "v2", championId);
  }

  private long registerShadowVersion(String modelName, String version, long championId)
      throws Exception {
    ModelVersion v = registry.register(sampleRequest(modelName, version));
    registry.transitionStage(v.id(), Stage.SHADOW);
    return v.id();
  }

  private RegisterRequest sampleRequest(String modelName, String version) throws Exception {
    Path artifact = artifactDir.resolve(modelName + "-" + version + "-model.onnx");
    Files.writeString(artifact, "stub");
    Path metadata = artifactDir.resolve(modelName + "-" + version + "-metadata.json");
    Files.writeString(metadata, "{}");
    Path pipeline = artifactDir.resolve(modelName + "-" + version + "-pipeline.json");
    Files.writeString(
        pipeline,
        "{\"model_name\":\""
            + modelName
            + "\",\"pipeline_version\":\"1\",\"feature_order\":[\"x\"],\"schema_hash\":\"\"}");
    return new RegisterRequest(
        modelName,
        version,
        artifact.toString(),
        metadata.toString(),
        pipeline.toString(),
        "train-h-" + version,
        "[2024-01-01,2024-12-31]",
        "{\"brier\":0.18}",
        Instant.now(),
        "experiment-it",
        "registered by ExperimentServiceIT");
  }
}
