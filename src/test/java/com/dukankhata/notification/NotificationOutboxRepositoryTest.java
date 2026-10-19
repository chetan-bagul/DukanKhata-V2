package com.dukankhata.notification;

import com.dukankhata.shop.Shop;
import com.dukankhata.shop.ShopRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-outbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class NotificationOutboxRepositoryTest {
    @Autowired NotificationOutboxRepository outbox;
    @Autowired NotificationRepository notifications;
    @Autowired ShopRepository shops;

    @Test
    void claimQueryIncludesDueAndStaleJobsButExcludesActiveLeases() {
        Instant now = Instant.now();
        NotificationOutbox due = save(NotificationOutboxStatus.PENDING, null, now.minusSeconds(1));
        NotificationOutbox stale = save(NotificationOutboxStatus.PROCESSING, now.minusSeconds(600), now);
        NotificationOutbox missingLease = save(NotificationOutboxStatus.PROCESSING, null, now);
        NotificationOutbox active = save(NotificationOutboxStatus.PROCESSING, now.minusSeconds(10), now);
        NotificationOutbox delayed = save(NotificationOutboxStatus.PENDING, null, now.plusSeconds(60));

        List<NotificationOutbox> claimable = outbox.lockClaimable(
                NotificationOutboxStatus.PENDING, NotificationOutboxStatus.PROCESSING,
                now, now.minusSeconds(300), PageRequest.of(0, 10));

        assertThat(claimable).extracting(NotificationOutbox::getId)
                .contains(due.getId(), stale.getId(), missingLease.getId())
                .doesNotContain(active.getId(), delayed.getId());
    }

    private NotificationOutbox save(NotificationOutboxStatus status, Instant lockedAt, Instant nextAttemptAt) {
        Shop shop = new Shop();
        shop.setName("Notification test shop");
        shop = shops.saveAndFlush(shop);

        Notification notification = new Notification();
        notification.setShop(shop);
        notification.setChannel(NotificationChannel.SMS);
        notification.setType(NotificationType.INVOICE);
        notification.setStatus(status == NotificationOutboxStatus.PROCESSING
                ? NotificationStatus.PROCESSING : NotificationStatus.QUEUED);
        notification.setRecipient("test-recipient");
        notification.setSubject("Test");
        notification.setMessage("No real delivery");
        notification = notifications.saveAndFlush(notification);

        NotificationOutbox item = new NotificationOutbox();
        item.setEventKey("event-" + java.util.UUID.randomUUID());
        item.setNotification(notification);
        item.setStatus(status);
        item.setAttempts(1);
        item.setLockedAt(lockedAt);
        item.setNextAttemptAt(nextAttemptAt);
        return outbox.saveAndFlush(item);
    }
}
