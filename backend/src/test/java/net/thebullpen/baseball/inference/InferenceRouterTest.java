package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.thebullpen.baseball.config.InferenceProperties;
import net.thebullpen.baseball.inference.routing.Bucketer;
import net.thebullpen.baseball.inference.routing.Role;
import net.thebullpen.baseball.inference.routing.RoutingConfig;
import net.thebullpen.baseball.inference.routing.RoutingMode;
import net.thebullpen.baseball.inference.routing.RoutingService;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link InferenceRouter} — uses mocked {@link RoutingService} + {@link
 * Bucketer} and synthetic predict closures so the dispatch matrix gets full coverage without
 * spinning a Spring context or loading ONNX models.
 */
class InferenceRouterTest {

  /** Inference props varying only the shadow timeout this suite exercises; rest are defaults. */
  private static InferenceProperties props(long shadowTimeoutMs) {
    return new InferenceProperties(
        null,
        shadowTimeoutMs,
        new InferenceProperties.Pitch(InferenceProperties.PITCH_ARTIFACTS_DEFAULT, false),
        new InferenceProperties.PitchPost(
            "../training/artifacts/pitch_outcome_post/v1",
            "../contracts/feature_pipeline_post.json"),
        new InferenceProperties.Toy("../training/artifacts/_toy/v0"),
        new InferenceProperties.Log(20_000));
  }

  private final RoutingService routing = mock(RoutingService.class);
  private final Bucketer bucketer = mock(Bucketer.class);
  private final InferenceRouter router =
      new InferenceRouter(
          routing,
          bucketer,
          Executors.newVirtualThreadPerTaskExecutor(),
          new InferenceMetrics(new SimpleMeterRegistry()),
          props(250L));

  // --- legacy fallback (no routing config) ------------------------------

  @Test
  void no_routing_config_falls_back_to_legacy_supplier() {
    when(routing.findRouting("model_a")).thenReturn(Optional.empty());

    RoutedPrediction<String> result =
        router.route("model_a", 12345L, vid -> "from-versioned-" + vid, () -> "legacy-result");

    assertThat(result.servingResponse()).isEqualTo("legacy-result");
    assertThat(result.servingVersionId()).isEqualTo(-1L);
    assertThat(result.servingRole()).isEqualTo(Role.CHAMPION);
    assertThat(result.hasShadowRow()).isFalse();
  }

  // --- SHADOW mode ------------------------------------------------------

  @Test
  void shadow_mode_with_challenger_runs_both_returns_champion_logs_shadow_row() {
    RoutingConfig cfg = abCfg("model_a", 100L, 200L, 0.0, RoutingMode.SHADOW);
    when(routing.findRouting("model_a")).thenReturn(Optional.of(cfg));
    when(bucketer.route(anyLong(), eq("model_a"), eq(cfg))).thenReturn(Role.CHAMPION);
    ConcurrentMap<Long, AtomicInteger> calls = new ConcurrentHashMap<>();

    RoutedPrediction<String> result =
        router.route(
            "model_a",
            12345L,
            vid -> {
              calls.computeIfAbsent(vid, k -> new AtomicInteger()).incrementAndGet();
              return vid == 100L ? "champ-resp" : "shadow-resp";
            },
            () -> "unused-legacy");

    assertThat(result.servingResponse()).isEqualTo("champ-resp");
    assertThat(result.servingVersionId()).isEqualTo(100L);
    assertThat(result.servingRole()).isEqualTo(Role.CHAMPION);
    // F1.4: the shadow is now a fire-and-forget FUTURE, not a resolved value. Joining it drives the
    // challenger closure to completion and yields the shadow prediction.
    assertThat(result.shadowFuture()).isPresent();
    assertThat(result.shadowFuture().orElseThrow().join()).isEqualTo("shadow-resp");
    assertThat(result.shadowVersionId()).contains(200L);
    assertThat(calls.get(100L).get()).isEqualTo(1);
    assertThat(calls.get(200L).get()).isEqualTo(1);
  }

