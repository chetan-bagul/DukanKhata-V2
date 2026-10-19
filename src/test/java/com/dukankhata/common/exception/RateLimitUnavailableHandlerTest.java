package com.dukankhata.common.exception;

import com.dukankhata.common.ratelimit.RateLimitUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitUnavailableHandlerTest {
    @Test
    void mapsRateLimiterOutageToServiceUnavailable() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        HttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");

        var response = handler.handleRateLimitUnavailable(new RateLimitUnavailableException(), request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("RATE_LIMIT_UNAVAILABLE", response.getBody().code());
    }
}
