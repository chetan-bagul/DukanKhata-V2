package com.dukankhata.auth;

import com.dukankhata.shop.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JwtRevocationServiceTest {
    @Test
    void revocationKeyIsHashedAndExpiresNoLaterThanTheToken() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        JwtService jwtService = new JwtService("jwt-revocation-test-secret-with-32-bytes", 60_000);
        User user = mock(User.class);
        Shop shop = mock(Shop.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(user.getShop()).thenReturn(shop);
        when(shop.getId()).thenReturn(UUID.randomUUID());
        when(user.getRole()).thenReturn("OWNER");
        String token = jwtService.generateToken(user);

        new JwtRevocationService(redis, jwtService).revoke(token);

        var key = org.mockito.ArgumentCaptor.forClass(String.class);
        var ttl = org.mockito.ArgumentCaptor.forClass(Duration.class);
        verify(values).set(key.capture(), eq("1"), ttl.capture());
        assertTrue(key.getValue().startsWith("dk:auth:revoked:"));
        assertTrue(!key.getValue().contains(token));
        assertTrue(!ttl.getValue().isNegative() && ttl.getValue().compareTo(Duration.ofMinutes(1)) <= 0);
    }
}
