package com.dukankhata.common.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class TokenBucketRateLimiterTest {
    private StringRedisTemplate redis;
    private SimpleMeterRegistry meters;
    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        meters = new SimpleMeterRegistry();
        limiter = new TokenBucketRateLimiter(redis, meters);
    }

    @AfterEach
    void tearDown() {
        meters.close();
    }

    @Test
    void deniesLoginWhenRedisTimesOut() {
        doThrow(new RedisSystemException("Redis command timed out", new TimeoutException()))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThrows(RateLimitUnavailableException.class, this::tryConsume);
        assertEquals(1, meters.counter("login.rate_limit.redis.failures").count());
        assertEquals(1, meters.get("login.rate_limit.redis.degraded").gauge().value());
    }

    @Test
    void deniesLoginWhenRedisConnectionFails() {
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThrows(RateLimitUnavailableException.class, this::tryConsume);
        assertEquals(1, meters.counter("login.rate_limit.redis.failures").count());
        assertEquals(1, meters.get("login.rate_limit.redis.degraded").gauge().value());
    }

    @Test
    void recoversAfterRedisBecomesAvailable() {
        doThrow(new RedisConnectionFailureException("connection refused"))
                .doReturn(List.of(1, 4, 4, 0))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThrows(RateLimitUnavailableException.class, this::tryConsume);
        TokenBucketRateLimiter.Result recovered = tryConsume();

        assertTrue(recovered.allowed());
        assertEquals(1, meters.counter("login.rate_limit.redis.failures").count());
        assertEquals(1, meters.counter("login.rate_limit.redis.recoveries").count());
        assertEquals(0, meters.get("login.rate_limit.redis.degraded").gauge().value());
    }

    @Test
    void returnsRedisDecisionWhenAvailable() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(0, 0, 4, 12));

        TokenBucketRateLimiter.Result result = tryConsume();

        assertFalse(result.allowed());
        assertEquals(12, result.retryAfterSeconds());
        assertEquals(0, meters.counter("login.rate_limit.redis.failures").count());
        assertEquals(0, meters.get("login.rate_limit.redis.degraded").gauge().value());
    }

    private TokenBucketRateLimiter.Result tryConsume() {
        return limiter.tryConsume("login:mobile:test", "login:ip:test", 5,
                Duration.ofSeconds(12), Duration.ofMinutes(2));
    }
}
