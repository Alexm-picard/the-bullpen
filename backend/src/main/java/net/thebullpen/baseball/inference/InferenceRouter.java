package net.thebullpen.baseball.inference;

import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import net.thebullpen.baseball.inference.routing.Bucketer;
import net.thebullpen.baseball.inference.routing.Role;
import net.thebullpen.baseball.inference.routing.RoutingConfig;
import net.thebullpen.baseball.inference.routing.RoutingMode;
import net.thebullpen.baseball.inference.routing.RoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Champion + (shadow | challenger) dispatcher (leaf 3b.3). Generic over request/response types via
 * a {@link Function} that maps a {@code version_id} to a prediction — the controller passes the
 * closure that knows how to invoke its specific model (the router never sees feature shapes or
 * output shapes, only opaque payloads).
 *
 * <p>Dispatch matrix (cfg = {@link RoutingConfig}):
 *
 * <table>
 *   <tr><th>cfg.mode</th><th>has challenger?</th><th>bucket role</th><th>champion runs?</th>
 *       <th>challenger runs?</th><th>serving role</th><th>shadow row logged?</th></tr>
 *   <tr><td>(no config)</td><td>—</td><td>—</td><td>via legacyFallback</td><td>no</td>
 *       <td>CHAMPION</td><td>no</td></tr>
 *   <tr><td>SHADOW</td><td>no</td><td>—</td><td>yes</td><td>no</td><td>CHAMPION</td><td>no</td></tr>
 *   <tr><td>SHADOW</td><td>yes</td><td>—</td><td>yes</td><td>yes (parallel)</td><td>CHAMPION</td>
 *       <td>YES (SHADOW row)</td></tr>
 *   <tr><td>AB</td><td>no</td><td>CHAMPION</td><td>yes</td><td>no</td><td>CHAMPION</td>
 *       <td>no</td></tr>
 *   <tr><td>AB</td><td>yes</td><td>CHAMPION</td><td>yes</td><td>no</td><td>CHAMPION</td>
 *       <td>no</td></tr>
 *   <tr><td>AB</td><td>yes</td><td>CHALLENGER</td><td>no</td><td>yes</td><td>CHALLENGER</td>
 *       <td>no (the served prediction IS the challenger)</td></tr>
 * </table>
 *
 * <p>The champion and the shadow/challenger run on a dedicated virtual-thread-per-task executor
 * ({@code inferenceShadowExecutor}, see {@link
 * net.thebullpen.baseball.config.InferenceExecutorConfig}), passed explicitly to {@link
 * CompletableFuture#supplyAsync}. This is deliberate: the no-executor {@code supplyAsync} runs on
 * the bounded {@code ForkJoinPool.commonPool()} (parallelism = #cores - 1), which {@code
 * spring.threads.virtual.enabled} does NOT replace (that flag wires the Tomcat request executor +
 * {@code @Async}). Blocking ONNX inference on the shared commonPool could stack on a low-core box;
 * the explicit VT executor keeps the leaf's "p95 within 10% of single-model" target true - both
 * models run truly concurrently, never stacked.
 *
 * <p>Challenger failures degrade silently (logged at WARN) — leaf "Known edge cases": user request
 * never fails because the shadow run threw. The champion result is always returned.
 */
@Component
public class InferenceRouter {

  private static final Logger log = LoggerFactory.getLogger(InferenceRouter.class);

  private final RoutingService routingService;
  private final Bucketer bucketer;
  private final ExecutorService executor;
  private final InferenceMetrics metrics;
  private final long shadowTimeoutMs;

  public InferenceRouter(
      RoutingService routingService,
      Bucketer bucketer,
      @Qualifier("inferenceShadowExecutor") ExecutorService executor,
      InferenceMetrics metrics,
      @Value("${bullpen.inference.shadow-timeout-ms:250}") long shadowTimeoutMs) {
    this.routingService = routingService;
    this.bucketer = bucketer;
    this.executor = executor;
    this.metrics = metrics;
    this.shadowTimeoutMs = shadowTimeoutMs;
  }

  /**
   * Route + dispatch a single prediction request.
   *
   * @param modelName the registered {@code model_name} this request is for.
   * @param gameId the game-id key for bucketing. Routes to challenger consistently across replays
   *     of the same game.
   * @param predictByVersionId a closure: given a registered {@code version_id}, return the
   *     prediction. When no routing row exists for {@code modelName} (e.g. the model isn't
   *     registered yet — current state for production heads), the router falls back to {@code
   *     legacyFallback} instead, with a sentinel {@code version_id} of {@code -1L} on the returned
   *     {@link RoutedPrediction#servingVersionId()}.
   * @param legacyFallback the closure used when no routing config exists. Typically wraps a direct
   *     call into the legacy inference bean.
   * @param <Resp> the prediction response type — opaque to the router.
   */
  public <Resp> RoutedPrediction<Resp> route(
      String modelName,
      long gameId,
      Function<Long, Resp> predictByVersionId,
      java.util.function.Supplier<Resp> legacyFallback) {
    Optional<RoutingConfig> cfgOpt = routingService.findRouting(modelName);
    if (cfgOpt.isEmpty()) {
      Resp resp = legacyFallback.get();
      return new RoutedPrediction<>(
          resp,
          /* servingVersionId */ -1L,
          Role.CHAMPION,
          /* shadowResponse */ Optional.empty(),
          /* shadowVersionId */ Optional.empty());
    }
    RoutingConfig cfg = cfgOpt.get();
    Role primaryRole = bucketer.route(gameId, modelName, cfg);

    // Champion always runs on the VT executor.
    CompletableFuture<Resp> championFut =
        CompletableFuture.supplyAsync(
            () -> predictByVersionId.apply(cfg.championVersionId()), executor);

    // AB bucketed to challenger: the challenger IS the served prediction, so the user DOES wait on
    // it (correct - it's what they get back). A challenger failure degrades to the champion. This
    // is NOT the shadow path. Both models run and complete here (pre-F1.4 AB semantics, unchanged):
    // the champion is joined before the serve decision so a challenger failure has a resolved
    // champion to fall back to, and the champion latency is a real parallel leg, not
    // fire-and-forget.
    if (cfg.mode() == RoutingMode.AB && primaryRole == Role.CHALLENGER && cfg.hasChallenger()) {
      CompletableFuture<Resp> challengerFut =
          CompletableFuture.supplyAsync(
              () -> predictByVersionId.apply(cfg.challengerVersionId()), executor);
      Resp abChampionResp = championFut.join();
      Resp challengerResp = safeJoin(challengerFut, modelName);
      if (challengerResp != null) {
        return new RoutedPrediction<>(
            challengerResp,
            cfg.challengerVersionId(),
            Role.CHALLENGER,
            Optional.empty(),
            Optional.empty());
      }
      return new RoutedPrediction<>(
          abChampionResp,
          cfg.championVersionId(),
          Role.CHAMPION,
          Optional.empty(),
          Optional.empty());
    }

    Resp championResp = championFut.join();

    // SHADOW with a challenger: run it FIRE-AND-FORGET off the request path (F1.4). We return the
    // champion IMMEDIATELY without joining the shadow - the shadow-join here used to stall every
    // user request by a full extra inference. The shadow future is bounded by orTimeout, records
    // its own latency (never blended into the served metric), and surfaces defects loudly via
    // onShadowComplete; the caller attaches a whenComplete to log the SHADOW row when it lands.
    if (cfg.mode() == RoutingMode.SHADOW && cfg.hasChallenger()) {
      Timer.Sample sample = metrics.startTimer();
      CompletableFuture<Resp> shadowFut =
          CompletableFuture.supplyAsync(
                  () -> predictByVersionId.apply(cfg.challengerVersionId()), executor)
              .orTimeout(shadowTimeoutMs, TimeUnit.MILLISECONDS)
              .whenComplete((resp, ex) -> onShadowComplete(modelName, sample, ex));
      return new RoutedPrediction<>(
          championResp,
          cfg.championVersionId(),
          Role.CHAMPION,
          Optional.of(shadowFut),
          Optional.of(cfg.challengerVersionId()));
    }

    return new RoutedPrediction<>(
        championResp, cfg.championVersionId(), Role.CHAMPION, Optional.empty(), Optional.empty());
  }

  /**
   * Completion hook for the fire-and-forget shadow leg. On success it records shadow latency on the
   * dedicated metric; on failure it preserves the DEF-L3 distinction (a programming bug / {@link
   * Error} is a DEFECT and is surfaced at ERROR; a normal inference failure or an {@code orTimeout}
   * is a degrade and is logged at WARN). It never rethrows - the shadow is off the request path, so
   * a shadow defect must NOT fail the user (it is loud in the logs instead).
   */
  private void onShadowComplete(String modelName, Timer.Sample sample, Throwable ex) {
    if (ex == null) {
      metrics.recordShadowLatency(sample, modelName);
      return;
    }
    Throwable cause =
        ex instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : ex;
    if (cause instanceof Error
        || cause instanceof NullPointerException
        || cause instanceof ClassCastException) {
      log.error(
          "shadow challenger for {} threw a DEFECT off the request path (surfacing, not degrading)",
          modelName,
          cause);
    } else {
      log.warn("shadow challenger for {} degraded off the request path: {}", modelName, cause);
    }
  }

  private static <Resp> Resp safeJoin(CompletableFuture<Resp> fut, String modelName) {
    try {
      return fut.join();
    } catch (CompletionException e) {
      // DEF-L3: degrade silently on inference / loading failures (the shadow contract), but never
      // mask a programming bug (NPE / CCE) or a JVM Error as a "challenger failed" event - those
      // are
      // defects, not a degraded shadow run, and must surface loudly.
      Throwable cause = e.getCause();
      if (cause instanceof Error err) {
        throw err;
      }
      if (cause instanceof NullPointerException || cause instanceof ClassCastException) {
        throw e;
      }
      log.warn(
          "InferenceRouter: challenger/shadow prediction for {} failed; degrading silently",
          modelName,
          cause != null ? cause : e);
      return null;
    }
  }
}
