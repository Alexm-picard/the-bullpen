package net.thebullpen.baseball.inference.routing;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stable game-id bucketing for the A/B router (decision [71]). Maps a {@code (gameId, modelName)}
 * pair (or a {@code (sessionUuid, modelName)} fallback for non-game requests like the Park
 * Explorer's hypothetical predictions) into a bucket in {@code [0, 1000)}; {@link #route} compares
 * that bucket against the {@code traffic_pct} threshold to assign a {@link Role}.
 *
 * <p>Properties:
 *
 * <ul>
 *   <li>Deterministic — same input pair always returns the same bucket. Survives JVM restarts;
 *       Murmur3 is content-addressed, not seeded.
 *   <li>Uniform — Murmur3_32 distributes inputs evenly across 32 bits; {@code floorMod} into 1000
 *       buckets is uniform to within ~0.7 buckets at large N.
 *   <li>Model-salted — the {@code modelName} mixes into the hash so a game routed to challenger for
 *       {@code pitch_outcome_pre} is NOT auto-routed to challenger for {@code batted_ball}. Without
 *       this salt the two models' challenger cohorts would be identical, defeating the independence
 *       assumption every A/B-test variance estimate relies on.
 * </ul>
 *
 * <p>Choice of {@code murmur3_32_fixed}: deterministic-byte-order variant (the non-fixed one may
 * differ per JVM/Guava version per the Guava docs). 32-bit is enough — 4B distinct keys before
 * birthday-paradox collisions become likely, and we have ~2K games per MLB season.
 *
 * <p>Edge cases (mirrors the leaf body):
 *
 * <ul>
 *   <li>{@code gameId == 0}: legal Murmur3 input, returns a stable bucket. The "no game id" request
 *       path uses {@link #routeForSession(UUID, String, RoutingConfig)} instead so gameId=0 isn't a
 *       special sentinel.
 *   <li>{@code traffic_pct == 0}: threshold = 0, every request goes to CHAMPION.
 *   <li>{@code traffic_pct == 100}: threshold = 1000, every A/B request goes to CHALLENGER.
 *   <li>Fractional pct (e.g. 33.33): {@code Math.round(pct * 10) = 333}; ±1 bucket of rounding
 *       error is acceptable at 1000-bucket granularity.
 * </ul>
 */
@Component
public class Bucketer {

  /** Bucket-space size — keep at 1000 so {@code traffic_pct} of 0.1 increment is representable. */
  public static final int BUCKET_COUNT = 1000;

  private static final HashFunction HASH = Hashing.murmur3_32_fixed();

  /**
   * Bucket for a game-id-keyed request. Returns a value in {@code [0, 1000)}; same input pair
   * always returns the same bucket.
   */
  public int bucket(long gameId, String modelName) {
    int raw =
        HASH.newHasher()
            .putLong(gameId)
            .putString(modelName, StandardCharsets.UTF_8)
            .hash()
            .asInt();
    return Math.floorMod(raw, BUCKET_COUNT);
  }

  /**
   * Bucket for a non-game (session-keyed) request — Park Explorer's hypothetical predictions and
   * any future "predict for this user's what-if" path. Uses the UUID's least-significant 64 bits as
   * the long input so the bucket distribution stays Murmur3-uniform.
   */
  public int bucketForSession(UUID sessionId, String modelName) {
    // UUID has 128 bits; combining both halves via Hasher.putLong twice keeps the full entropy.
    int raw =
        HASH.newHasher()
            .putLong(sessionId.getMostSignificantBits())
            .putLong(sessionId.getLeastSignificantBits())
            .putString(modelName, StandardCharsets.UTF_8)
            .hash()
            .asInt();
    return Math.floorMod(raw, BUCKET_COUNT);
  }

  /**
   * Assign a {@link Role} to a game-keyed request. {@link RoutingMode#SHADOW} always returns {@link
   * Role#CHAMPION} for the user-facing prediction (the challenger runs alongside but its prediction
   * is logged-only — that dispatch lives in 3b.3, not here). {@link RoutingMode#AB} compares the
   * bucket against {@code traffic_pct * 10} (pct → bucket count out of 1000).
   *
   * <p>Returns {@link Role#CHAMPION} unconditionally if {@link RoutingConfig#hasChallenger()} is
   * false — no challenger means no comparison; even in AB mode we have nothing to route TO.
   */
  public Role route(long gameId, String modelName, RoutingConfig cfg) {
    if (!cfg.hasChallenger()) {
      return Role.CHAMPION;
    }
    if (cfg.mode() == RoutingMode.SHADOW) {
      return Role.CHAMPION;
    }
    int b = bucket(gameId, modelName);
    return b < trafficThreshold(cfg.challengerTrafficPct()) ? Role.CHALLENGER : Role.CHAMPION;
  }

  /** Session-keyed variant of {@link #route} for the no-game-id request path. */
  public Role routeForSession(UUID sessionId, String modelName, RoutingConfig cfg) {
    if (!cfg.hasChallenger()) {
      return Role.CHAMPION;
    }
    if (cfg.mode() == RoutingMode.SHADOW) {
      return Role.CHAMPION;
    }
    int b = bucketForSession(sessionId, modelName);
    return b < trafficThreshold(cfg.challengerTrafficPct()) ? Role.CHALLENGER : Role.CHAMPION;
  }

  /**
   * Translate {@code traffic_pct} (a {@code [0, 100]} percentage) into a bucket-count threshold (a
   * {@code [0, 1000]} integer). Pct of 50.0 → 500; pct of 33.33 → 333; pct of 0 → 0; pct of 100 →
   * 1000. Bucket index {@code b} routes to challenger iff {@code b < threshold}.
   */
  static int trafficThreshold(double pct) {
    if (pct <= 0.0) {
      return 0;
    }
    if (pct >= 100.0) {
      return BUCKET_COUNT;
    }
    return (int) Math.round(pct * (BUCKET_COUNT / 100.0));
  }
}
