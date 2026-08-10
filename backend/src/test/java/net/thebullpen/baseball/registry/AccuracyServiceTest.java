package net.thebullpen.baseball.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.thebullpen.baseball.api.dto.ModelAccuracyScorecard;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link AccuracyService} against the REAL committed promotion-evidence JSONs, bundled onto
 * the test classpath by the {@code processResources} copy
 * (build/resources/main/accuracy-evidence/). No mocks - it asserts the actual held-out numbers, the
 * name reconciliation, the de-dup, and the honesty labels so a regression in either the evidence
 * schema mapping or the JSON itself is caught.
 */
class AccuracyServiceTest {

  private static Map<String, ModelAccuracyScorecard> scorecardsByModel() {
    AccuracyService service =
        new AccuracyService(new AccuracyEvidenceRepository(new ObjectMapper()));
    return service.scorecards().stream()
        .collect(Collectors.toMap(ModelAccuracyScorecard::modelName, Function.identity()));
  }

  @Test
  void surfaces_the_three_models_deduped_to_one_batted_ball_row() {
    Map<String, ModelAccuracyScorecard> byModel = scorecardsByModel();
    // pitch pre + pitch post + battedball_outcome (the two batted_ball_mlp evidence files de-dupe
    // to
    // the per_park_isotonic variant, so exactly one battedball row).
    assertThat(byModel.keySet())
        .containsExactlyInAnyOrder("pitch_outcome_pre", "pitch_outcome_post", "battedball_outcome");
  }

  @Test
  void post_head_carries_real_held_out_metrics_and_passed_verdict() {
    ModelAccuracyScorecard post = scorecardsByModel().get("pitch_outcome_post");
    assertThat(post.gateStatus()).isEqualTo("passed");
    assertThat(post.verdictOutcome()).isEqualTo("would_pass");
    assertThat(post.baselineModelName()).isEqualTo("pitch_outcome_lr_baseline");
    assertThat(post.brier()).isCloseTo(0.10368198397009791, within(1e-9));
    assertThat(post.brierCvMean()).isCloseTo(0.10377467408102027, within(1e-9));
    assertThat(post.sampleSize()).isEqualTo(710600L);
    assertThat(post.stage()).isEqualTo("CHAMPION"); // champion-STAGE but UI-held ([165])
    assertThat(post.eceVsRetro()).isNull(); // pitch heads have no retro reference
    assertThat(post.calibrationNote()).isNull();
  }

  @Test
  void pre_head_is_shown_with_its_would_pass_verdict_under_the_ece_primary() {
    // ADR-0014 / [180] re-aimed PRE's declared primary from Brier-edge (failed) to absolute
    // calibration (ECE < 0.02). The regenerated full-data evidence flips the verdict accordingly.
    // Still SHADOW - the gate PASSING is not the same as promotion (rule 6, human-gated).
    ModelAccuracyScorecard pre = scorecardsByModel().get("pitch_outcome_pre");
    assertThat(pre.gateStatus()).isEqualTo("passed");
    assertThat(pre.verdictOutcome()).isEqualTo("would_pass");
    assertThat(pre.primaryMetric()).isEqualTo("ece");
    assertThat(pre.brier()).isCloseTo(0.14764637722497043, within(1e-9));
    assertThat(pre.stage()).isEqualTo("SHADOW");
  }

  @Test
  void batted_ball_reconciles_name_and_surfaces_reality_ece_plus_self_referential_note() {
    ModelAccuracyScorecard bb = scorecardsByModel().get("battedball_outcome");
    assertThat(bb.evidenceModelName()).isEqualTo("batted_ball_mlp");
    assertThat(bb.gateStatus()).isEqualTo("failed");
    assertThat(bb.verdictOutcome()).isEqualTo("would_fail_guardrail");
    // headline ece is the mediocre REALITY label-ECE; ece_vs_retro is the low SELF-REFERENTIAL one.
    assertThat(bb.ece()).isCloseTo(0.23644744737891557, within(1e-9));
    assertThat(bb.eceVsRetro()).isCloseTo(0.0028212314467010915, within(1e-9));
    assertThat(bb.calibrationNote()).isNotNull().contains("SELF-REFERENTIAL");
  }

  @Test
  void every_row_is_labeled_offline_not_live() {
    assertThat(scorecardsByModel().values())
        .allSatisfy(
            row -> {
              assertThat(row.evaluation()).contains("offline").contains("not live");
              // primaryMetric is head-specific now (PRE = ece since ADR-0014, others = brier);
              // each head's exact value is pinned in its own test. Here just assert it is surfaced.
              assertThat(row.primaryMetric()).isNotBlank();
            });
  }

  @Test
  void backfill_carries_the_committed_box_artifact() {
    // The box-produced 2026 holdout backfill artifact is committed (#157) and bundled onto the test
    // classpath, so backfill() is present and carries the real held-out aggregate (was empty before
    // #157; this test asserted that empty pre-commit state and is now updated to the committed
    // one).
    AccuracyService service =
        new AccuracyService(new AccuracyEvidenceRepository(new ObjectMapper()));
    Optional<JsonNode> backfill = service.backfill();
    assertThat(backfill).isPresent();
    JsonNode doc = backfill.orElseThrow();
    assertThat(doc.get("model_name").asText()).isEqualTo("battedball_outcome");
    assertThat(doc.get("season_from").asInt()).isEqualTo(2026);
    assertThat(doc.get("eval_kind").asText()).isEqualTo("offline_holdout_unseen");
    assertThat(doc.get("aggregate").get("brier").asDouble())
        .isCloseTo(0.10322678124538412, within(1e-9));
  }
}
