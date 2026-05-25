package net.thebullpen.baseball.inference.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link Bucketer} — no Spring context needed. Each property the leaf calls out
 * (determinism, uniformity at α=0.01, ~50% routing at traffic_pct=50, model_name salt independence)
 * gets its own test method.
 */
class BucketerTest {

  private final Bucketer bucketer = new Bucketer();

  // --- determinism ------------------------------------------------------

  @Test
  void same_input_pair_always_returns_same_bucket() {
    long gameId = 718394L;
    String modelName = "pitch_outcome_pre";
    int expected = bucketer.bucket(gameId, modelName);
    for (int i = 0; i < 1000; i++) {
      assertThat(bucketer.bucket(gameId, modelName)).isEqualTo(expected);
    }
  }

  @Test
  void bucket_value_is_always_in_range() {
    Random r = new Random(42);
    for (int i = 0; i < 10_000; i++) {
      int b = bucketer.bucket(r.nextLong(), "pitch_outcome_pre");
      assertThat(b).isGreaterThanOrEqualTo(0).isLessThan(Bucketer.BUCKET_COUNT);
    }
  }

  // --- model_name salt --------------------------------------------------

  @Test
  void model_name_salts_the_bucket_so_two_models_do_not_align() {
    // For the salt to be doing its job, the bucket assignments for the same gameId across two
    // different modelNames must NOT be identical (every game routed to challenger for one
    // model isn't auto-routed to challenger for the other).
    int identical = 0;
    int total = 5000;
    Random r = new Random(7);
    for (int i = 0; i < total; i++) {
      long gid = r.nextLong();
      if (bucketer.bucket(gid, "pitch_outcome_pre") == bucketer.bucket(gid, "batted_ball")) {
        identical++;
      }
    }
    // At uniform-random alignment the expected match rate is 1/1000 = 0.1% — allow up to 1%
    // (10× slack) before declaring the salt broken.
    assertThat((double) identical / total)
        .as("two models' bucket assignments should not align beyond chance")
        .isLessThan(0.01);
  }

  // --- uniformity (chi-squared GOF) -------------------------------------

  @Test
  void uniformity_chi_squared_passes_at_alpha_001() {
    // 100K random game IDs, binned into 100 bins (collapse 10 buckets per bin); chi-squared
    // GOF for uniform distribution. df = 99; critical value at α=0.01 is ~134.642.
    int samples = 100_000;
    int bins = 100;
    long[] counts = new long[bins];
    Random r = new Random(20260524L);
    for (int i = 0; i < samples; i++) {
      int b = bucketer.bucket(r.nextLong(), "uniformity_test_model");
      counts[b * bins / Bucketer.BUCKET_COUNT]++;
    }
    double expected = (double) samples / bins;
    double chiSquared = 0.0;
    for (long c : counts) {
      double delta = c - expected;
      chiSquared += (delta * delta) / expected;
    }
    // χ² critical value, df=99, α=0.01: 134.642 (from chi-squared distribution table).
    assertThat(chiSquared)
        .as(
            "Murmur3 bucketing should be uniform — chi-squared %.2f vs critical 134.642 at α=0.01",
            chiSquared)
        .isLessThan(134.642);
  }

  // --- route + traffic_pct ----------------------------------------------

  @Test
  void route_in_ab_mode_with_50_pct_traffic_assigns_about_half_to_challenger() {
    RoutingConfig cfg =
        new RoutingConfig(1L, "model_a", 1L, 2L, 50.0, RoutingMode.AB, Instant.now());
    int challenger = 0;
    int total = 100_000;
    Random r = new Random(20260524L);
    for (int i = 0; i < total; i++) {
      if (bucketer.route(r.nextLong(), "model_a", cfg) == Role.CHALLENGER) {
        challenger++;
      }
    }
    double pct = (double) challenger / total;
    assertThat(pct)
        .as("expected ~0.50 challenger at traffic_pct=50; got %.4f", pct)
        .isBetween(0.49, 0.51);
  }

  @Test
  void route_in_shadow_mode_always_returns_champion_regardless_of_pct() {
    // Even if traffic_pct is non-zero (the service rejects this combination but the router
    // shouldn't fall apart if it sees the row), SHADOW mode means user-facing = champion.
    RoutingConfig cfg =
        new RoutingConfig(1L, "model_a", 1L, 2L, 50.0, RoutingMode.SHADOW, Instant.now());
    Random r = new Random(7);
    for (int i = 0; i < 1000; i++) {
      assertThat(bucketer.route(r.nextLong(), "model_a", cfg)).isEqualTo(Role.CHAMPION);
    }
  }

