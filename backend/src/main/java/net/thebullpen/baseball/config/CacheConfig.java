package net.thebullpen.baseball.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
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
 *       request, written when an admin flips a challenger or moves the traffic slider. 30s TTL +
 *       max 1024 entries (we have ~5 model_names, so the bound is theoretical, not real). The
 *       {@code expireAfterWrite} (not {@code expireAfterAccess}) discipline means a stale value is
 *       bounded to 30 seconds in the worst case — matches the leaf's "visible within 30s"
 *       acceptance criterion.
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
  public CacheManager cacheManager() {
    CaffeineCacheManager mgr = new CaffeineCacheManager(ROUTING_CACHE);
    mgr.setCaffeine(
        Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(1024)
            .recordStats());
    return mgr;
  }
}
