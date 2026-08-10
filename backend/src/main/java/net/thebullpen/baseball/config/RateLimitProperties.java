package net.thebullpen.baseball.config;

import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated binding for the {@code bullpen.ratelimit.*} namespace (Wave E / M-task 26, slice
 * 2). Replaces the {@code @Value} injections {@link RateLimitFilter} declared inline, so the
 * per-route limits live in one place, carry their own defaults, and fail fast at startup on a
 * non-positive limit instead of silently throttling everything to zero. {@code readPerMinute} is
 * the abuse backstop for the public ClickHouse-backed reads ({@code /v1/ops/**}, {@code
 * /v1/games/**}, {@code /v1/matchups/**}, and the {@code /v1/players/**} non-search reads); it is
 * generous because the polled ones are edge-cacheable (the real defense is the Cache-Control +
 * Cloudflare layer), so this only blunts a cache-busting flood or the un-cached profile reads.
 *
 * <p>The per-minute limits are {@link Positive}: a value of 0 while {@code enabled} would reject
 * every request on that route, which is never intended (disable the limiter with {@code
 * enabled=false} instead). {@code trustedProxies} are CIDR/IP strings the filter turns into {@code
 * IpAddressMatcher}s (trimming each), matched against for the forwarded-header client-IP decision;
 * the loopback default matches the single-box + Cloudflare-in-front topology.
 */
@ConfigurationProperties("bullpen.ratelimit")
@Validated
public record RateLimitProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("60") @Positive int predictPerMinute,
    @DefaultValue("15") @Positive int simulatePerMinute,
    @DefaultValue("120") @Positive int searchPerMinute,
    @DefaultValue("20") @Positive int adminPerMinute,
    @DefaultValue("120") @Positive int readPerMinute,
    @DefaultValue({"127.0.0.0/8", "::1"}) List<String> trustedProxies) {}
