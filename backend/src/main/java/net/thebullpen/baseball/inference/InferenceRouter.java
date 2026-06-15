package net.thebullpen.baseball.inference;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import net.thebullpen.baseball.inference.routing.Bucketer;
import net.thebullpen.baseball.inference.routing.Role;
import net.thebullpen.baseball.inference.routing.RoutingConfig;
import net.thebullpen.baseball.inference.routing.RoutingMode;
import net.thebullpen.baseball.inference.routing.RoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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

  public InferenceRouter(
      RoutingService routingService,
      Bucketer bucketer,
      @Qualifier("inferenceShadowExecutor") ExecutorService executor) {
    this.routingService = routingService;
    this.bucketer = bucketer;
    this.executor = executor;
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

    // Always run champion in parallel (returned in shadow + AB-champion cases).
    CompletableFuture<Resp> championFut =
        CompletableFuture.supplyAsync(
            () -> predictByVersionId.apply(cfg.championVersionId()), executor);

    // Run challenger when: SHADOW with a challenger registered, OR AB-mode bucketed to challenger.
    boolean runChallenger =
        cfg.hasChallenger()
            && (cfg.mode() == RoutingMode.SHADOW
                || (cfg.mode() == RoutingMode.AB && primaryRole == Role.CHALLENGER));
    CompletableFuture<Resp> challengerFut =
        runChallenger
            ? CompletableFuture.supplyAsync(
                () -> predictByVersionId.apply(cfg.challengerVersionId()), executor)
            : null;

    Resp championResp = championFut.join();
    Resp challengerResp = challengerFut == null ? null : safeJoin(challengerFut, modelName);

    // AB-routed-to-challenger AND the challenger ran successfully → serve the challenger.
    boolean abServeChallenger =
        cfg.mode() == RoutingMode.AB && primaryRole == Role.CHALLENGER && challengerResp != null;

    Resp servingResp = abServeChallenger ? challengerResp : championResp;
    long servingVersionId = abServeChallenger ? cfg.challengerVersionId() : cfg.championVersionId();
    Role servingRole = abServeChallenger ? Role.CHALLENGER : Role.CHAMPION;

    // Shadow row is logged separately ONLY in SHADOW mode (in AB mode the challenger IS the
    // served prediction — no separate "shadow" row, just a CHALLENGER serving row).
    Optional<Resp> shadowResp =
        cfg.mode() == RoutingMode.SHADOW && challengerResp != null
            ? Optional.of(challengerResp)
            : Optional.empty();
    Optional<Long> shadowVersionId =
        shadowResp.isPresent() ? Optional.of(cfg.challengerVersionId()) : Optional.empty();

    return new RoutedPrediction<>(
        servingResp, servingVersionId, servingRole, shadowResp, shadowVersionId);
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
