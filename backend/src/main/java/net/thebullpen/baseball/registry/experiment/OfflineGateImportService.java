package net.thebullpen.baseball.registry.experiment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.thebullpen.baseball.registry.ExperimentResultsRepository;
import net.thebullpen.baseball.registry.OfflineGateEvidenceRepository;
import net.thebullpen.baseball.registry.RegistryBaselines;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ExperimentResult;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.OfflineGateEvidence;
import net.thebullpen.baseball.registry.dto.Stage;
import net.thebullpen.baseball.registry.experiment.dto.PrimaryMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a committed OFFLINE promotion-gate artifact (decision [166] / ADR-0012) into a terminal
 * {@code passed} {@code experiment_results} row - the row {@link
 * net.thebullpen.baseball.registry.RegistryService}{@code .assertPromotionCriteriaMet} reads.
 *
 * <p>This is the OFFLINE-evidence analogue of {@link ExperimentService}, which only supports ONLINE
 * shadow comparisons (start -&gt; evaluate-from-{@code prediction_log} -&gt; complete, and {@code
 * StartExperimentRequest} rejects a negative threshold). An offline rolling-origin ablation - e.g.
 * the carry champion's non-inferiority gate: a NEGATIVE threshold, scored offline because the
 * challenger is not serving and has zero shadow predictions - cannot go through that lifecycle.
 * This path ingests the committed, reviewed, BUNDLED evidence instead.
 *
 * <p>Anti-bypass (so this does not become a way to wave anything through): it can only import a
 * BUNDLED artifact (an operator cannot post arbitrary JSON); it RE-DERIVES the pass from the
 * verdict + guardrails + carry gate (never trusts the JSON's {@code status} blindly); and it binds
 * the row to the CURRENT champion - or, for a model with no champion yet, to its rule-9
 * co-registered LR baseline (the FIRST-CHAMPION binding, decision [181]/[145]), validated
 * non-archived and name-matched to the artifact's {@code champion_model_name}. ADMIN-only at the
 * controller. NO promotion is performed here (rule 6) - it only creates the evidence row the
 * human-gated promote reads.
 */
@Service
@Profile("api")
public class OfflineGateImportService {

  private static final Logger log = LoggerFactory.getLogger(OfflineGateImportService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ExperimentResultsRepository experiments;
  private final OfflineGateEvidenceRepository evidence;
  private final RegistryRepository registry;

  public OfflineGateImportService(
      ExperimentResultsRepository experiments,
      OfflineGateEvidenceRepository evidence,
      RegistryRepository registry) {
    this.experiments = experiments;
    this.evidence = evidence;
    this.registry = registry;
  }

  /**
   * Ingest {@code artifactName} as a terminal {@code passed} experiment_results row for {@code
   * (modelName, champion=championVersionId, challenger=challengerVersionId)}. Throws {@link
   * ExperimentException.OfflineGateInvalid} (-&gt; 422) on any validation failure; nothing is
   * written in that case.
   */
  @Transactional
  public ExperimentResult importGate(
      String modelName,
      long championVersionId,
      long challengerVersionId,
      String artifactName,
      String reason) {
    OfflineGateEvidence ev =
        evidence
            .byArtifact(artifactName)
            .orElseThrow(
                () ->
                    new ExperimentException.OfflineGateInvalid(
                        "no committed offline-gate artifact named '"
                            + artifactName
                            + "' on the classpath (only bundled, reviewed evidence can be imported)"));

    // 1. the evidence must be FOR this model.
    if (!modelName.equals(ev.modelName())) {
      throw new ExperimentException.OfflineGateInvalid(
          "artifact model_name '" + ev.modelName() + "' != request modelName '" + modelName + "'");
    }
    // 2. RE-DERIVE the pass from the raw NUMERICS (do not trust the declared status/verdict
    // booleans
    //    alone, so a JSON whose declared verdict disagrees with its own metrics cannot import):
    //    primary met iff challenger + threshold <= champion (a NEGATIVE threshold is a
    // non-inferiority
    //    margin, the carry case); each observed guardrail delta must not exceed its declared max;
    // the
    //    optional carry hard gate must pass; AND the artifact's own declared verdict must AGREE.
    if (ev.primaryMetric() == null) {
      throw new ExperimentException.OfflineGateInvalid(
          "artifact " + artifactName + " has no primary_metric");
    }
    try {
      PrimaryMetric.fromDbValue(ev.primaryMetric());
    } catch (IllegalArgumentException e) {
      throw new ExperimentException.OfflineGateInvalid(
          "artifact " + artifactName + " has unknown primary_metric '" + ev.primaryMetric() + "'");
    }
    if (ev.championMetric() == null
        || ev.challengerMetric() == null
        || ev.primaryThreshold() == null) {
      throw new ExperimentException.OfflineGateInvalid(
          "artifact " + artifactName + " is missing champion/challenger metric or threshold");
    }
    boolean primaryMet = ev.challengerMetric() + ev.primaryThreshold() <= ev.championMetric();
    Map<String, Double> maxDeltas = ev.guardrails() == null ? Map.of() : ev.guardrails();
    Map<String, Double> observed =
        ev.guardrailsObserved() == null ? Map.of() : ev.guardrailsObserved();
    List<String> breached = new ArrayList<>();
    for (Map.Entry<String, Double> g : maxDeltas.entrySet()) {
      Double obs = observed.get(g.getKey());
      if (obs != null && obs > g.getValue()) {
        breached.add(g.getKey());
      }
    }
    boolean carryOk = ev.carryGate() == null || Boolean.TRUE.equals(ev.carryGate().passed());
    boolean recomputedPass = primaryMet && breached.isEmpty() && carryOk;
    boolean declaredPass =
        "passed".equals(ev.status())
            && ev.verdict() != null
            && Boolean.TRUE.equals(ev.verdict().passed())
            && Boolean.TRUE.equals(ev.verdict().sampleSizeMet());
    if (!(recomputedPass && declaredPass)) {
      throw new ExperimentException.OfflineGateInvalid(
          "artifact "
              + artifactName
              + " is not a self-consistent PASS (recomputed primary_met="
              + primaryMet
              + ", guardrail_breaches="
              + breached
              + ", carry_gate.passed="
              + (ev.carryGate() == null ? "n/a" : ev.carryGate().passed())
              + ", declared status="
              + ev.status()
              + ", verdict.passed="
              + (ev.verdict() == null ? null : ev.verdict().passed())
              + ", sample_size_met="
              + (ev.verdict() == null ? null : ev.verdict().sampleSizeMet())
              + ")");
    }
    // 3. sample-size consistency.
    if (ev.sampleSizeObserved() == null
        || ev.sampleSizeTarget() == null
        || ev.sampleSizeObserved() < ev.sampleSizeTarget()) {
      throw new ExperimentException.OfflineGateInvalid(
          "artifact "
              + artifactName
              + " sample_size_observed "
              + ev.sampleSizeObserved()
              + " < target "
              + ev.sampleSizeTarget());
    }
    // 4. Bind the champion. The row must differ from the challenger, and the binding must be one
    // the strict promote gate will accept.
    if (championVersionId == challengerVersionId) {
      throw new ExperimentException.OfflineGateInvalid(
          "champion and challenger version ids must differ; got " + championVersionId);
    }
    Optional<ModelVersion> currentChampion = registry.findChampion(modelName);
    if (currentChampion.isPresent()) {
      // Normal case: bind the CURRENT champion (a stale-champion row would not satisfy the gate).
      if (currentChampion.get().id() != championVersionId) {
        throw new ExperimentException.OfflineGateInvalid(
            "championVersionId "
                + championVersionId
                + " is not the CURRENT champion of "
                + modelName
                + " (current="
                + currentChampion.get().id()
                + "); a row against a stale champion would not satisfy the promote gate");
      }
    } else {
      // FIRST-CHAMPION binding (decision [181]/[145]): a model with NO champion (e.g.
      // pitch_outcome_pre, whose declared primary is a NEGATIVE-threshold ECE bar the online
      // experiment path cannot create) binds its offline-gate row to the rule-9 co-registered LR
      // BASELINE version - the natural comparison, and the promote gate's
      // findLatestPassingAnyChampion accepts any champion_version_id. The baseline version must be
      // NON-ARCHIVED and its model name must match both the rule-9 mapping AND the artifact's
      // declared champion_model_name (so the imported row honestly names what it was scored
      // against).
      String expectedBaseline =
          RegistryBaselines.baselineFor(modelName)
              .orElseThrow(
                  () ->
                      new ExperimentException.OfflineGateInvalid(
                          modelName
                              + " has no current champion and no rule-9 baseline to bind a"
                              + " first-champion gate row to"));
      ModelVersion baselineVersion =
          registry
              .findById(championVersionId)
              .orElseThrow(
                  () ->
                      new ExperimentException.OfflineGateInvalid(
                          "championVersionId "
                              + championVersionId
                              + " is not a registered version"));
      if (!expectedBaseline.equals(baselineVersion.modelName())) {
        throw new ExperimentException.OfflineGateInvalid(
            "first-champion binding: championVersionId "
                + championVersionId
                + " belongs to '"
                + baselineVersion.modelName()
                + "', not the rule-9 baseline '"
                + expectedBaseline
                + "' of '"
                + modelName
                + "'");
      }
      if (baselineVersion.stage() == Stage.ARCHIVED) {
        throw new ExperimentException.OfflineGateInvalid(
            "first-champion binding requires a non-archived "
                + expectedBaseline
                + " version; "
                + championVersionId
                + " is ARCHIVED");
      }
      if (!expectedBaseline.equals(ev.championModelName())) {
        throw new ExperimentException.OfflineGateInvalid(
            "first-champion binding: artifact champion_model_name '"
                + ev.championModelName()
                + "' != the rule-9 baseline '"
                + expectedBaseline
                + "' of '"
                + modelName
                + "'");
      }
    }
    ModelVersion challenger =
        registry
            .findById(challengerVersionId)
            .orElseThrow(
                () ->
                    new ExperimentException.OfflineGateInvalid(
                        "challengerVersionId "
                            + challengerVersionId
                            + " is not a registered version"));
    if (!modelName.equals(challenger.modelName())) {
      throw new ExperimentException.OfflineGateInvalid(
          "challengerVersionId "
              + challengerVersionId
              + " belongs to model '"
              + challenger.modelName()
              + "', not '"
              + modelName
              + "'");
    }

    String notes =
        "offline-gate import: artifact="
            + artifactName
            + " git_commit="
            + (ev.provenance() == null ? "?" : ev.provenance().gitCommit())
            + " reason: "
            + reason
            + " (ADR-0012/[166])";
    ExperimentResult running =
        experiments.insertRunning(
            modelName,
            championVersionId,
            challengerVersionId,
            ev.primaryMetric(),
            ev.primaryThreshold() == null ? 0.0 : ev.primaryThreshold(),
            writeJson(ev.guardrails()),
            ev.sampleSizeTarget(),
            notes);
    experiments.markTerminal(
        running.id(),
        ExperimentResult.Status.PASSED.dbValue(),
        ev.sampleSizeObserved(),
        ev.championMetric(),
        ev.challengerMetric(),
        writeJson(ev.guardrailsObserved()));
    log.info(
        "offline-gate import: experiment id={} model={} champ={} chall={} status=PASSED artifact={}"
            + " (primary {} threshold {})",
        running.id(),
        modelName,
        championVersionId,
        challengerVersionId,
        artifactName,
        ev.primaryMetric(),
        ev.primaryThreshold());
    return experiments.findById(running.id()).orElseThrow();
  }

  private static String writeJson(Map<String, Double> map) {
    try {
      return MAPPER.writeValueAsString(map == null ? Map.of() : map);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not serialize guardrails map", e);
    }
  }
}
