package com.dukankhata.auth;

import io.jsonwebtoken.Claims;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class JwtRevocationService {
    private static final String KEY_PREFIX = "dk:auth:revoked:";

    private final StringRedisTemplate redis;
    private final JwtService jwtService;

    public JwtRevocationService(StringRedisTemplate redis, JwtService jwtService) {
        this.redis = redis;
        this.jwtService = jwtService;
    }

    public void revoke(String token) {
        try {
            Claims claims = jwtService.parse(token);
            Instant expiresAt = claims.getExpiration().toInstant();
            Duration remaining = Duration.between(Instant.now(), expiresAt);
            if (!remaining.isNegative() && !remaining.isZero()) {
                redis.opsForValue().set(key(token), "1", remaining);
            }
        } catch (RuntimeException ex) {
            throw new SessionRevocationUnavailableException();
        }
    }

    public boolean isRevoked(String token) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(token)));
        } catch (RuntimeException ex) {
            throw new SessionRevocationUnavailableException();
        }
    }

    private String key(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
