package com.dukankhata.common.ratelimit;

public class RateLimitUnavailableException extends RuntimeException {
    public RateLimitUnavailableException() {
        super("Login is temporarily unavailable. Please try again shortly.");
    }
}
