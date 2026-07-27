package net.thebullpen.baseball.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import net.thebullpen.baseball.inference.PredictionLogWriter;
import net.thebullpen.baseball.inference.routing.RoutingService;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A10: the two behaviours the #368 router integration changed, pinned end to end - the no-model 503
 * fallback, and the SHADOW dual-log carrying {@code role=shadow} with the challenger's FK.
 *
 * <p>Composes the two established harnesses rather than inventing a third: {@code
 * PredictPitchRoutingIT}'s shape (SQLite registry + real ORT fixture + {@code CapturingLogger}, a
 * {@code @Primary} subclass, no Mockito) plus {@code PitchTypeArsenalParityIT}'s ClickHouse
 * container - which pitch-outcome's IT does not need but this one cannot avoid: {@code
 * PitchTypePredictionService} derives the career arsenal from real tables BEFORE routing, and the
 * service bean itself is conditional on {@code bullpen.clickhouse.enabled}. Booting the api profile
 * with the container proved out in the parity IT: the migration runner applies V001..V030 on
 * startup, so the snapshot table exists without hand-run DDL.
 *
 * <p>Docker-gated like every ClickHouse IT - CI is its first real execution.
 *
 * <p>Deliberately pinned here per decision [183]: the HTTP response carries NO winner field. The
 * contract test asserts that reflectively on the record; this asserts it on the actual wire JSON.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"api", "registry-controller-it"})
@Testcontainers
@EnabledIfSystemProperty(
    named = "bullpen.it.docker",
    matches = "true",
    disabledReason =
        "Docker Desktop on macOS returns malformed /info responses to Testcontainers"
            + "; set -Dbullpen.it.docker=true to force-run in CI.")
class PredictPitchTypeRoutingIT {

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path CONTRACT =
      REPO_ROOT.resolve("contracts/feature_pipeline_pitchtype.json");
  private static final String MODEL_NAME = "pitch_type_pre";
  private static final String BASELINE = "pitch_type_lr_baseline";
  private static final ZoneId ET = ZoneId.of("America/New_York");
  private static final long PITCHER = 4242L;

  @Container
  static final ClickHouseContainer CH =
      new ClickHouseContainer("clickhouse/clickhouse-server:24.12-alpine")
          .withUsername("default")
          .withPassword("test");

  @TestConfiguration
  static class CaptureConfig {
    @Bean
    @Primary
    PredictPitchRoutingIT.CapturingLogger capturingLogger(
        Optional<PredictionLogWriter> writer, MeterRegistry registry) {
      return new PredictPitchRoutingIT.CapturingLogger(writer, registry);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("bullpen.clickhouse.enabled", () -> "true");
    registry.add("bullpen.clickhouse.url", CH::getJdbcUrl);
    registry.add("bullpen.clickhouse.user", CH::getUsername);
    registry.add("bullpen.clickhouse.password", CH::getPassword);

    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-pitchtype-routing-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    registry.add("bullpen.admin.basicauth", () -> "it-admin:it-password");
    registry.add(
        "bullpen.snapshot.local-base-path",
        () ->
            Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "bullpen-pitchtype-routing-it-snap-" + UUID.randomUUID())
                .toString());
  }

  @Autowired private MockMvc mvc;
  @Autowired private RegistryService registryService;
  @Autowired private RoutingService routingService;
  @Autowired private PredictPitchRoutingIT.CapturingLogger logger;
  @Autowired private JdbcTemplate registryJdbc;
  @Autowired private CacheManager cacheManager;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private DataSource clickhouseDs;

  private JdbcTemplate ch;

  @TempDir Path sourceDir;

  @BeforeEach
  void reset() {
    registryJdbc.update("DELETE FROM experiment_results");
    registryJdbc.update("DELETE FROM model_routing");
    registryJdbc.update("DELETE FROM model_versions");
    cacheManager
        .getCacheNames()
        .forEach(
            name -> {
              var c = cacheManager.getCache(name);
              if (c != null) {
                c.clear();
              }
            });
    logger.events.clear();
    ch = new JdbcTemplate(clickhouseDs);
    ch.execute("TRUNCATE TABLE IF EXISTS pitcher_pitchtype_prior_current");
    ch.execute("TRUNCATE TABLE IF EXISTS pitches_live");
  }

  // --- the two behaviours under pin -----------------------------------------

