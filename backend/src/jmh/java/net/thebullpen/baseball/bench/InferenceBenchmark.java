package net.thebullpen.baseball.bench;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import net.thebullpen.baseball.inference.OnnxModel;
import net.thebullpen.baseball.inference.routing.Bucketer;
import net.thebullpen.baseball.inference.routing.Role;
import net.thebullpen.baseball.inference.routing.RoutingConfig;
import net.thebullpen.baseball.inference.routing.RoutingMode;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Microbenchmarks for the inference hot path (plan S1g). Three things every served prediction pays
 * for:
 *
 * <ul>
 *   <li>{@link #onnxBattedBallPredict} — a single ONNX session forward pass on the toy batted-ball
 *       model (the serving floor; p50/p99 of the in-process inference).
 *   <li>{@link #routerBucketDecision} — the Murmur3 game-id bucketing the A/B router does per
 *       request.
 *   <li>{@link #routerAbRouteDecision} — the full A/B route decision (bucket + mode + traffic
 *       split) producing a {@link Role}.
 * </ul>
 *
 * Run with {@code ./gradlew jmh}. CI runs this nightly against a committed baseline ({@code
 * backend/benchmarks/baseline.json}) and fails on a &gt;25% regression — it is NOT a per-PR gate
 * because JMH timing flaps on shared CI runners.
 *
 * <p>Requires the toy ONNX artifact at {@code ../training/artifacts/_toy/v0/model.onnx} (generate
 * via {@code uv run python -m bullpen_training.battedball.generate_ci_artifacts}).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
public class InferenceBenchmark {

  private OnnxModel model;
  private float[] features;
  private Bucketer bucketer;
  private RoutingConfig abConfig;

  @Setup
  public void setup() throws Exception {
    Path modelPath =
        Path.of(System.getProperty("user.dir"))
            .resolve("../training/artifacts/_toy/v0/model.onnx")
            .normalize();
    model = new OnnxModel(modelPath);
    // [launch_speed_mph, launch_angle_deg, release_speed_mph, park_hr_rate, stand_is_left]
    features = new float[] {100.0f, 28.0f, 95.0f, 0.045f, 0.0f};
    bucketer = new Bucketer();
    abConfig =
        new RoutingConfig(1L, "pitch_outcome_pre", 10L, 11L, 20.0, RoutingMode.AB, Instant.now());
  }

  @TearDown
  public void teardown() throws Exception {
    model.close();
  }

  @Benchmark
  public float onnxBattedBallPredict() throws Exception {
    return model.predict(features);
  }

  @Benchmark
  public int routerBucketDecision() {
    return bucketer.bucket(123_456_789L, "pitch_outcome_pre");
  }

  @Benchmark
  public Role routerAbRouteDecision() {
    return bucketer.route(123_456_789L, "pitch_outcome_pre", abConfig);
  }
}
