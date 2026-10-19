package com.dukankhata.notification;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class NotificationOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxWorker.class);
    private static final int BATCH_SIZE = 10;
    private static final int MICROSECOND_PRECISION = 6;

    private final NotificationOutboxRepository outbox;
    private final NotificationRepository notifications;
    private final NotificationProvider provider;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meters;
    private final long leaseMillis;
    private final int maxAttempts;

    public NotificationOutboxWorker(NotificationOutboxRepository outbox,
                                    NotificationRepository notifications,
                                    NotificationProvider provider,
                                    TransactionTemplate transactionTemplate,
                                    MeterRegistry meters,
                                    @Value("${app.notification-worker.lease-duration-ms:300000}") long leaseMillis,
                                    @Value("${app.notification-worker.max-attempts:5}") int maxAttempts) {
        if (leaseMillis <= 0 || maxAttempts <= 0) {
            throw new IllegalArgumentException("Notification worker lease and max attempts must be positive");
        }
        this.outbox = outbox;
        this.notifications = notifications;
        this.provider = provider;
        this.transactionTemplate = transactionTemplate;
        this.meters = meters;
        this.leaseMillis = leaseMillis;
        this.maxAttempts = maxAttempts;
        registerStatusGauges();
    }

    @Scheduled(fixedDelayString = "${app.notification-worker.fixed-delay-ms:5000}")
    public void process() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            Claim claim = claimOne();
            if (claim == null) return;
            deliver(claim);
        }
    }

    Claim claimOne() {
        Instant now = databasePrecision(Instant.now());
        Instant staleBefore = databasePrecision(now.minusMillis(leaseMillis));
        return transactionTemplate.execute(status -> {
            List<NotificationOutbox> rows = outbox.lockClaimable(
                    NotificationOutboxStatus.PENDING, NotificationOutboxStatus.PROCESSING,
                    now, staleBefore, PageRequest.of(0, 1));
            if (rows.isEmpty()) return null;
            NotificationOutbox item = rows.get(0);
            if (item.getAttempts() >= maxAttempts) {
                markDeadLetter(item, "Retry limit reached after an interrupted delivery");
                meters.counter("dukankhata.notification.outbox.exhausted").increment();
                return new Claim(item.getId(), item.getLockedAt(), false);
            }
            boolean recovered = item.getStatus() == NotificationOutboxStatus.PROCESSING;
            Instant leaseAt = now;
            item.setStatus(NotificationOutboxStatus.PROCESSING);
            item.setLockedAt(leaseAt);
            item.setAttempts(item.getAttempts() + 1);
            Notification notification = item.getNotification();
            notification.setStatus(NotificationStatus.PROCESSING);
            notification.setAttempts(item.getAttempts());
            notifications.save(notification);
            outbox.save(item);
            meters.counter("dukankhata.notification.outbox.claimed").increment();
            if (recovered) meters.counter("dukankhata.notification.outbox.lease_recovered").increment();
            return new Claim(item.getId(), leaseAt, true);
        });
    }

    private void deliver(Claim claim) {
        if (!claim.deliver()) return;
        NotificationOutbox item = outbox.findWithNotificationById(claim.id()).orElse(null);
        if (item == null || !Objects.equals(item.getLockedAt(), claim.lockedAt())) return;
        NotificationProvider.DeliveryResult result;
        try {
            result = provider.send(item.getNotification(), item.getEventKey());
        } catch (Exception exception) {
            log.warn("Notification provider call failed for outbox {} ({})", claim.id(), exception.getClass().getSimpleName());
            result = new NotificationProvider.DeliveryResult(false, null, "Provider delivery failed");
        }
        finish(claim, result);
    }

    void finish(Claim claim, NotificationProvider.DeliveryResult result) {
        transactionTemplate.executeWithoutResult(status -> {
            NotificationOutbox item = outbox.findWithNotificationById(claim.id()).orElse(null);
            if (item == null || item.getStatus() != NotificationOutboxStatus.PROCESSING
                    || !Objects.equals(item.getLockedAt(), claim.lockedAt())) return;
            Notification notification = item.getNotification();
            Instant now = databasePrecision(Instant.now());
            if (result.success()) {
                notification.setStatus(NotificationStatus.SENT);
                notification.setProviderMessageId(result.providerMessageId());
                notification.setLastError(null);
                notification.setSentAt(now);
                item.setStatus(NotificationOutboxStatus.COMPLETED);
                item.setCompletedAt(now);
                item.setLastError(null);
                meters.counter("dukankhata.notification.outbox.delivered").increment();
            } else {
                String safeError = "Provider delivery failed";
                item.setLastError(safeError);
                notification.setLastError(safeError);
                if (item.getAttempts() >= maxAttempts) {
                    markDeadLetter(item, safeError);
                    meters.counter("dukankhata.notification.outbox.exhausted").increment();
                } else {
                    item.setStatus(NotificationOutboxStatus.PENDING);
                    item.setLockedAt(null);
                    item.setNextAttemptAt(now.plus(backoff(item.getAttempts())));
                    notification.setStatus(NotificationStatus.FAILED);
                    meters.counter("dukankhata.notification.outbox.retry_scheduled").increment();
                }
            }
            notifications.save(notification);
            outbox.save(item);
        });
    }

    private void markDeadLetter(NotificationOutbox item, String safeError) {
        item.setStatus(NotificationOutboxStatus.DEAD_LETTER);
        item.setLockedAt(null);
        item.setLastError(safeError);
        item.getNotification().setStatus(NotificationStatus.DEAD_LETTER);
        item.getNotification().setLastError(safeError);
        notifications.save(item.getNotification());
        outbox.save(item);
    }

    private Duration backoff(int attempt) {
        return Duration.ofSeconds(Math.min(300L, 5L * (1L << Math.min(6, Math.max(0, attempt - 1)))));
    }

    private Instant databasePrecision(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private void registerStatusGauges() {
        for (NotificationOutboxStatus status : NotificationOutboxStatus.values()) {
            meters.gauge("dukankhata.notification.outbox.jobs", List.of(
                    io.micrometer.core.instrument.Tag.of("status", status.name())), outbox,
                    repository -> repository.countByStatus(status));
        }
    }

    record Claim(UUID id, Instant lockedAt, boolean deliver) {}
}
