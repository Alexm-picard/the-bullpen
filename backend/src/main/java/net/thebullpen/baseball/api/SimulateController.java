package net.thebullpen.baseball.api;

import ai.onnxruntime.OrtException;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import net.thebullpen.baseball.api.dto.SimulateRequest;
import net.thebullpen.baseball.api.dto.SimulateResponse;
import net.thebullpen.baseball.inference.FeaturePipelinePitchPre;
import net.thebullpen.baseball.inference.InferenceMetrics;
import net.thebullpen.baseball.inference.PitchInferenceService;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.simulation.AnalyticalSolver;
import net.thebullpen.baseball.simulation.MonteCarloSimulator;
import net.thebullpen.baseball.simulation.PitchOutcome;
import net.thebullpen.baseball.simulation.PlateAppearanceMarkov;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Forward-simulator endpoints — Phase 2a.9.
 *
 * <ul>
 *   <li>{@code POST /v1/simulate/plate-appearance} — analytical (production)
 *   <li>{@code POST /v1/simulate/plate-appearance/monte-carlo} — diagnostic
 * </ul>
 *
 * <p>Per the leaf plan: the simulator must call {@link PitchInferenceService} <em>per state</em>,
 * not once — pitchers throw differently in 0-0 vs 3-2. The 12 per-state distributions are computed
 * once per request and shared by the analytical solver / MC simulator.
 *
 * <p>{@link ConditionalOnBean} keeps this controller out of the context when {@link
 * PitchInferenceService} is missing — i.e. when the production ONNX artifact hasn't been trained
 * yet, so toy-only tests stay green on a fresh clone.
 */
@RestController
@RequestMapping("/v1/simulate")
@Profile("api")
@ConditionalOnBean(PitchInferenceService.class)
public class SimulateController {

  // Unrouted diagnostic: the simulator PINS one artifact and calls it directly, never through the
  // A/B router - the per-state Markov solve requires every one of its 12 probes to come from the
  // SAME model version, and routing could stitch a mid-request promotion/flip across states into an
  // incoherent transition matrix. The role tag is "simulator" (not "champion") so this traffic can
  // never blend into any champion/shadow/challenger fleet query, and latency lands on its own
  // metric
  // (F1.6, see decisions.md).
  private static final String ROLE = "simulator";
  private static final String SERVING_MODE = "unrouted-diagnostic";
  private static final int DEFAULT_MC_TRIALS = 10_000;

  private final PitchInferenceService inference;
  private final InferenceMetrics metrics;
  private final RegistryService registry;

  public SimulateController(
      PitchInferenceService inference, InferenceMetrics metrics, RegistryService registry) {
    this.inference = inference;
    this.metrics = metrics;
    this.registry = registry;
  }

  @PostMapping("/plate-appearance")
  public SimulateResponse analytical(@Valid @RequestBody SimulateRequest req) throws OrtException {
    Timer.Sample sample = metrics.startTimer();
    try {
      IntFunction<double[]> probsByState = buildPerStateProbs(req);
      AnalyticalSolver.Solution solution = AnalyticalSolver.solve(probsByState);
      AnalyticalSolver.StateResult start = solution.at(req.startBalls(), req.startStrikes());
      long elapsedNanos = metrics.recordSimulateLatency(sample, PitchInferenceService.MODEL_NAME);
      metrics.incrementPrediction(PitchInferenceService.MODEL_NAME, ROLE);
      return new SimulateResponse(
          "analytical",
          req.startBalls(),
          req.startStrikes(),
          start.expectedPitches(),
          start.pWalk(),
          start.pStrikeout(),
          start.pInPlay(),
          null,
          PitchInferenceService.MODEL_NAME,
          PitchInferenceService.MODEL_VERSION,
          SERVING_MODE,
          registryStage(),
          elapsedNanos / 1_000L,
          MDC.get("correlation_id"));
    } catch (Exception e) {
      metrics.incrementError(PitchInferenceService.MODEL_NAME, ROLE, e.getClass().getSimpleName());
      throw e;
    }
  }

