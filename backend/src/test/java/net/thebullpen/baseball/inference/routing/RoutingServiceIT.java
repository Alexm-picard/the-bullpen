package net.thebullpen.baseball.inference.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
  @Autowired private RoutingChampionIntegrityCheck integrityCheck;
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
        // TEXT timestamps matching CURRENT_TIMESTAMP's format - the B2 recency cutoff compares
        // as TEXT, and a numeric epoch silently sorts below every TEXT value in SQLite.
        sqliteTs(Instant.now().minusSeconds(7200)),
        sqliteTs(Instant.now().minusSeconds(60)));
  }

  private static String sqliteTs(Instant instant) {
    return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(java.time.ZoneOffset.UTC)
        .format(instant);
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

  // --- champion-stage invariant (task #94, V011 bypass) -------------------

  /**
   * The carry-forward refusal: an admin write that merely preserves the current champion must still
   * refuse if that champion is no longer at CHAMPION stage. The stranded state is created by a RAW
   * stage flip on model_versions (which has no trigger, by design - see V020's header) - simulating
   * a row stranded before this guard existed, since every in-code path that archives a serving
   * champion now removes the routing row in the same transaction.
   */
  @Test
  void setMode_refuses_to_perpetuate_a_non_champion_reference() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("stale_champ_model", "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);
    jdbc.update("UPDATE model_versions SET stage = 'archived' WHERE id = ?", v1.id());

    assertThatThrownBy(() -> routing.setMode("stale_champ_model", RoutingMode.AB))
        .isInstanceOf(RoutingException.ChampionNotAtChampionStage.class)
        .hasMessageContaining("ARCHIVED");
  }

  @Test
  void setChallenger_refuses_to_perpetuate_a_non_champion_reference() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("stale_champ_sc_model", "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);
    ModelVersion v2 = registry.register(sampleRequest("stale_champ_sc_model", "v2"));
    registry.transitionStage(v2.id(), Stage.SHADOW);
    jdbc.update("UPDATE model_versions SET stage = 'archived' WHERE id = ?", v1.id());

    assertThatThrownBy(() -> routing.setChallenger("stale_champ_sc_model", v2.id()))
        .isInstanceOf(RoutingException.ChampionNotAtChampionStage.class);
  }

  @Test
  void setTrafficPct_refuses_to_perpetuate_a_non_champion_reference() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("stale_champ_tp_model", "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);
    routing.setMode("stale_champ_tp_model", RoutingMode.AB);
    jdbc.update("UPDATE model_versions SET stage = 'archived' WHERE id = ?", v1.id());

    assertThatThrownBy(() -> routing.setTrafficPct("stale_champ_tp_model", 10.0))
        .isInstanceOf(RoutingException.ChampionNotAtChampionStage.class);
  }

  @Test
  void clearChallenger_refuses_to_perpetuate_a_non_champion_reference() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("stale_champ_cc_model", "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);
    jdbc.update("UPDATE model_versions SET stage = 'archived' WHERE id = ?", v1.id());

    assertThatThrownBy(() -> routing.clearChallenger("stale_champ_cc_model"))
        .isInstanceOf(RoutingException.ChampionNotAtChampionStage.class);
  }

  /** Direct call with a never-promoted version: refused, and no row is written. */
  @Test
  void ensureRoutingForChampion_refuses_a_candidate_version() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("ensure_cand_model", "v1"));
    // v1 stays at CANDIDATE.

    assertThatThrownBy(() -> routing.ensureRoutingForChampion("ensure_cand_model", v1.id()))
        .isInstanceOf(RoutingException.ChampionNotAtChampionStage.class)
        .hasMessageContaining("CANDIDATE");
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM model_routing WHERE model_name = ?",
            Integer.class,
            "ensure_cand_model");
    assertThat(rows).isZero();
  }

  /**
   * The V011 bypass in its most reachable form: archiving a SERVING champion. Both in-code paths
   * that do this must drop the routing row in the same transaction - a surviving row would keep the
   * router serving a version outside the rule-5/rule-6 gates.
   */
  @Test
  void champion_to_archived_transition_removes_routing_row() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("arch_champ_model", "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);
    assertThat(routingRowCount("arch_champ_model")).isEqualTo(1); // anti-vacuity

    registry.transitionStage(v1.id(), Stage.ARCHIVED);
    assertThat(routingRowCount("arch_champ_model")).isZero();
  }

  @Test
  void bootstrap_reset_removes_routing_row() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("bootstrap_reset_model", "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);
    assertThat(routingRowCount("bootstrap_reset_model")).isEqualTo(1); // anti-vacuity

    registry.registerWithBootstrap(
        sampleRequest("bootstrap_reset_model", "v2"),
        new net.thebullpen.baseball.registry.dto.ResetFeatureSchemaConfirmation(
            "bootstrap_reset_model", "RoutingServiceIT: proving bootstrap drops routing"));
    assertThat(routingRowCount("bootstrap_reset_model")).isZero();
  }

  /**
   * Boot-time half of the invariant. The bean's throw-on-violation is what fails the boot
   * (SmartInitializingSingleton failures abort context refresh - framework contract, not re-proven
   * here); these tests pin the detection itself, both directions.
   */
  @Test
  void integrity_check_passes_on_a_legitimate_routing_row() throws Exception {
    bootstrapRouting("integrity_ok_model");
    assertThat(routingRowCount("integrity_ok_model")).isEqualTo(1); // the pass is not vacuous
    assertThatCode(() -> integrityCheck.afterSingletonsInstantiated()).doesNotThrowAnyException();
  }

  // --- cross-model references (rule 9) ------------------------------------

  /** A champion of the WRONG MODEL is refused even though its stage IS champion. */
  @Test
  void ensureRoutingForChampion_refuses_another_models_champion() throws Exception {
    ModelVersion owner = registry.register(sampleRequest("xm_owner_model", "v1"));
    registry.transitionStage(owner.id(), Stage.CHAMPION);

    assertThatThrownBy(() -> routing.ensureRoutingForChampion("xm_other_model", owner.id()))
        .isInstanceOf(RoutingException.ChampionNotAtChampionStage.class)
        .hasMessageContaining("belongs to model")
        .hasMessageContaining("xm_owner_model");
    assertThat(routingRowCount("xm_other_model")).isZero();
  }

  @Test
  void setChallenger_refuses_another_models_shadow_version() throws Exception {
    bootstrapRouting("xm_ch_model");
    ModelVersion other = registry.register(sampleRequest("xm_ch_other", "v1"));
    registry.transitionStage(other.id(), Stage.SHADOW);

    assertThatThrownBy(() -> routing.setChallenger("xm_ch_model", other.id()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rule 9");
  }

  /**
   * The boot scan must flag a routing row referencing another model's version. The state is created
   * by RAW re-homing the version on model_versions (no trigger there, by design) - the routing-side
   * triggers make the row itself impossible to WRITE cross-model, so drift on the versions side is
   * the only way this state can exist.
   */
  @Test
  void integrity_check_flags_a_cross_model_reference() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("xm_int_model", "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);
    jdbc.update("UPDATE model_versions SET model_name = 'xm_int_other' WHERE id = ?", v1.id());

    assertThatThrownBy(() -> integrityCheck.afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("xm_int_model")
        .hasMessageContaining("<no matching model_versions row>");
  }

  @Test
  void integrity_check_throws_on_a_stranded_routing_row() throws Exception {
    ModelVersion v1 = registry.register(sampleRequest("integrity_bad_model", "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);
    jdbc.update("UPDATE model_versions SET stage = 'archived' WHERE id = ?", v1.id());

    assertThatThrownBy(() -> integrityCheck.afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("integrity_bad_model")
        .hasMessageContaining("archived");
  }

  private int routingRowCount(String modelName) {
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM model_routing WHERE model_name = ?", Integer.class, modelName);
    return rows == null ? 0 : rows;
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