  @Test
  void no_model_returns_503_not_404_and_logs_nothing() throws Exception {
    // The prior EXISTS, so the only remaining 503 source is the router fallback: pitch_type_pre
    // has no champion and no routing row, and per #360's corrected understanding routing rows are
    // born only inside promoteToChampionAtomically - so this is the state every request hits
    // until a first champion promotes via the offline gate ([182]/#334).
    seedPriorSnapshot();
    mvc.perform(
            post("/v1/predict/pitch-type")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()))
        .andExpect(status().isServiceUnavailable());
    assertThat(logger.events)
        .as("a refused request must write NOTHING to prediction_log")
        .isEmpty();
  }

  @Test
  void a_missing_prior_snapshot_refuses_503_before_routing() throws Exception {
    // No snapshot row - but a REGISTERED, PROMOTED champion. This is what makes the pin real: with
    // no model registered, a route-first implementation would also 503 (from the fallback) with
    // nothing logged, and the test would prove nothing about ordering. With a servable champion
    // present, the ONLY explanation for 503-plus-empty-log is that the deriver refused BEFORE
    // routing ever ran - a prior computed over the wrong history is worse than no answer.
    registerVersion(BASELINE, "v1");
    long championId = registerVersion(MODEL_NAME, "v1");
    registryService.transitionStage(championId, Stage.CHAMPION);

    mvc.perform(
            post("/v1/predict/pitch-type")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()))
        .andExpect(status().isServiceUnavailable());
    assertThat(logger.events)
        .as("a servable champion existed, so an empty log proves derive-first ordering")
        .isEmpty();
  }

  @Test
  void champion_serves_through_the_router_and_logs_the_wire_keys() throws Exception {
    seedPriorSnapshot();
    registerVersion(BASELINE, "v1"); // rule 9: the primary cannot reach CHAMPION without it
    long championId = registerVersion(MODEL_NAME, "v1");
    registryService.transitionStage(championId, Stage.CHAMPION);

    mvc.perform(
            post("/v1/predict/pitch-type")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.modelName").value(MODEL_NAME))
        .andExpect(jsonPath("$.servingVersion").value("v1"))
        .andExpect(jsonPath("$.probabilities.FF").isNumber())
        .andExpect(jsonPath("$.priorPitches").value(100))
        // Decision [183] on the actual wire, not just the record shape: no argmax-shaped field.
        .andExpect(jsonPath("$.winner").doesNotExist())
        .andExpect(jsonPath("$.predictedType").doesNotExist());

    PredictionLogEvent champ =
        logger.events.stream()
            .filter(e -> e.role() == PredictionLogEvent.Role.CHAMPION)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CHAMPION prediction-log event captured"));
    assertThat(champ.modelVersionId())
        .as("the routed CHAMPION row carries the registered version FK")
        .isEqualTo(championId);
    assertThat(champ.features())
        .as(
            "the logged features are the WIRE keys the drift config watches - PSI joins by exact"
                + " key, and pThrows is the spelling the pitch-outcome heads do NOT use")
        .contains("\"pThrows\"")
        .contains("\"stand\"")
        .doesNotContain("pitcherThrows")
        .doesNotContain("batterStand")
        .doesNotContain("pitcherId");
    assertThat(champ.prediction())
        .as("no argmax in the audit trail either - [183] applies to the log row")
        .doesNotContain("winner");
  }

  @Test
  void shadow_challenger_dual_logs_role_shadow_with_the_challengers_fk() throws Exception {
    // THE A10 PIN. The #368 change exists so this row can exist: findChampion could never emit it.
    // Scope the claim precisely: these HTTP-path rows carry NULL live keys and enter NO promotion
    // gate (both evidence readers filter game_id IS NOT NULL) - what this pins is the MECHANISM
    // and the correct role, which become evidence-bearing when a live-ingest poller leg logs
    // through this same path with real live keys.
    seedPriorSnapshot();
    registerVersion(BASELINE, "v1");
    long championId = registerVersion(MODEL_NAME, "v1");
    // The promotion here rides the documented BOOTSTRAP EXEMPTION: pitch_type_pre has exactly one
    // ever-registered version, so assertPromotionCriteriaMet self-exempts. That is the real
    // first-champion path, not a test shortcut - but do not read this line as "promotion needs no
    // evidence": with 2+ versions it requires a passing experiment_results row (rule 5).
    registryService.transitionStage(championId, Stage.CHAMPION);
    long challengerId = registerVersion(MODEL_NAME, "v2");
    registryService.transitionStage(challengerId, Stage.SHADOW);
    routingService.setChallenger(MODEL_NAME, challengerId);

    mvc.perform(
            post("/v1/predict/pitch-type")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.servingVersion").value("v1")); // the champion serves the user

    PredictionLogEvent shadow = awaitShadowEvent();
    assertThat(shadow.modelName()).isEqualTo(MODEL_NAME); // rule 9: same model name, own row
    assertThat(shadow.modelVersion()).isEqualTo("v2");
    assertThat(shadow.modelVersionId())
        .as("the SHADOW row carries the challenger's FK, which the eval fetchers key on")
        .isEqualTo(challengerId);
    assertThat(shadow.features())
        // HONEST SCOPE: this pins that both legs logged the same request serialization, nothing
        // more - serializeFeatures is a pure function of the request DTO called in each leg, so
        // these bytes are identical whether the derivations ran once or twice. The derive-once
        // guarantee is structural (both derivations complete before router.route; the closure
        // captures the finished vector) and is not observable from the log rows, whose features
        // deliberately exclude every derived value.
        .as("both legs logged the same wire-key serialization of the request")
        .isEqualTo(
            logger.events.stream()
                .filter(e -> e.role() == PredictionLogEvent.Role.CHAMPION)
                .findFirst()
                .orElseThrow()
                .features());
  }

  /** Fire-and-forget shadow row lands after the HTTP response; poll rather than read. */
  private PredictionLogEvent awaitShadowEvent() throws InterruptedException {
    long deadlineNanos = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadlineNanos) {
      var found =
          logger.events.stream()
              .filter(e -> e.role() == PredictionLogEvent.Role.SHADOW)
              .findFirst();
      if (found.isPresent()) {
        return found.get();
      }
      Thread.sleep(20);
    }
    // First execution of this IT is CI, so the failure must be diagnosable from the message alone.
    throw new AssertionError(
        "no SHADOW prediction-log event captured within 5s; captured "
            + logger.events.size()
            + " event(s) with roles "
            + logger.events.stream().map(e -> e.role().name()).toList());
  }

  // --- fixtures --------------------------------------------------------------

  /**
   * One snapshot row for the pitcher: as_of_date = yesterday (inside the deriver's 2-day bound),
   * counts whose composition is unambiguous (prior_n 100, FF 40). The date binds as a STRING
   * through toDate(?) and LEADS the reordered column list - clickhouse-jdbc mis-binds java.sql.Date
   * into Date columns (the 1975-06-16 lesson) and mishandles a literal interleaved among
   * placeholders.
   */
  private void seedPriorSnapshot() {
    ch.update(
        "INSERT INTO pitcher_pitchtype_prior_current"
            + " (as_of_date, pitcher_id, prior_n, n_ff, n_si, n_fc, n_sl, n_cu, n_ch, n_off,"
            + "  prior_n_by_count, n_ff_by_count)"
            + " VALUES (toDate(?), 4242, 100, 40, 20, 10, 15, 5, 5, 5,"
            + "  [8,8,8,8,8,8,8,8,8,8,8,12], [3,3,3,3,3,3,3,3,3,3,3,7])",
        LocalDate.now(ET).minusDays(1).toString());
  }

  /** gameDate = today (ET): strictly after as_of_date, inside the staleness bound. */
  private String requestBody() {
    return """
        {"pitcherId": 4242, "gameId": 700002, "gameDate": "%s", "atBatIndex": 1,
         "pitchNumber": 3, "balls": 1, "strikes": 1, "outs": 0, "inning": 4, "baseState": 2,
         "stand": "R", "pThrows": "L", "parkId": "NYY", "timesThroughOrder": 2.0,
         "atBatNumberInGame": 14.0, "timesFacedToday": 1.0}
        """
        .formatted(LocalDate.now(ET));
  }

  /**
   * Register a pitch-type family version from the committed two-output ORT fixture plus the
   * canonical contract, mirroring ModelLoadValidatorPitchTypeTest's bundle. metadata carries
   * model_kind, which #360's server-side [184] check requires for this armed family, and the
   * calibrator pointer the loader resolves first.
   */
  private long registerVersion(String modelName, String version) throws Exception {
    Path src = Files.createDirectories(sourceDir.resolve(modelName + "-" + version));
    URL onnx = getClass().getResource("/onnx/pitch_type_fixture.onnx");
    Files.copy(
        Path.of(Objects.requireNonNull(onnx, "pitch-type fixture missing").toURI()),
        src.resolve("model.onnx"));
    Files.writeString(
        src.resolve("metadata.json"),
        "{\"model_name\":\""
            + modelName
            + "\",\"model_kind\":\"pitch_type\",\"model_version\":\""
            + version
            + "\",\"calibrator\":{\"path\":\"calibrator.json\"}}");
    Files.copy(CONTRACT, src.resolve("feature_pipeline.json"));
    Files.writeString(
        src.resolve("calibrator.json"),
        "{\"kind\":\"temperature\",\"class_labels\":"
            + "[\"FF\",\"SI\",\"FC\",\"SL\",\"CU\",\"CH\",\"OFF\"],\"temperature\":1.0}");
    Files.writeString(
        src.resolve("park_id_mapping.json"), "{\"park_id\":{\"NYY\":0},\"missing_value\":-1}");

    return registryService
        .register(
            new RegisterRequest(
                modelName,
                version,
                src.resolve("model.onnx").toString(),
                src.resolve("metadata.json").toString(),
                src.resolve("feature_pipeline.json").toString(),
                "train-h-pitchtype-" + version,
                "[2015-01-01,2023-12-31]",
                "{\"ece\":0.0036}",
                Instant.now(),
                "pitchtype-routing-it",
                "registered by PredictPitchTypeRoutingIT"))
        .id();
  }
}