  @PostMapping("/plate-appearance/monte-carlo")
  public SimulateResponse monteCarlo(@Valid @RequestBody SimulateRequest req) throws OrtException {
    Timer.Sample sample = metrics.startTimer();
    try {
      IntFunction<double[]> probsByState = buildPerStateProbs(req);
      int trials = req.mcTrials() != null ? req.mcTrials() : DEFAULT_MC_TRIALS;
      long seed = req.mcSeed() != null ? req.mcSeed() : System.nanoTime();
      MonteCarloSimulator.Result r =
          MonteCarloSimulator.run(req.startBalls(), req.startStrikes(), trials, probsByState, seed);
      long elapsedNanos = metrics.recordSimulateLatency(sample, PitchInferenceService.MODEL_NAME);
      metrics.incrementPrediction(PitchInferenceService.MODEL_NAME, ROLE);
      return new SimulateResponse(
          "monte_carlo",
          req.startBalls(),
          req.startStrikes(),
          r.meanPitches(),
          r.pWalk(),
          r.pStrikeout(),
          r.pInPlay(),
          trials,
          PitchInferenceService.MODEL_NAME,
          PitchInferenceService.MODEL_VERSION,
          SERVING_MODE,
          registryStage(),
          elapsedNanos / 1_000L,
          MDC.get("correlation_id"));
    } catch (Exception e) {
      metrics.incrementError(PitchInferenceService.MODEL_NAME, ROLE, e.getClass().getSimpleName());
      throw e;
    }
  }

  /**
   * The pinned served artifact's registry stage (e.g. {@code "shadow"}), or {@code null} when it
   * has no matching registry row. Informational only - the simulator serves the pinned artifact
   * directly regardless of stage. Cheap: one lookup by model name on a rate-limited endpoint.
   */
  private String registryStage() {
    return registry.findByName(PitchInferenceService.MODEL_NAME).stream()
        .filter(mv -> mv.version().equals(PitchInferenceService.MODEL_VERSION))
        .map(mv -> mv.stage().name().toLowerCase(Locale.ROOT))
        .findFirst()
        .orElse(null);
  }

  /**
   * Compute and cache the per-(balls, strikes) probability vector for every transient state. Done
   * eagerly so both the analytical solver and the MC sampler share the same 12 ONNX calls per
   * request.
   */
  private IntFunction<double[]> buildPerStateProbs(SimulateRequest req) throws OrtException {
    Map<Integer, double[]> cache = new HashMap<>();
    List<String> classLabels = inference.classLabels();
    for (int s = 0; s < PlateAppearanceMarkov.N_TRANSIENT; s++) {
      int[] bs = PlateAppearanceMarkov.unpackTransient(s);
      FeaturePipelinePitchPre.Request pitchReq = pitchRequestForCount(req, bs[0], bs[1]);
      Map<String, Double> probs = inference.predict(pitchReq);
      double[] ordered = new double[PitchOutcome.COUNT];
      for (int c = 0; c < classLabels.size(); c++) {
        ordered[c] = probs.getOrDefault(classLabels.get(c), 0.0);
      }
      cache.put(s, ordered);
    }
    return cache::get;
  }

  private static FeaturePipelinePitchPre.Request pitchRequestForCount(
      SimulateRequest req, int balls, int strikes) {
    return new FeaturePipelinePitchPre.Request(
        balls,
        strikes,
        req.outs(),
        req.inning(),
        req.baseState(),
        // score_diff: keep as-is; the simulator does not project run-expectancy yet.
        req.scoreDiff(),
        req.dow(),
        req.pitcherThrows(),
        req.batterStand(),
        req.parkId(),
        req.pitcherId(),
        req.batterId(),
        req.pitcherPitchesLast28d(),
        req.pitcherPitchesInGame(),
        req.daysSinceLastAppearance(),
        req.pitcherStrikeRate28d(),
        req.pitcherSwstrikeRate28d(),
        req.pitcherInplayRate28d(),
        req.pitcherStrikeRateStd(),
        req.batterStrikeRate28d(),
        req.batterInplayRate28d(),
        req.batterBallRate28d(),
        req.batterInplayRateStd());
  }
}