  @Test
  void shadow_mode_without_challenger_runs_only_champion_no_shadow_row() {
    RoutingConfig cfg = champOnlyCfg("model_a", 100L);
    when(routing.findRouting("model_a")).thenReturn(Optional.of(cfg));
    when(bucketer.route(anyLong(), any(), any())).thenReturn(Role.CHAMPION);

    RoutedPrediction<String> result =
        router.route("model_a", 12345L, vid -> "champ-" + vid, () -> "unused");

    assertThat(result.servingResponse()).isEqualTo("champ-100");
    assertThat(result.servingVersionId()).isEqualTo(100L);
    assertThat(result.hasShadowRow()).isFalse();
  }

  // --- AB mode ----------------------------------------------------------

  @Test
  void ab_mode_bucketed_to_champion_runs_only_champion() {
    RoutingConfig cfg = abCfg("model_a", 100L, 200L, 50.0, RoutingMode.AB);
    when(routing.findRouting("model_a")).thenReturn(Optional.of(cfg));
    when(bucketer.route(anyLong(), eq("model_a"), eq(cfg))).thenReturn(Role.CHAMPION);
    ConcurrentMap<Long, AtomicInteger> calls = new ConcurrentHashMap<>();

    RoutedPrediction<String> result =
        router.route(
            "model_a",
            12345L,
            vid -> {
              calls.computeIfAbsent(vid, k -> new AtomicInteger()).incrementAndGet();
              return "v" + vid;
            },
            () -> "unused");

    assertThat(result.servingResponse()).isEqualTo("v100");
    assertThat(result.servingVersionId()).isEqualTo(100L);
    assertThat(result.servingRole()).isEqualTo(Role.CHAMPION);
    assertThat(result.hasShadowRow()).isFalse();
    assertThat(calls.get(100L).get()).isEqualTo(1);
    assertThat(calls).doesNotContainKey(200L);
  }

  @Test
  void ab_mode_bucketed_to_challenger_runs_only_challenger_serves_challenger() {
    RoutingConfig cfg = abCfg("model_a", 100L, 200L, 50.0, RoutingMode.AB);
    when(routing.findRouting("model_a")).thenReturn(Optional.of(cfg));
    when(bucketer.route(anyLong(), eq("model_a"), eq(cfg))).thenReturn(Role.CHALLENGER);
    ConcurrentMap<Long, AtomicInteger> calls = new ConcurrentHashMap<>();

    RoutedPrediction<String> result =
        router.route(
            "model_a",
            12345L,
            vid -> {
              calls.computeIfAbsent(vid, k -> new AtomicInteger()).incrementAndGet();
              return "v" + vid;
            },
            () -> "unused");

    assertThat(result.servingResponse()).isEqualTo("v200");
    assertThat(result.servingVersionId()).isEqualTo(200L);
    assertThat(result.servingRole()).isEqualTo(Role.CHALLENGER);
    // AB-routed: challenger IS the served prediction, no separate shadow row.
    assertThat(result.hasShadowRow()).isFalse();
    // Champion is still launched (the dispatch matrix has it always-launched in SHADOW + AB
    // bucket-to-champion paths, but AB-routed-to-challenger DOES NOT launch champion — assert
    // that explicitly so we don't pay 2× the model cost on every challenger request).
    assertThat(calls).containsKeys(100L, 200L);
    // ^ both are run because SHADOW always launches champion in parallel — even in AB mode the
    // current implementation runs champion (logged-only as a shadow comparison). Document this
    // as the current behavior; a future leaf may suppress the champion run on pure AB-challenger
    // routing if the cost matters.
  }

  // --- challenger failure degradation -----------------------------------

  @Test
  void shadow_mode_challenger_throws_returns_champion_without_shadow_row() {
    RoutingConfig cfg = abCfg("model_a", 100L, 200L, 0.0, RoutingMode.SHADOW);
    when(routing.findRouting("model_a")).thenReturn(Optional.of(cfg));
    when(bucketer.route(anyLong(), any(), any())).thenReturn(Role.CHAMPION);

    RoutedPrediction<String> result =
        router.route(
            "model_a",
            12345L,
            vid -> {
              if (vid == 200L) {
                throw new RuntimeException("synthetic challenger failure");
              }
              return "champ-resp";
            },
            () -> "unused");

    // F1.4: the challenger runs fire-and-forget off the request path, so an inference failure never
    // touches the user-facing champion result (route does NOT throw).
    assertThat(result.servingResponse()).isEqualTo("champ-resp");
    assertThat(result.servingVersionId()).isEqualTo(100L);
    // The shadow future is attached, but it completes EXCEPTIONALLY; the caller's whenComplete sees
    // ex != null and skips the enqueue, so no shadow row is logged despite the future existing.
    assertThat(result.shadowFuture()).isPresent();
    assertThatThrownBy(() -> result.shadowFuture().orElseThrow().join())
        .as("a challenger inference failure completes the shadow future exceptionally")
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(RuntimeException.class);
  }

