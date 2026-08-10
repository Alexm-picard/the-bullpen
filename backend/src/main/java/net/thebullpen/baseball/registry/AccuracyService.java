package net.thebullpen.baseball.registry;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.thebullpen.baseball.api.dto.ModelAccuracyScorecard;
import net.thebullpen.baseball.registry.dto.PromotionEvidence;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Builds the public model-accuracy scorecard from the committed promotion-evidence, plus a
 * pass-through to the box-produced batted-ball backfill artifact. Backs {@code GET
 * /v1/ops/accuracy} and {@code GET /v1/ops/backfill-accuracy} on {@link
 * net.thebullpen.baseball.api.ops.OpsController}.
 *
 * <p>HONESTY: stamps every row with the constant offline-eval label, passes the gate status/verdict
 * + calibration note through verbatim, and surfaces the REALITY {@code ece} as the headline (with
 * {@code eceVsRetro} clearly secondary). It invents no number the evidence does not contain. {@code
 * api} profile only.
 */
@Service
@Profile("api")
public class AccuracyService {

  /**
   * The single honesty label stamped on every row: these are offline rolling-origin-CV / full-box
   * gate numbers from training time, NOT live production accuracy.
   */
  static final String EVALUATION =
      "offline rolling-origin CV (4 folds, 2015-2025 held-out); not live production accuracy";

  /**
   * Evidence (training/challenger) name -> registry/serving name. Pitch heads map to themselves
   * (absent here); the batted-ball challenger {@code batted_ball_mlp} serves as {@code
   * battedball_outcome}. Mirrors RegistryService's baseline-name reconciliation.
   */
  private static final Map<String, String> EVIDENCE_NAME_TO_REGISTRY =
      Map.of("batted_ball_mlp", "battedball_outcome");

  /**
   * Static serving-stage hint by registry name, reflecting decisions [165]/[154]/[163]: the post
   * head is champion-STAGE but UI-held; the pre head failed primary and stays shadow; the
   * batted-ball champion serves {@code /parks}. The live registry stage is shown on the Ops fleet
   * table - this label is a convenience on offline-eval rows, not a second source of truth for
   * routing.
   */
  private static final Map<String, String> STAGE_HINT =
      Map.of(
          "pitch_outcome_post", "CHAMPION",
          "pitch_outcome_pre", "SHADOW",
          "battedball_outcome", "CHAMPION");

  private final AccuracyEvidenceRepository repo;

  public AccuracyService(AccuracyEvidenceRepository repo) {
    this.repo = repo;
  }

  /** One scorecard row per registered/evidenced model. Empty when no evidence is bundled. */
  public List<ModelAccuracyScorecard> scorecards() {
    return repo.evidence().stream().map(AccuracyService::toScorecard).toList();
  }

  /** The box-produced batted-ball backfill artifact verbatim, or empty until the box commits it. */
  public Optional<JsonNode> backfill() {
    return repo.backfill();
  }

  private static ModelAccuracyScorecard toScorecard(PromotionEvidence e) {
    String evidenceName = e.modelName();
    String registryName = EVIDENCE_NAME_TO_REGISTRY.getOrDefault(evidenceName, evidenceName);
    String stage = STAGE_HINT.getOrDefault(registryName, "NONE");

    PromotionEvidence.Metrics chall = e.challengerFullMetrics();
    PromotionEvidence.Verdict verdict = e.verdict();
    PromotionEvidence.RollingOriginCv.Summary cv =
        e.rollingOriginCv() == null ? null : e.rollingOriginCv().challengerSummary();

    return new ModelAccuracyScorecard(
        registryName,
        evidenceName,
        stage,
        e.championModelName(),
        e.primaryMetric(),
        EVALUATION,
        e.status(),
        verdict == null ? null : verdict.outcome(),
        e.sampleSizeObserved() == null ? 0L : e.sampleSizeObserved(),
        chall == null ? null : chall.brier(),
        chall == null ? null : chall.ece(),
        chall == null ? null : chall.logLoss(),
        chall == null ? null : chall.eceVsRetro(),
        verdict == null ? null : verdict.primaryMarginObserved(),
        statMean(cv == null ? null : cv.multiclassBrier()),
        statStd(cv == null ? null : cv.multiclassBrier()),
        statMean(cv == null ? null : cv.expectedCalibrationError()),
        statStd(cv == null ? null : cv.expectedCalibrationError()),
        e.calibrationNote(),
        e.provenance() == null ? null : e.provenance().generatedAt(),
        e.provenance() == null ? null : e.provenance().gitCommit());
  }

  private static Double statMean(PromotionEvidence.RollingOriginCv.Stat s) {
    return s == null ? null : s.mean();
  }

  private static Double statStd(PromotionEvidence.RollingOriginCv.Stat s) {
    return s == null ? null : s.std();
  }
}
