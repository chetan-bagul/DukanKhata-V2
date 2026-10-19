package com.dukankhata.notification;

import com.dukankhata.auth.User;
import com.dukankhata.common.security.CurrentUserService;
import com.dukankhata.shop.Shop;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationRetryTest {
    @Test
    void deadLetterRetryReusesExistingOutboxEventAndResetsAttempts() {
        UUID shopId = UUID.randomUUID();
        Shop shop = new Shop();
        org.springframework.test.util.ReflectionTestUtils.setField(shop, "id", shopId);
        User user = new User();
        user.setShop(shop);
        Notification notification = notification(shop, NotificationStatus.DEAD_LETTER, 5);
        NotificationOutbox item = new NotificationOutbox();
        item.setEventKey("original-provider-idempotency-key");
        item.setNotification(notification);
        item.setStatus(NotificationOutboxStatus.DEAD_LETTER);
        item.setAttempts(5);
        item.setLastError("Provider delivery failed");

        NotificationRepository notifications = mock(NotificationRepository.class);
        NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
        CurrentUserService current = mock(CurrentUserService.class);
        when(current.requireUser()).thenReturn(user);
        when(current.shopId()).thenReturn(shopId);
        when(notifications.findByIdAndShopId(any(), eq(shopId))).thenReturn(Optional.of(notification));
        when(outbox.findByNotificationIdForUpdate(any())).thenReturn(Optional.of(item));
        when(notifications.save(notification)).thenReturn(notification);
        NotificationService service = new NotificationService(notifications, current, outbox);

        service.retry(UUID.randomUUID());

        assertEquals("original-provider-idempotency-key", item.getEventKey());
        assertEquals(NotificationOutboxStatus.PENDING, item.getStatus());
        assertEquals(0, item.getAttempts());
        assertEquals(NotificationStatus.QUEUED, notification.getStatus());
        assertEquals(0, notification.getAttempts());
        verify(outbox).save(item);
    }

    @Test
    void retryRejectsJobsThatAreNotDeadLettered() {
        UUID shopId = UUID.randomUUID();
        Shop shop = new Shop();
        org.springframework.test.util.ReflectionTestUtils.setField(shop, "id", shopId);
        Notification notification = notification(shop, NotificationStatus.FAILED, 1);
        NotificationOutbox item = new NotificationOutbox();
        item.setNotification(notification);
        item.setStatus(NotificationOutboxStatus.PENDING);
        NotificationRepository notifications = mock(NotificationRepository.class);
        NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
        CurrentUserService current = mock(CurrentUserService.class);
        when(current.shopId()).thenReturn(shopId);
        when(notifications.findByIdAndShopId(any(), eq(shopId))).thenReturn(Optional.of(notification));
        when(outbox.findByNotificationIdForUpdate(any())).thenReturn(Optional.of(item));
        NotificationService service = new NotificationService(notifications, current, outbox);

        assertThrows(com.dukankhata.common.exception.BusinessException.class, () -> service.retry(UUID.randomUUID()));
        verify(outbox, never()).save(any());
    }

    private Notification notification(Shop shop, NotificationStatus status, int attempts) {
        Notification notification = new Notification();
        notification.setShop(shop);
        notification.setStatus(status);
        notification.setAttempts(attempts);
        notification.setChannel(NotificationChannel.SMS);
        notification.setType(NotificationType.INVOICE);
        notification.setRecipient("recipient");
        notification.setSubject("subject");
        notification.setMessage("message");
        return notification;
    }
}
