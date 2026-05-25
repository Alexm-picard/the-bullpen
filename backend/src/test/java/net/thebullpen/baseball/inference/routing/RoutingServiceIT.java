package net.thebullpen.baseball.inference.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration test for {@link RoutingService} — exercises auto-row creation on first CHAMPION
 * promotion (leaf "Known edge cases"), every validation rule mapped to a {@link RoutingException}
 * subclass, and the Caffeine cache-eviction discipline.
 *
 * <p>Same isolation pattern as {@code RegistryServiceIT}: temp SQLite + temp snapshot base via
 * {@code @DynamicPropertySource}.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class RoutingServiceIT {

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-routing-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-routing-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private RegistryService registry;
  @Autowired private RoutingService routing;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private CacheManager cacheManager;

  @TempDir Path artifactDir;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM experiment_results");
    jdbc.update("DELETE FROM model_routing");
    jdbc.update("DELETE FROM model_versions");
    // Cache is per-context; evict everything so prior tests don't leak entries.
    cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
  }

  /** Seed a passing experiment row so 2nd+ promotions pass the rule-5 gate. */
  private void seedPassingExperiment(String modelName, long championId, long challengerId) {
    jdbc.update(
        "INSERT INTO experiment_results (model_name, champion_version_id, challenger_version_id,"
            + " started_at, ended_at, primary_metric, primary_threshold, guardrails,"
            + " sample_size_target, sample_size_observed, champion_metric, challenger_metric,"
            + " guardrails_observed, status, notes)"
            + " VALUES (?, ?, ?, ?, ?, 'brier', 0.20, '{}', 10000, 12345, 0.185, 0.172, '{}',"
            + " 'passed', 'seeded by RoutingServiceIT')",
        modelName,
        championId,
        challengerId,
        java.sql.Timestamp.from(Instant.now().minusSeconds(7200)),
        java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
  }

  // --- auto-create on first CHAMPION promotion ---------------------------

  @Test
  void first_champion_promotion_auto_creates_routing_row_in_shadow_mode() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("auto_routing_model", "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);

    RoutingConfig cfg = routing.getRouting("auto_routing_model");
    assertThat(cfg.championVersionId()).isEqualTo(v1.id());
    assertThat(cfg.challengerVersionId()).isNull();
    assertThat(cfg.challengerTrafficPct()).isEqualTo(0.0);
    assertThat(cfg.mode()).isEqualTo(RoutingMode.SHADOW);
  }

  @Test
  void second_champion_promotion_updates_existing_routing_row() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("update_routing_model", "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);
    ModelVersion v2 = registry.register(sampleRequest("update_routing_model", "v2"));
    registry.transitionStage(v2.id(), Stage.SHADOW);
    seedPassingExperiment("update_routing_model", v1.id(), v2.id());
    registry.transitionStage(v2.id(), Stage.CHAMPION);

    RoutingConfig cfg = routing.getRouting("update_routing_model");
    assertThat(cfg.championVersionId()).isEqualTo(v2.id());
    // v2 was the challenger; getting promoted to champion clears its old challenger slot.
    assertThat(cfg.challengerVersionId()).isNull();
    assertThat(cfg.challengerTrafficPct()).isEqualTo(0.0);
    assertThat(cfg.mode()).isEqualTo(RoutingMode.SHADOW);
  }

  // --- setChallenger validations -----------------------------------------

  @Test
  void setChallenger_with_shadow_version_succeeds() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("sc_ok_model", "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);
    ModelVersion v2 = registry.register(sampleRequest("sc_ok_model", "v2"));
    registry.transitionStage(v2.id(), Stage.SHADOW);

    RoutingConfig cfg = routing.setChallenger("sc_ok_model", v2.id());
    assertThat(cfg.challengerVersionId()).isEqualTo(v2.id());
    assertThat(cfg.challengerTrafficPct()).isEqualTo(0.0);
  }

  @Test
  void setChallenger_with_champion_version_throws() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("sc_same_model", "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);

    assertThatThrownBy(() -> routing.setChallenger("sc_same_model", v1.id()))
        .isInstanceOf(RoutingException.ChallengerSameAsChampion.class);
  }

  @Test
  void setChallenger_with_candidate_stage_throws() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("sc_cand_model", "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);
    ModelVersion v2 = registry.register(sampleRequest("sc_cand_model", "v2"));
    // v2 stays at CANDIDATE.

    assertThatThrownBy(() -> routing.setChallenger("sc_cand_model", v2.id()))
        .isInstanceOf(RoutingException.ChallengerNotInShadow.class)
        .hasMessageContaining("CANDIDATE");
  }

  @Test
  void setChallenger_for_unknown_model_throws() {
    assertThatThrownBy(() -> routing.setChallenger("nonexistent_model", 42L))
        .isInstanceOf(RoutingException.UnknownModel.class);
  }

  // --- setTrafficPct validations -----------------------------------------

  @Test
  void setTrafficPct_below_zero_throws() throws Exception {
    bootstrapRouting("tp_neg_model");
    assertThatThrownBy(() -> routing.setTrafficPct("tp_neg_model", -1.0))
        .isInstanceOf(RoutingException.InvalidTrafficPct.class);
  }

  @Test
  void setTrafficPct_above_hundred_throws() throws Exception {
    bootstrapRouting("tp_high_model");
    assertThatThrownBy(() -> routing.setTrafficPct("tp_high_model", 100.01))
        .isInstanceOf(RoutingException.InvalidTrafficPct.class);
  }

  @Test
  void setTrafficPct_nonzero_in_shadow_mode_throws() throws Exception {
    bootstrapRouting("tp_shadow_model");
    assertThatThrownBy(() -> routing.setTrafficPct("tp_shadow_model", 10.0))
        .isInstanceOf(RoutingException.ShadowModeWithTraffic.class);
  }

  @Test
  void setTrafficPct_nonzero_in_ab_mode_succeeds() throws Exception {
    bootstrapRouting("tp_ab_model");
    routing.setMode("tp_ab_model", RoutingMode.AB);
    RoutingConfig cfg = routing.setTrafficPct("tp_ab_model", 25.0);
    assertThat(cfg.challengerTrafficPct()).isEqualTo(25.0);
  }

  // --- setMode ------------------------------------------------------------

  @Test
  void setMode_to_shadow_resets_traffic_pct_to_zero() throws Exception {
    bootstrapRouting("mode_reset_model");
    routing.setMode("mode_reset_model", RoutingMode.AB);
    routing.setTrafficPct("mode_reset_model", 50.0);
    RoutingConfig cfg = routing.setMode("mode_reset_model", RoutingMode.SHADOW);
    assertThat(cfg.mode()).isEqualTo(RoutingMode.SHADOW);
    assertThat(cfg.challengerTrafficPct()).isEqualTo(0.0);
  }

  // --- clearChallenger ----------------------------------------------------

  @Test
  void clearChallenger_removes_challenger_and_resets_to_shadow() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("clear_model", "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);
    ModelVersion v2 = registry.register(sampleRequest("clear_model", "v2"));
    registry.transitionStage(v2.id(), Stage.SHADOW);
    routing.setChallenger("clear_model", v2.id());
    routing.setMode("clear_model", RoutingMode.AB);
    routing.setTrafficPct("clear_model", 30.0);

    RoutingConfig cfg = routing.clearChallenger("clear_model");
    assertThat(cfg.challengerVersionId()).isNull();
    assertThat(cfg.mode()).isEqualTo(RoutingMode.SHADOW);
    assertThat(cfg.challengerTrafficPct()).isEqualTo(0.0);
  }

  // --- cache invalidation -------------------------------------------------

  @Test
  void cache_invalidates_on_write_within_same_test_thread() throws Exception {
    bootstrapRouting("cache_model");
    RoutingConfig pre = routing.getRouting("cache_model");
    assertThat(pre.mode()).isEqualTo(RoutingMode.SHADOW);

    routing.setMode("cache_model", RoutingMode.AB);
    RoutingConfig post = routing.getRouting("cache_model");
    assertThat(post.mode())
        .as("write to cached model should evict the entry; next read sees the new mode")
        .isEqualTo(RoutingMode.AB);
  }

  @Test
  void cache_returns_same_instance_on_repeat_read_without_write() throws Exception {
    bootstrapRouting("cache_hit_model");
    RoutingConfig first = routing.getRouting("cache_hit_model");
    RoutingConfig second = routing.getRouting("cache_hit_model");
    // Cached returns the same reference (Caffeine doesn't copy).
    assertThat(second).isSameAs(first);
  }

  // --- helpers ----------------------------------------------------------

  /**
   * Register + promote to CHAMPION (single-version → bootstrap-exempt gate). Auto-creates the
   * routing row via the ensureRoutingForChampion hook.
   */
  private void bootstrapRouting(String modelName) throws Exception {
    ModelVersion v1 = registry.register(sampleRequest(modelName, "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);
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
        "routing-it",
        "registered by RoutingServiceIT");
  }
}
