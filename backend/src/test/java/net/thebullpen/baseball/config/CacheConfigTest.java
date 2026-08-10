package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;

/**
 * Pins the routing cache wiring through the D-38 {@code bullpen.cache.routing-ttl-seconds} param:
 * the {@code cacheManager} bean still exposes the {@code routing} cache regardless of the injected
 * TTL, so {@code @Cacheable(ROUTING_CACHE)} keeps resolving and D-39's two-instance IT can drive
 * the TTL low. (Caffeine's expireAfterWrite value is internal to the cache, so this asserts the
 * wiring, not the numeric TTL - the TTL behavior is exercised by D-39.)
 */
class CacheConfigTest {

  @Test
  void cacheManagerExposesTheRoutingCacheAtAnyTtl() {
    CacheManager mgr = new CacheConfig().cacheManager(5);
    assertThat(mgr.getCacheNames()).contains(CacheConfig.ROUTING_CACHE);
    assertThat(mgr.getCache(CacheConfig.ROUTING_CACHE)).isNotNull();
  }
}
