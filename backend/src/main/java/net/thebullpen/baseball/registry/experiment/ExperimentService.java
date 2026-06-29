package net.thebullpen.baseball.registry.experiment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.thebullpen.baseball.registry.ExperimentResultsRepository;
import net.thebullpen.baseball.registry.dto.ExperimentResult;
import net.thebullpen.baseball.registry.dto.ExperimentResult.Status;
import net.thebullpen.baseball.registry.experiment.dto.ExperimentVerdict;
import net.thebullpen.baseball.registry.experiment.dto.ExperimentVerdict.Outcome;
import net.thebullpen.baseball.registry.experiment.dto.PrimaryMetric;
import net.thebullpen.baseball.registry.experiment.dto.StartExperimentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle service for {@code experiment_results} (leaf 3b.4) — start → evaluate → complete (or
 * abort). The promotion-gate read path from 3a.4 already reads this table; this service is the
 * WRITE path the admin uses to declare + score experiments.
 *
 * <p>Lifecycle states (mirrors the V012 CHECK):
 *
 * <pre>
 *   running ──────┬──→ passed   (complete: sample met + primary met + no guardrail violated)
 *                 ├──→ failed   (complete: sample met + primary missed OR guardrail violated)
 *                 └──→ aborted  (abort: explicit operator action; no verdict needed)
 * </pre>
 *
 * <p>{@code evaluate} is read-only — computes the WOULD-be verdict from currently-observed paired
 * predictions, doesn't mutate state. {@code complete} consults the verdict + the sample-size target
 * to decide passed / failed / refuses-to-complete.
 *
 * <p>One running experiment per model_name is enforced at {@code start} (leaf "Known edge cases").
 * The repository check is the gate; the admin must complete or abort the existing experiment first.
 */
@Service
public class ExperimentService {