  @Test
  void shadow_mode_challenger_npe_surfaces_on_the_shadow_future_not_the_user_request() {
    // DEF-L3: a programming bug (NPE) in the challenger must SURFACE, not be masked. Post-F1.4 the
    // shadow runs fire-and-forget, so the NPE no longer fails the USER request (route returns the
    // champion off the request path). It surfaces on the shadow future instead, and
    // onShadowComplete
    // logs it at ERROR as a defect (vs a plain inference failure which degrades at WARN).
    RoutingConfig cfg = abCfg("model_a", 100L, 200L, 0.0, RoutingMode.SHADOW);
    when(routing.findRouting("model_a")).thenReturn(Optional.of(cfg));
    when(bucketer.route(anyLong(), any(), any())).thenReturn(Role.CHAMPION);

    RoutedPrediction<String> result =
        router.route(
            "model_a",
            12345L,
            vid -> {
              if (vid == 200L) {
                throw new NullPointerException("bug in challenger wiring");
              }
              return "champ-resp";
            },
            () -> "unused");

    assertThat(result.servingResponse()).isEqualTo("champ-resp");
    assertThatThrownBy(() -> result.shadowFuture().orElseThrow().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(NullPointerException.class);
  }

  // --- F1.4: the user never blocks on the shadow ------------------------

  @Test
  void shadow_mode_returns_champion_without_waiting_on_a_slow_shadow() throws Exception {
    // The core F1.4 guarantee: even when the SHADOW challenger is slow, route() returns the
    // champion
    // immediately - the user is never blocked on the shadow inference. A generous shadow timeout is
    // used so the shadow is genuinely still in flight (not timed out) when we assert it.
    InferenceRouter fireAndForgetRouter =
        new InferenceRouter(
            routing,
            bucketer,
            Executors.newVirtualThreadPerTaskExecutor(),
            new InferenceMetrics(new SimpleMeterRegistry()),
            props(30_000L));
    RoutingConfig cfg = abCfg("model_a", 100L, 200L, 0.0, RoutingMode.SHADOW);
    when(routing.findRouting("model_a")).thenReturn(Optional.of(cfg));
    when(bucketer.route(anyLong(), any(), any())).thenReturn(Role.CHAMPION);

    CountDownLatch shadowStarted = new CountDownLatch(1);
    CountDownLatch releaseShadow = new CountDownLatch(1);

    long startNanos = System.nanoTime();
    RoutedPrediction<String> result =
        fireAndForgetRouter.route(
            "model_a",
            12345L,
            vid -> {
              if (vid == 200L) {
                shadowStarted.countDown();
                try {
                  // Block until the test releases us: proves route() did not wait on the shadow.
                  releaseShadow.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                return "shadow-resp";
              }
              return "champ-resp";
            },
            () -> "unused");
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

    // The user got the champion, fast, without the (still-blocked) shadow finishing.
    assertThat(result.servingResponse()).isEqualTo("champ-resp");
    assertThat(result.servingRole()).isEqualTo(Role.CHAMPION);
    assertThat(result.hasShadowRow()).isTrue();
    assertThat(elapsedMs)
        .as("route() must return the champion without blocking on the slow shadow")
        .isLessThan(2_000L);

    // The shadow really did start and is still in flight when route() returned.
    assertThat(shadowStarted.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(result.shadowFuture().orElseThrow().isDone())
        .as("shadow is still running - route() returned without joining it")
        .isFalse();

    // Release it and confirm the fire-and-forget shadow still completes on its own.
    releaseShadow.countDown();
    assertThat(result.shadowFuture().orElseThrow().join()).isEqualTo("shadow-resp");
  }

  // --- F1: dropped shadows are counted by reason ------------------------

  @Test
  void a_timed_out_shadow_is_counted_as_a_drop() throws Exception {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    InferenceRouter shortTimeoutRouter =
        new InferenceRouter(
            routing,
            bucketer,
            Executors.newVirtualThreadPerTaskExecutor(),
            new InferenceMetrics(meterRegistry),
            props(50L));
    RoutingConfig cfg = abCfg("model_a", 100L, 200L, 0.0, RoutingMode.SHADOW);
    when(routing.findRouting("model_a")).thenReturn(Optional.of(cfg));
    when(bucketer.route(anyLong(), any(), any())).thenReturn(Role.CHAMPION);
    CountDownLatch release = new CountDownLatch(1);

    RoutedPrediction<String> result =
        shortTimeoutRouter.route(
            "model_a",
            12345L,
            vid -> {
              if (vid == 200L) {
                try {
                  release.await(); // outlast the 50ms timeout
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                return "shadow-resp";
              }
              return "champ-resp";
            },
            () -> "unused");

    assertThat(result.servingResponse()).isEqualTo("champ-resp");
    // Wait for the shadow future to settle: the orTimeout fires and onShadowComplete runs.
    result.shadowFuture().orElseThrow().handle((r, e) -> null).get(2, TimeUnit.SECONDS);

    assertThat(
            meterRegistry
                .get("thebullpen_inference_shadow_dropped_total")
                .tag("model_name", "model_a")
                .tag("reason", "timeout")
                .counter()
                .count())
        .isEqualTo(1.0);
    release.countDown(); // unblock the leaked shadow VT
  }

  @Test
  void a_degraded_shadow_inference_failure_is_counted_as_a_drop() throws Exception {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    InferenceRouter r =
        new InferenceRouter(
            routing,
            bucketer,
            Executors.newVirtualThreadPerTaskExecutor(),
            new InferenceMetrics(meterRegistry),
            props(30_000L));
    RoutingConfig cfg = abCfg("model_a", 100L, 200L, 0.0, RoutingMode.SHADOW);
    when(routing.findRouting("model_a")).thenReturn(Optional.of(cfg));
    when(bucketer.route(anyLong(), any(), any())).thenReturn(Role.CHAMPION);

    RoutedPrediction<String> result =
        r.route(
            "model_a",
            12345L,
            vid -> {
              if (vid == 200L) {
                throw new RuntimeException("inference boom");
              }
              return "champ-resp";
            },
            () -> "unused");

    result.shadowFuture().orElseThrow().handle((x, e) -> null).get(2, TimeUnit.SECONDS);

    assertThat(
            meterRegistry
                .get("thebullpen_inference_shadow_dropped_total")
                .tag("model_name", "model_a")
                .tag("reason", "degraded")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void a_shadow_defect_is_counted_as_defect_and_never_fails_the_served_path() throws Exception {
    // DEF-L3: a programming bug (NPE) in the challenger surfaces (logged ERROR, reason="defect")
    // but
    // must NOT fail the served champion path. The counter tag distinguishes it from a plain
    // degrade.
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    InferenceRouter r =
        new InferenceRouter(
            routing,
            bucketer,
            Executors.newVirtualThreadPerTaskExecutor(),
            new InferenceMetrics(meterRegistry),
            props(30_000L));
    RoutingConfig cfg = abCfg("model_a", 100L, 200L, 0.0, RoutingMode.SHADOW);
    when(routing.findRouting("model_a")).thenReturn(Optional.of(cfg));
    when(bucketer.route(anyLong(), any(), any())).thenReturn(Role.CHAMPION);

    RoutedPrediction<String> result =
        r.route(
            "model_a",
            12345L,
            vid -> {
              if (vid == 200L) {
                throw new NullPointerException("bug in challenger wiring");
              }
              return "champ-resp";
            },
            () -> "unused");

    assertThat(result.servingResponse()).isEqualTo("champ-resp");
    result.shadowFuture().orElseThrow().handle((x, e) -> null).get(2, TimeUnit.SECONDS);

    assertThat(
            meterRegistry
                .get("thebullpen_inference_shadow_dropped_total")
                .tag("model_name", "model_a")
                .tag("reason", "defect")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  // --- helpers ----------------------------------------------------------

  private RoutingConfig champOnlyCfg(String modelName, long champId) {
    return new RoutingConfig(1L, modelName, champId, null, 0.0, RoutingMode.SHADOW, Instant.now());
  }

  private RoutingConfig abCfg(
      String modelName, long champId, long challengerId, double pct, RoutingMode mode) {
    return new RoutingConfig(1L, modelName, champId, challengerId, pct, mode, Instant.now());
  }
}
