package com.dukankhata.common.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class TokenBucketRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);
    private static final String SCRIPT = """
        local now = tonumber(ARGV[3])
        local capacity = tonumber(ARGV[1])
        local interval = tonumber(ARGV[2])
        local ttl = tonumber(ARGV[4])
        local tokens = {}
        local lasts = {}

        for i, key in ipairs(KEYS) do
            local t = tonumber(redis.call('HGET', key, 'tokens'))
            local last = tonumber(redis.call('HGET', key, 'last_refill_ms'))
            if not t then t = capacity end
            if not last then last = now end

            if now > last then
                local added = math.floor((now - last) / interval)
                if added > 0 then
                    t = math.min(capacity, t + added)
                    last = last + (added * interval)
                end
            end

            tokens[i] = t
            lasts[i] = last
        end

        local allowed = true
        for i = 1, #tokens do
            if tokens[i] < 1 then allowed = false end
        end

        local retry = 0
        if not allowed then
            local minWait = 9223372036854775807
            for i = 1, #tokens do
                if tokens[i] < 1 then
                    local wait = math.max(0, (lasts[i] + interval) - now)
                    if wait < minWait then minWait = wait end
                end
            end
            retry = math.max(1, math.ceil(minWait / 1000))
        end

        for i, key in ipairs(KEYS) do
            local t = tokens[i]
            if allowed then t = t - 1 end
            redis.call('HSET', key, 'tokens', t, 'last_refill_ms', lasts[i])
            redis.call('PEXPIRE', key, ttl)
            tokens[i] = t
        end

        return {allowed and 1 or 0, tokens[1], tokens[2], retry}
        """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>(SCRIPT, List.class);
    private final Counter failureCounter;
    private final Counter recoveryCounter;
    private final AtomicBoolean degraded = new AtomicBoolean();

    public TokenBucketRateLimiter(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.failureCounter = Counter.builder("login.rate_limit.redis.failures")
                .description("Redis errors while checking login rate limits")
                .register(meterRegistry);
        this.recoveryCounter = Counter.builder("login.rate_limit.redis.recoveries")
                .description("Redis recoveries after login rate limiting was unavailable")
                .register(meterRegistry);
        Gauge.builder("login.rate_limit.redis.degraded", degraded, state -> state.get() ? 1 : 0)
                .description("Whether Redis-backed login rate limiting is unavailable")
                .register(meterRegistry);
    }

    public Result tryConsume(String mobileKey, String ipKey, int capacity, Duration refillInterval, Duration stateTtl) {
        try {
        long now = System.currentTimeMillis();
        List<?> result = redis.execute(
                script,
                List.of(mobileKey, ipKey),
                String.valueOf(capacity),
                String.valueOf(refillInterval.toMillis()),
                String.valueOf(now),
                String.valueOf(stateTtl.toMillis())
        );

        if (result == null || result.size() < 4) {
            throw new IllegalStateException("Rate limiter did not return a valid result");
        }

        boolean allowed = Long.parseLong(String.valueOf(result.get(0))) == 1L;
        long retry = Long.parseLong(String.valueOf(result.get(3)));
        if (degraded.getAndSet(false)) {
            recoveryCounter.increment();
            log.info("Login rate limiter Redis connection recovered");
        }
        return new Result(allowed, retry);
        } catch (Exception ex) {
            failureCounter.increment();
            if (!degraded.getAndSet(true)) {
                log.error("Login rate limiter Redis operation failed; denying login attempts (causeType={})",
                        ex.getClass().getSimpleName());
            }
            throw new RateLimitUnavailableException();
        }
    }

    public record Result(boolean allowed, long retryAfterSeconds) {}
}
