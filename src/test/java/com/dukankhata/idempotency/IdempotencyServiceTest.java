package com.dukankhata.idempotency;

import com.dukankhata.auth.User;
import com.dukankhata.common.exception.BusinessException;
import com.dukankhata.common.security.CurrentUserService;
import com.dukankhata.shop.Shop;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest {
    private IdempotencyRepository records;
    private PostgresIdempotencyLockRepository locks;
    private CurrentUserService current;
    private IdempotencyService service;
    private UUID shopId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        records = mock(IdempotencyRepository.class);
        locks = mock(PostgresIdempotencyLockRepository.class);
        current = new CurrentUserService();
        service = new IdempotencyService(records, locks, current, new ObjectMapper());

        shopId = UUID.randomUUID();
        userId = UUID.randomUUID();
        Shop shop = Mockito.mock(Shop.class);
        when(shop.getId()).thenReturn(shopId);
        User user = new User();
        user.setShop(shop);
        setPrivateId(user, userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void executesActionOnlyOnceForSameKey() {
        AtomicInteger executions = new AtomicInteger();
        when(records.findByShopIdAndUserIdAndOperationAndIdempotencyKey(
                eq(shopId), eq(userId), eq("PAYMENT"), eq("payment-1")))
                .thenReturn(Optional.empty());

        String first = service.execute("PAYMENT", " payment-1 ", new PaymentRequest("5000"), String.class,
                () -> {
                    executions.incrementAndGet();
                    return "OK";
                });

        assertEquals("OK", first);
        assertEquals(1, executions.get());
        verify(records).save(any(IdempotencyRecord.class));
        verify(locks).lock(shopId + ":" + userId + ":PAYMENT:payment-1");
    }

    @Test
    void replaysExistingResponseWithoutExecutingAction() {
        IdempotencyRecord record = record("PAYMENT", "payment-1", "5000", "OK");
        when(records.findByShopIdAndUserIdAndOperationAndIdempotencyKey(
                eq(shopId), eq(userId), eq("PAYMENT"), eq("payment-1")))
                .thenReturn(Optional.of(record));

        AtomicInteger executions = new AtomicInteger();
        String result = service.execute("PAYMENT", "payment-1", new PaymentRequest("5000"), String.class,
                () -> {
                    executions.incrementAndGet();
                    return "SHOULD_NOT_RUN";
                });

        assertEquals("OK", result);
        assertEquals(0, executions.get());
        verify(records, never()).save(any());
    }

    @Test
    void rejectsSameKeyWithDifferentRequest() {
        IdempotencyRecord record = record("PAYMENT", "payment-1", "5000", "OK");
        when(records.findByShopIdAndUserIdAndOperationAndIdempotencyKey(
                eq(shopId), eq(userId), eq("PAYMENT"), eq("payment-1")))
                .thenReturn(Optional.of(record));

        assertThrows(BusinessException.class, () -> service.execute(
                "PAYMENT", "payment-1", new PaymentRequest("7000"), String.class, () -> "NO"));
    }

    @Test
    void rejectsMissingKey() {
        assertThrows(BusinessException.class, () -> service.execute(
                "PAYMENT", " ", new PaymentRequest("5000"), String.class, () -> "NO"));
    }

    private IdempotencyRecord record(String operation, String key, String amount, String response) {
        IdempotencyRecord r = new IdempotencyRecord();
        r.setShopId(shopId);
        r.setUserId(userId);
        r.setOperation(operation);
        r.setIdempotencyKey(key);
        try {
            r.setResponseJson(new ObjectMapper().writeValueAsString(response));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        try {
            String json = new ObjectMapper().writeValueAsString(new PaymentRequest(amount));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
            r.setRequestHash(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return r;
    }

    private void setPrivateId(User user, UUID id) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    record PaymentRequest(String amount) {}
}
