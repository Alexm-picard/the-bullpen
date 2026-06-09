package net.thebullpen.baseball.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import net.thebullpen.baseball.inference.FeaturePipelinePitchPre;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import org.junit.jupiter.api.Test;

/**
 * Pins the train/serve conventions of the live pre-pitch request (decision [143]) and the keyed
 * {@code prediction_log} event - without touching the ONNX boundary. These conventions are exactly
 * where silent skew would hide: the constant-0 score_diff placeholder, ISO day-of-week, the
 * base-state bitmask, and switch-hitter resolution.
 */
class LivePitchPredictorTest {

  /** 2026-06-04 is a Thursday -> ISO day-of-week 4. */
  private static LiveNextPitch ctx(
      String batSide, String pitchHand, boolean onFirst, boolean onSecond, boolean onThird) {
    return new LiveNextPitch(
        822810L,
        77,
        3,
        9,
        false,
        689296L,
        676391L,
        pitchHand,
        batSide,
        2,
        1,
        1,
        onFirst,
        onSecond,
        onThird,
        "TOR",
        LocalDate.of(2026, 6, 4));
  }

  @Test
  void toRequest_pins_the_training_conventions() {
    FeaturePipelinePitchPre.Request r =
        LivePitchPredictor.toRequest(ctx("R", "R", true, false, true));

    assertEquals(2, r.countBalls());
    assertEquals(1, r.countStrikes());
    assertEquals(1, r.outs());
    assertEquals(9, r.inning());
    assertEquals(1 + 4, r.baseState(), "1B + 3B occupied -> bitmask 5");
    assertEquals(0, r.scoreDiff(), "training placeholder is a constant 0 - no real score, no skew");
    assertEquals(
        4, r.dow(), "2026-06-04 is Thursday -> ISO dow 4 (matches ClickHouse toDayOfWeek)");
    assertEquals("R", r.pitcherThrows());
    assertEquals("R", r.batterStand());
    assertEquals("TOR", r.parkId());
    assertEquals(689296L, r.pitcherId());
    assertEquals(676391L, r.batterId());
    assertNull(r.pitcherPitchesLast28d(), "Tier 3 left null for v1 (decision [143])");
    assertNull(r.batterInplayRateStd());
  }

  @Test
  void resolveBatSide_resolves_switch_hitters_opposite_the_pitcher() {
    assertEquals("L", LivePitchPredictor.resolveBatSide("S", "R"), "switch vs RHP bats left");
    assertEquals("R", LivePitchPredictor.resolveBatSide("S", "L"), "switch vs LHP bats right");
    assertEquals("L", LivePitchPredictor.resolveBatSide("L", "R"), "non-switch passes through");
    assertEquals("R", LivePitchPredictor.resolveBatSide("R", "L"));
  }

  @Test
  void buildEvent_carries_the_live_join_key_role_and_real_version_fk() {
    Map<String, Double> probs = new LinkedHashMap<>();
    probs.put("ball", 0.5);
    probs.put("called_strike", 0.3);
    probs.put("in_play", 0.2);

    PredictionLogEvent ev =
        LivePitchPredictor.buildEvent(
            ctx("R", "R", true, false, false),
            probs,
            Instant.now(),
            "v1",
            42L, // routed model_version_id FK (W1b: must be real, not null)
            "hash123",
            PredictionLogEvent.Role.CHAMPION,
            1.5f);

    assertEquals(822810L, ev.gameId(), "keyed to the predicted pitch's game");
    assertEquals(77, ev.atBatIndex());
    assertEquals(3, ev.pitchNumber());
    assertEquals(PredictionLogEvent.Role.CHAMPION, ev.role());
    assertEquals("pitch_outcome_pre", ev.modelName());
    assertEquals("v1", ev.modelVersion());
    assertEquals(
        42L, ev.modelVersionId(), "W1b: live row carries the routed registry FK, not null");
    assertEquals("hash123", ev.featureHash());
    assertEquals(1.5f, ev.latencyMs());
    assertEquals("ball", LivePitchPredictor.argmax(probs), "winner is the argmax class");
  }
}
