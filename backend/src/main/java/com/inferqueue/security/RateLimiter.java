package com.inferqueue.security;

import com.inferqueue.config.InferQueueProperties;
import com.inferqueue.domain.Tier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Rate limit por API key con ventana fija de un minuto: un contador en Redis
 * (INCR) con TTL. Es aproximado en los bordes de la ventana, pero cuesta una
 * sola operación y no necesita coordinación entre instancias del gateway.
 */
@Component
public class RateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final InferQueueProperties props;

    public RateLimiter(StringRedisTemplate redis, InferQueueProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public Decision check(UUID apiKeyId, Tier tier) {
        int limit = limitFor(tier);
        long windowStart = Instant.now().getEpochSecond() / WINDOW.toSeconds();
        String key = "ratelimit:%s:%d".formatted(apiKeyId, windowStart);

        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // Primer hit de la ventana: le ponemos TTL para que se limpie sola.
            redis.expire(key, WINDOW);
        }
        long used = count == null ? 1L : count;
        long remaining = Math.max(0, limit - used);
        return new Decision(used <= limit, limit, remaining, (windowStart + 1) * WINDOW.toSeconds());
    }

    private int limitFor(Tier tier) {
        return tier == Tier.PREMIUM
                ? props.ratelimit().premium().requestsPerMinute()
                : props.ratelimit().free().requestsPerMinute();
    }

    public record Decision(boolean allowed, int limit, long remaining, long resetEpochSeconds) {
    }
}