  private static final Logger log = LoggerFactory.getLogger(ExperimentService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ExperimentResultsRepository repo;
  private final PairedPredictionFetcher fetcher;

  public ExperimentService(ExperimentResultsRepository repo, PairedPredictionFetcher fetcher) {
    this.repo = repo;
    this.fetcher = fetcher;
  }

  // --- start ------------------------------------------------------------

  /**
   * Insert a new {@code running} experiment row. Fails with {@link
   * ExperimentException.AlreadyRunning} if another experiment is in {@code running} for the same
   * model_name.
   */
  @Transactional
  public ExperimentResult start(StartExperimentRequest req) {
    Optional<ExperimentResult> existing = repo.findRunningByModel(req.modelName());
    if (existing.isPresent()) {
      throw new ExperimentException.AlreadyRunning(req.modelName(), existing.get().id());
    }
    String guardrailsJson = writeJson(req.guardrails());
    String notes = "started: " + req.reason();
    ExperimentResult inserted =
        repo.insertRunning(
            req.modelName(),
            req.championVersionId(),
            req.challengerVersionId(),
            req.primaryMetric().dbValue(),
            req.primaryThreshold(),
            guardrailsJson,
            req.sampleSizeTarget(),
            notes);
    log.info(
        "experiment: started id={} {} champ={} chall={} metric={} threshold={} target={}",
        inserted.id(),
        req.modelName(),
        req.championVersionId(),
        req.challengerVersionId(),
        req.primaryMetric(),
        req.primaryThreshold(),
        req.sampleSizeTarget());
    return inserted;
  }

  // --- evaluate (read-only) --------------------------------------------

  /**
   * Compute the would-be verdict for {@code experimentId} from currently-observed paired
   * predictions. Read-only: does NOT mutate the row. The admin uses this to peek at "would-pass"
   * state before deciding to {@code complete}.
   */
  public ExperimentVerdict evaluate(long experimentId) {
    ExperimentResult exp = loadOrThrow(experimentId);
    List<PairedPrediction> pairs = fetchPairs(exp);
    PrimaryMetric metric = PrimaryMetric.fromDbValue(exp.primaryMetric());

    if (pairs.isEmpty()) {
      return new ExperimentVerdict(
          Outcome.WOULD_FAIL_PRIMARY, 0L, Double.NaN, Double.NaN, Map.of(), Map.of());
    }

    double champMetric = MetricsComputer.compute(metric, pairs, false);
    double challMetric = MetricsComputer.compute(metric, pairs, true);

    Map<String, Double> guardrails = parseGuardrails(exp.guardrails());
    Map<String, Double> deltas = new HashMap<>();
    Map<String, Double> violated = new HashMap<>();
    for (Map.Entry<String, Double> g : guardrails.entrySet()) {
      PrimaryMetric guardrailMetric;
      try {
        guardrailMetric = PrimaryMetric.fromDbValue(g.getKey());
      } catch (IllegalArgumentException e) {
        log.warn("experiment: unknown guardrail metric {}; skipping", g.getKey());
        continue;
      }
      double champG = MetricsComputer.compute(guardrailMetric, pairs, false);
      double challG = MetricsComputer.compute(guardrailMetric, pairs, true);
      double delta = challG - champG;
      deltas.put(g.getKey(), delta);
      // Guardrail violation: challenger is WORSE than champion by more than the allowed delta.
      // All metrics here are lower-is-better, so positive delta = regression.
      if (delta > g.getValue()) {
        violated.put(g.getKey(), delta);
      }
    }

    Outcome outcome;
    if (!violated.isEmpty()) {
      outcome = Outcome.WOULD_FAIL_GUARDRAIL;
    } else if (challMetric + exp.primaryThreshold() <= champMetric) {
      outcome = Outcome.WOULD_PASS;
    } else {
      outcome = Outcome.WOULD_FAIL_PRIMARY;
    }
    return new ExperimentVerdict(
        outcome, (long) pairs.size(), champMetric, challMetric, deltas, violated);
  }

  // --- complete + abort -------------------------------------------------

  /**
   * Compute the verdict and flip the row to {@code passed} or {@code failed} based on the outcome +
   * the sample-size target. Refuses with {@link ExperimentException.InsufficientSampleSize} when
   * not enough data has accumulated.
   */
  @Transactional
  public ExperimentResult complete(long experimentId) {
    ExperimentResult exp = loadOrThrow(experimentId);
    if (exp.status() != Status.RUNNING) {
      throw new ExperimentException.InvalidStateTransition(
          experimentId, exp.status().name(), "complete");
    }
    ExperimentVerdict verdict = evaluate(experimentId);
    if (verdict.sampleSizeObserved() < exp.sampleSizeTarget()) {
      throw new ExperimentException.InsufficientSampleSize(
          experimentId, verdict.sampleSizeObserved(), exp.sampleSizeTarget());
    }
    String terminalStatus =
        verdict.outcome() == Outcome.WOULD_PASS ? Status.PASSED.dbValue() : Status.FAILED.dbValue();
    String observedJson = writeJson(verdict.guardrailDeltas());
    repo.markTerminal(
        experimentId,
        terminalStatus,
        verdict.sampleSizeObserved(),
        verdict.championMetric(),
        verdict.challengerMetric(),
        observedJson);
    log.info(
        "experiment: completed id={} status={} sample={} champ_metric={} chall_metric={} guardrails_violated={}",
        experimentId,
        terminalStatus,
        verdict.sampleSizeObserved(),
        verdict.championMetric(),
        verdict.challengerMetric(),
        verdict.guardrailsViolated().keySet());
    return repo.findById(experimentId).orElseThrow();
  }

  /**
   * Abort a running experiment without computing a verdict — for the "regretted starting it" case
   * from the leaf "Known edge cases". Status flips to {@code aborted}; metric columns stay null (no
   * verdict was computed).
   */
  @Transactional
  public ExperimentResult abort(long experimentId, String reason) {
    ExperimentResult exp = loadOrThrow(experimentId);
    if (exp.status() != Status.RUNNING) {
      throw new ExperimentException.InvalidStateTransition(
          experimentId, exp.status().name(), "abort");
    }
    repo.markTerminal(experimentId, Status.ABORTED.dbValue(), 0L, null, null, "{}");
    log.warn("experiment: aborted id={} (was running) reason: {}", experimentId, reason);
    return repo.findById(experimentId).orElseThrow();
  }

  // --- reads ------------------------------------------------------------

  public ExperimentResult getById(long id) {
    return loadOrThrow(id);
  }

  public List<ExperimentResult> findByModel(String modelName) {
    return repo.findByModel(modelName);
  }

  // --- helpers ----------------------------------------------------------

  private ExperimentResult loadOrThrow(long id) {
    return repo.findById(id).orElseThrow(() -> new ExperimentException.UnknownExperiment(id));
  }

  private List<PairedPrediction> fetchPairs(ExperimentResult exp) {
    // Resolve version strings from the registry - the fetcher works in (modelName, version)
    // because that's the prediction_log schema (V004 columns are strings, not numeric FKs).
    // version_ids are passed as strings; the @Primary ClickHouse fetcher
    // (ClickHousePairedPredictionFetcher) parses and queries them in prod. A stub fetcher stands
    // in only when ClickHouse is disabled (e.g. unit tests).
    Instant until = Instant.now().minusSeconds(24L * 3600L); // settled-truth window per leaf
    return fetcher.fetch(
        exp.modelName(),
        String.valueOf(exp.championVersionId()),
        String.valueOf(exp.challengerVersionId()),
        exp.startedAt(),
        until);
  }

  private static String writeJson(Map<String, ?> map) {
    try {
      return MAPPER.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not serialize map to JSON", e);
    }
  }

  private static Map<String, Double> parseGuardrails(String json) {
    if (json == null || json.isBlank() || json.equals("{}")) {
      return Map.of();
    }
    try {
      Map<String, Object> raw =
          MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
      Map<String, Double> out = new HashMap<>();
      for (Map.Entry<String, Object> e : raw.entrySet()) {
        if (e.getValue() instanceof Number n) {
          out.put(e.getKey(), n.doubleValue());
        }
      }
      return out;
    } catch (JsonProcessingException e) {
      log.warn("experiment: malformed guardrails JSON; treating as empty: {}", json);
      return Map.of();
    }
  }
}
