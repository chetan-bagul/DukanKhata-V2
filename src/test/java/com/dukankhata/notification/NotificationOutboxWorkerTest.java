package com.dukankhata.notification;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationOutboxWorkerTest {
    private static final UUID OUTBOX_ID = UUID.randomUUID();

    @Test
    void recoversProcessingLeaseAfterRestartAndCompletesDelivery() {
        Fixture fixture = new Fixture();
        fixture.item.setStatus(NotificationOutboxStatus.PROCESSING);
        fixture.item.setLockedAt(Instant.now().minusSeconds(600));
        fixture.item.setAttempts(1);
        fixture.worker.process();

        assertEquals(NotificationOutboxStatus.COMPLETED, fixture.item.getStatus());
        assertEquals(NotificationStatus.SENT, fixture.notification.getStatus());
        assertEquals(2, fixture.item.getAttempts());
        verify(fixture.provider).send(fixture.notification, "stable-event-key");
        assertEquals(1.0, fixture.meters.counter("dukankhata.notification.outbox.lease_recovered").count());
    }

    @Test
    void doesNotRedeliverCompletedJobAndDoesNotPersistProviderErrorDetails() {
        Fixture fixture = new Fixture();
        when(fixture.provider.send(any(), anyString()))
                .thenThrow(new IllegalStateException("secret=do-not-store"))
                .thenReturn(new NotificationProvider.DeliveryResult(true, "provider-id", null));
        fixture.worker.process();
        assertEquals("Provider delivery failed", fixture.item.getLastError());
        assertFalse(fixture.item.getLastError().contains("do-not-store"));

        fixture.item.setNextAttemptAt(Instant.now().minusSeconds(1));
        fixture.worker.process();
        fixture.worker.process();
        assertEquals(NotificationOutboxStatus.COMPLETED, fixture.item.getStatus());
        verify(fixture.provider, times(2)).send(any(), anyString());
    }

    @Test
    void providerFailuresUseBoundedAttemptsAndReachDeadLetter() {
        Fixture fixture = new Fixture();
        when(fixture.provider.send(any(), anyString())).thenReturn(
                new NotificationProvider.DeliveryResult(false, null, "sensitive provider response"));
        for (int attempt = 1; attempt <= 5; attempt++) {
            fixture.item.setNextAttemptAt(Instant.now().minusSeconds(1));
            fixture.worker.process();
        }

        assertEquals(5, fixture.item.getAttempts());
        assertEquals(NotificationOutboxStatus.DEAD_LETTER, fixture.item.getStatus());
        assertEquals(NotificationStatus.DEAD_LETTER, fixture.notification.getStatus());
        assertEquals("Provider delivery failed", fixture.notification.getLastError());
        verify(fixture.provider, times(5)).send(any(), anyString());
    }

    @Test
    void twoWorkersDoNotClaimSameActiveLeaseAtTheSameTime() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger concurrentCalls = new AtomicInteger();
        AtomicInteger maximumConcurrentCalls = new AtomicInteger();
        when(fixture.provider.send(any(), anyString())).thenAnswer(invocation -> {
            int active = concurrentCalls.incrementAndGet();
            maximumConcurrentCalls.accumulateAndGet(active, Math::max);
            providerEntered.countDown();
            assertTrue(releaseProvider.await(5, TimeUnit.SECONDS));
            concurrentCalls.decrementAndGet();
            return new NotificationProvider.DeliveryResult(true, "provider-id", null);
        });

        Thread firstWorker = new Thread(fixture.worker::process);
        firstWorker.start();
        assertTrue(providerEntered.await(5, TimeUnit.SECONDS));
        fixture.worker.process();
        releaseProvider.countDown();
        firstWorker.join(5000);

        assertFalse(firstWorker.isAlive());
        assertEquals(1, maximumConcurrentCalls.get());
        assertEquals(1, fixture.item.getAttempts());
        verify(fixture.provider, times(1)).send(any(), anyString());
    }

    private static final class Fixture {
        final NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
        final NotificationRepository notifications = mock(NotificationRepository.class);
        final NotificationProvider provider = mock(NotificationProvider.class);
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final Notification notification = new Notification();
        final NotificationOutbox item = new NotificationOutbox();
        final NotificationOutboxWorker worker;

        Fixture() {
            item.setEventKey("stable-event-key");
            item.setNotification(notification);
            item.setStatus(NotificationOutboxStatus.PENDING);
            item.setAttempts(0);
            item.setNextAttemptAt(Instant.now().minusSeconds(1));
            ReflectionTestUtils.setField(item, "id", OUTBOX_ID);
            when(outbox.countByStatus(any())).thenReturn(0L);
            when(outbox.findWithNotificationById(OUTBOX_ID)).thenReturn(Optional.of(item));
            when(outbox.lockClaimable(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                synchronized (item) {
                    Instant now = invocation.getArgument(2);
                    Instant staleBefore = invocation.getArgument(3);
                    boolean pendingReady = item.getStatus() == NotificationOutboxStatus.PENDING
                            && !item.getNextAttemptAt().isAfter(now);
                    boolean staleProcessing = item.getStatus() == NotificationOutboxStatus.PROCESSING
                            && item.getLockedAt() != null && item.getLockedAt().isBefore(staleBefore);
                    return pendingReady || staleProcessing ? List.of(item) : List.of();
                }
            });
            TransactionTemplate transactionTemplate = new TransactionTemplate() {
                @Override
                public <T> T execute(TransactionCallback<T> action) {
                    synchronized (item) {
                        return action.doInTransaction(new SimpleTransactionStatus());
                    }
                }

                @Override
                public void executeWithoutResult(java.util.function.Consumer<TransactionStatus> action) {
                    synchronized (item) {
                        action.accept(new SimpleTransactionStatus());
                    }
                }
            };
            when(provider.send(any(), anyString())).thenReturn(
                    new NotificationProvider.DeliveryResult(true, "provider-id", null));
            worker = new NotificationOutboxWorker(outbox, notifications, provider, transactionTemplate, meters, 300_000, 5);
        }
    }
}
