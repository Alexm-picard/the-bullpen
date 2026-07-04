package net.thebullpen.baseball.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine-backed Spring caching for read-heavy reference data on the inference path. Currently one
 * cache:
 *
 * <ul>
 *   <li>{@code routing} — backs {@code RoutingService.getRouting(...)} (Phase 3b.1). Read on every
 *       request, written when an admin flips a challenger or moves the traffic slider. TTL is
 *       configurable via {@code bullpen.cache.routing-ttl-seconds} (default 30s) + max 1024 entries
 *       (we have ~5 model_names, so the bound is theoretical, not real). The {@code
 *       expireAfterWrite} (not {@code expireAfterAccess}) discipline means a stale value is bounded
 *       to the TTL in the worst case - matches the leaf's "visible within 30s" acceptance criterion
 *       at the default. A two-instance IT (D-39) drives the TTL to 2s to assert cross-instance
 *       routing convergence without a 30s sleep.
 * </ul>
 *
 * <p>{@code @EnableCaching} is the gate — without this annotation Spring's {@code @Cacheable} +
 * {@code @CacheEvict} are no-ops. Adding more caches later is one extra {@code
 * Caffeine.newBuilder()} registered against the same {@code CacheManager}; keep them named
 * distinctly so eviction targets the right one.
 */
@Configuration
@EnableCaching
public class CacheConfig {

  public static final String ROUTING_CACHE = "routing";

  @Bean
  public CacheManager cacheManager(
      @Value("${bullpen.cache.routing-ttl-seconds:30}") long routingTtlSeconds) {
    CaffeineCacheManager mgr = new CaffeineCacheManager(ROUTING_CACHE);
    mgr.setCaffeine(
        Caffeine.newBuilder()
            .expireAfterWrite(routingTtlSeconds, TimeUnit.SECONDS)
            .maximumSize(1024)
            .recordStats());
    return mgr;
  }
}
