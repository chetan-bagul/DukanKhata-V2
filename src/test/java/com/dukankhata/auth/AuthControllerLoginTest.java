package com.dukankhata.auth;

import com.dukankhata.auth.dto.AuthResponse;
import com.dukankhata.auth.dto.LoginRequest;
import com.dukankhata.common.ratelimit.LoginRateLimitService;
import com.dukankhata.common.ratelimit.RateLimitUnavailableException;
import com.dukankhata.common.ratelimit.TokenBucketRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class AuthControllerLoginTest {
    @Test
    void successfulRateLimitCheckPreservesNormalLoginFlow() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1, 4, 4, 0));
        AuthService authService = mock(AuthService.class);
        LoginRequest request = new LoginRequest("9876543210", "correct-password");
        AuthResponse expected = new AuthResponse("token", "Bearer", UUID.randomUUID(), UUID.randomUUID(),
                "Owner", request.mobile(), "OWNER", "Shop", "SMALL", "TRIAL");
        when(authService.login(request)).thenReturn(expected);
        var limiter = new TokenBucketRateLimiter(redis, new SimpleMeterRegistry());
        var loginRateLimit = new LoginRateLimitService(limiter, 5, 12_000, 120_000);
        var controller = new AuthController(authService, loginRateLimit, mock(JwtRevocationService.class));
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("192.0.2.10");

        var response = controller.login(request, httpRequest);

        assertTrue(response.success());
        assertSame(expected, response.data());
        verify(authService).login(request);
    }

    @Test
    void redisFailureStopsBeforeAuthentication() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        AuthService authService = mock(AuthService.class);
        var limiter = new TokenBucketRateLimiter(redis, new SimpleMeterRegistry());
        var loginRateLimit = new LoginRateLimitService(limiter, 5, 12_000, 120_000);
        var controller = new AuthController(authService, loginRateLimit, mock(JwtRevocationService.class));

        assertThrows(RateLimitUnavailableException.class,
                () -> controller.login(new LoginRequest("9876543210", "password"), new MockHttpServletRequest()));
        verifyNoInteractions(authService);
    }
}
