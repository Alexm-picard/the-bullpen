package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import net.thebullpen.baseball.inference.FeaturePipelineBattedBall;
import net.thebullpen.baseball.inference.LoadedAllParksModel;
import net.thebullpen.baseball.inference.ModelLoader;
import net.thebullpen.baseball.inference.PitchPredictionService;
import net.thebullpen.baseball.inference.ToyBattedBallInference;
import net.thebullpen.baseball.inference.routing.RoutingConfig;
import net.thebullpen.baseball.inference.routing.RoutingMode;
import net.thebullpen.baseball.inference.routing.RoutingService;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link WarmupReadiness}. Mocks the ONNX boundary (ModelLoader /
 * ToyBattedBallInference / the final LoadedAllParksModel, mockable via the Mockito 5 inline
 * mock-maker) and the two registry-facing services, so no real model is loaded: the dispatch
 * decisions (which model gets warmed, fail-closed vs best-effort, and the readiness state machine)
 * are what these lock. Only the REAL ONNX graph compile is left to the inference ITs + box smoke.
 */
class WarmupReadinessTest {

  private final ModelLoader modelLoader = mock(ModelLoader.class);
  private final ToyBattedBallInference toy = mock(ToyBattedBallInference.class);
  private final RoutingService routingService = mock(RoutingService.class);
  private final RegistryService registry = mock(RegistryService.class);

  private final List<ReadinessState> states = new ArrayList<>();
  private final ApplicationEventPublisher publisher =
      event -> {
        if (event instanceof AvailabilityChangeEvent<?> ace
            && ace.getState() instanceof ReadinessState rs) {
          states.add(rs);
        }
      };

  private final WarmupReadiness warmup =
      new WarmupReadiness(toy, modelLoader, routingService, registry, publisher);

  // --- resolveChampionId (pure decision logic) -----------------------------

  @Test
  void resolve_champion_prefers_the_routing_champion() {
    when(routingService.findRouting("m")).thenReturn(Optional.of(routing(42L, null)));

    assertThat(warmup.resolveChampionId("m")).isEqualTo(OptionalLong.of(42L));
    verify(registry, never()).findChampion(anyString()); // routing short-circuits the registry
  }

  @Test
  void resolve_champion_falls_back_to_the_registry_live_champion() {
    when(routingService.findRouting("m")).thenReturn(Optional.empty());
    when(registry.findChampion("m")).thenReturn(Optional.of(modelVersion(7L)));

    assertThat(warmup.resolveChampionId("m")).isEqualTo(OptionalLong.of(7L));
  }

  @Test
  void resolve_champion_is_empty_when_unregistered() {
    when(routingService.findRouting("m")).thenReturn(Optional.empty());
    when(registry.findChampion("m")).thenReturn(Optional.empty());

    assertThat(warmup.resolveChampionId("m")).isEqualTo(OptionalLong.empty());
  }

  // --- fail-closed on the served /parks champion ---------------------------

  @Test
  void a_battedball_champion_that_fails_to_load_keeps_readiness_down() {
    when(routingService.findRouting(WarmupReadiness.BATTED_BALL_MODEL))
        .thenReturn(Optional.of(routing(1L, null)));
    when(modelLoader.loadAllParks(1L)).thenThrow(new RuntimeException("onnx boom"));

    warmup.onApplicationEvent(null); // the event is unused

    // REFUSING was published up front; ACCEPTING never fires because warm() propagated.
    assertThat(states).containsExactly(ReadinessState.REFUSING_TRAFFIC);
  }

  @Test
  void a_registry_live_champion_that_fails_to_load_keeps_readiness_down() {
    // No routing row, but /parks still serves the registry LIVE champion (the controller fallback).
    // That branch is fail-closed too: an unloadable registry champion must keep readiness DOWN.
    when(routingService.findRouting(WarmupReadiness.BATTED_BALL_MODEL))
        .thenReturn(Optional.empty());
    when(registry.findChampion(WarmupReadiness.BATTED_BALL_MODEL))
        .thenReturn(Optional.of(modelVersion(3L)));
    when(modelLoader.loadAllParks(3L)).thenThrow(new RuntimeException("onnx boom"));

    warmup.onApplicationEvent(null);

    assertThat(states).containsExactly(ReadinessState.REFUSING_TRAFFIC);
  }

  // --- served champion loads: readiness comes UP ---------------------------