  @Test
  void route_with_no_challenger_returns_champion_even_in_ab_mode() {
    RoutingConfig cfg =
        new RoutingConfig(1L, "model_a", 1L, null, 0.0, RoutingMode.AB, Instant.now());
    assertThat(bucketer.route(12345L, "model_a", cfg)).isEqualTo(Role.CHAMPION);
  }

  @Test
  void route_at_zero_pct_always_returns_champion() {
    RoutingConfig cfg =
        new RoutingConfig(1L, "model_a", 1L, 2L, 0.0, RoutingMode.AB, Instant.now());
    Random r = new Random(13);
    for (int i = 0; i < 10_000; i++) {
      assertThat(bucketer.route(r.nextLong(), "model_a", cfg)).isEqualTo(Role.CHAMPION);
    }
  }

  @Test
  void route_at_hundred_pct_always_returns_challenger() {
    RoutingConfig cfg =
        new RoutingConfig(1L, "model_a", 1L, 2L, 100.0, RoutingMode.AB, Instant.now());
    Random r = new Random(13);
    for (int i = 0; i < 10_000; i++) {
      assertThat(bucketer.route(r.nextLong(), "model_a", cfg)).isEqualTo(Role.CHALLENGER);
    }
  }

  // --- threshold rounding -----------------------------------------------

  @Test
  void traffic_threshold_handles_edges_and_fractional_pct() {
    assertThat(Bucketer.trafficThreshold(0.0)).isEqualTo(0);
    assertThat(Bucketer.trafficThreshold(100.0)).isEqualTo(1000);
    assertThat(Bucketer.trafficThreshold(50.0)).isEqualTo(500);
    assertThat(Bucketer.trafficThreshold(33.33)).isEqualTo(333);
    // 0.05 rounds up to 1 bucket — pct sub-resolution is documented (leaf "Known edge cases").
    assertThat(Bucketer.trafficThreshold(0.05)).isEqualTo(1);
    // Negative + > 100 are clamped (defense in depth alongside service validation).
    assertThat(Bucketer.trafficThreshold(-10.0)).isEqualTo(0);
    assertThat(Bucketer.trafficThreshold(150.0)).isEqualTo(1000);
  }

  // --- session-keyed path ----------------------------------------------

  @Test
  void session_bucket_is_deterministic() {
    UUID session = UUID.fromString("00000000-0000-0000-0000-000000000001");
    int expected = bucketer.bucketForSession(session, "model_a");
    for (int i = 0; i < 1000; i++) {
      assertThat(bucketer.bucketForSession(session, "model_a")).isEqualTo(expected);
    }
  }

  @Test
  void session_routes_uniformly_at_50_pct() {
    RoutingConfig cfg =
        new RoutingConfig(1L, "model_a", 1L, 2L, 50.0, RoutingMode.AB, Instant.now());
    int challenger = 0;
    int total = 50_000;
    Random r = new Random(20260524L);
    for (int i = 0; i < total; i++) {
      UUID session = new UUID(r.nextLong(), r.nextLong());
      if (bucketer.routeForSession(session, "model_a", cfg) == Role.CHALLENGER) {
        challenger++;
      }
    }
    double pct = (double) challenger / total;
    assertThat(pct).isBetween(0.49, 0.51);
  }

  // --- cross-check: bucket vs route consistency -------------------------

  @Test
  void route_decision_matches_direct_bucket_threshold_comparison() {
    // Spot-check 1000 game IDs: route() and (bucket() < threshold) agree.
    double pct = 37.0;
    int threshold = Bucketer.trafficThreshold(pct);
    RoutingConfig cfg =
        new RoutingConfig(1L, "model_a", 1L, 2L, pct, RoutingMode.AB, Instant.now());
    Random r = new Random(99);
    Map<Long, Role> decisions = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      long gid = r.nextLong();
      Role direct = bucketer.bucket(gid, "model_a") < threshold ? Role.CHALLENGER : Role.CHAMPION;
      decisions.put(gid, direct);
      assertThat(bucketer.route(gid, "model_a", cfg)).isEqualTo(direct);
    }
    assertThat(decisions).hasSize(1000);
  }
}