  @Test
  void a_loadable_routing_champion_warms_all_parks_and_accepts_traffic() throws Exception {
    LoadedAllParksModel model = mock(LoadedAllParksModel.class);
    when(routingService.findRouting(WarmupReadiness.BATTED_BALL_MODEL))
        .thenReturn(Optional.of(routing(10L, null)));
    when(modelLoader.loadAllParks(10L)).thenReturn(model);
    // pitch heads resolve to Optional.empty() (Mockito default) -> skipped.

    warmup.onApplicationEvent(null);

    assertThat(states)
        .containsExactly(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
    verify(model, times(WarmupReadiness.WARMUP_ITERATIONS)).predictWithCarry(any());
    verify(toy, never()).predict(anyDouble(), anyDouble(), anyDouble(), anyString(), anyString());
  }

  @Test
  void a_broken_challenger_does_not_fail_readiness_when_the_champion_loads() throws Exception {
    // Champion loads + predicts fine; the challenger's load throws. Readiness must still flip UP -
    // the shadow contract is silent-degrade and the challenger is never the served prediction.
    LoadedAllParksModel champion = mock(LoadedAllParksModel.class);
    when(routingService.findRouting(WarmupReadiness.BATTED_BALL_MODEL))
        .thenReturn(Optional.of(routing(10L, 20L)));
    when(modelLoader.loadAllParks(10L)).thenReturn(champion);
    when(modelLoader.loadAllParks(20L)).thenThrow(new RuntimeException("challenger boom"));

    warmup.onApplicationEvent(null);

    assertThat(states)
        .containsExactly(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
    verify(champion, times(WarmupReadiness.WARMUP_ITERATIONS)).predictWithCarry(any());
  }

  // --- unregistered environment: toy fallback, still ready -----------------

  @Test
  void unregistered_environment_warms_the_toy_and_accepts_traffic() throws Exception {
    when(routingService.findRouting(anyString())).thenReturn(Optional.empty());
    when(registry.findChampion(anyString())).thenReturn(Optional.empty());

    warmup.onApplicationEvent(null);

    assertThat(states)
        .containsExactly(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
    verify(toy, times(WarmupReadiness.WARMUP_ITERATIONS))
        .predict(anyDouble(), anyDouble(), anyDouble(), anyString(), anyString());
    verify(modelLoader, never()).loadAllParks(anyLong());
  }

  // --- pitch heads are best-effort: their failure must not fail readiness --

  @Test
  void a_pitch_head_that_fails_to_warm_does_not_fail_readiness() {
    // Batted-ball unregistered -> toy warms, api is ready. Pitch-pre resolves a champion but its
    // load throws; readiness must still flip to ACCEPTING.
    when(routingService.findRouting(WarmupReadiness.BATTED_BALL_MODEL))
        .thenReturn(Optional.empty());
    when(registry.findChampion(WarmupReadiness.BATTED_BALL_MODEL)).thenReturn(Optional.empty());
    when(routingService.findRouting(PitchPredictionService.PRE_MODEL_NAME))
        .thenReturn(Optional.of(routing(5L, null)));
    when(modelLoader.loadPitchPre(5L)).thenThrow(new RuntimeException("pitch boom"));
    when(routingService.findRouting(PitchPredictionService.POST_MODEL_NAME))
        .thenReturn(Optional.empty());
    when(registry.findChampion(PitchPredictionService.POST_MODEL_NAME))
        .thenReturn(Optional.empty());

    warmup.onApplicationEvent(null);

    assertThat(states)
        .containsExactly(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
  }

  @Test
  void a_pitch_post_head_that_fails_to_warm_does_not_fail_readiness() {
    // Symmetric to the pitch-pre case (guards against a copy-paste divergence in the post method):
    // battedball unregistered -> toy ready; pitch-post resolves a champion but its load throws.
    when(routingService.findRouting(WarmupReadiness.BATTED_BALL_MODEL))
        .thenReturn(Optional.empty());
    when(registry.findChampion(WarmupReadiness.BATTED_BALL_MODEL)).thenReturn(Optional.empty());
    when(routingService.findRouting(PitchPredictionService.POST_MODEL_NAME))
        .thenReturn(Optional.of(routing(8L, null)));
    when(modelLoader.loadPitchPost(8L)).thenThrow(new RuntimeException("pitch post boom"));

    warmup.onApplicationEvent(null);

    assertThat(states)
        .containsExactly(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
  }

  @Test
  void a_registry_lookup_failure_during_pitch_resolution_does_not_fail_readiness() {
    // Batted-ball unregistered -> toy warms, api ready. The pitch-pre resolution lookup itself
    // throws (a transient registry/routing blip); it must be swallowed, never fail readiness.
    when(routingService.findRouting(WarmupReadiness.BATTED_BALL_MODEL))
        .thenReturn(Optional.empty());
    when(registry.findChampion(WarmupReadiness.BATTED_BALL_MODEL)).thenReturn(Optional.empty());
    when(routingService.findRouting(PitchPredictionService.PRE_MODEL_NAME))
        .thenThrow(new RuntimeException("registry blip"));
    when(routingService.findRouting(PitchPredictionService.POST_MODEL_NAME))
        .thenReturn(Optional.empty());
    when(registry.findChampion(PitchPredictionService.POST_MODEL_NAME))
        .thenReturn(Optional.empty());

    warmup.onApplicationEvent(null);

    assertThat(states)
        .containsExactly(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
  }

  // --- challenger warm is best-effort (silent-degrade contract) ------------

  @Test
  void a_challenger_that_fails_to_warm_is_swallowed() {
    when(modelLoader.loadAllParks(99L)).thenThrow(new RuntimeException("challenger boom"));

    String label = warmup.warmChallengerBestEffort(99L, sampleReq());

    assertThat(label).contains("challenger v99").contains("warm failed");
  }

  // --- helpers -------------------------------------------------------------

  private static RoutingConfig routing(long championId, Long challengerId) {
    return new RoutingConfig(
        1L,
        "m",
        championId,
        challengerId,
        challengerId == null ? 0.0 : 0.1,
        RoutingMode.SHADOW,
        Instant.EPOCH);
  }

  private static ModelVersion modelVersion(long id) {
    return new ModelVersion(
        id,
        "m",
        "v1",
        "/tmp/model.onnx",
        "/tmp/meta.json",
        "trainhash",
        "2015-2025",
        "schemahash",
        "{}",
        Instant.EPOCH,
        Instant.EPOCH,
        Stage.CHAMPION,
        "test",
        null,
        Instant.EPOCH,
        Instant.EPOCH);
  }

  private static FeaturePipelineBattedBall.Request sampleReq() {
    return new FeaturePipelineBattedBall.Request(95.0, 28.0, 10.0, 380.0, "R", 0, 0);
  }
}
