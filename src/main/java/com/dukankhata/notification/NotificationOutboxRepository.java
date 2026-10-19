package com.dukankhata.notification;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox,UUID> {
 Optional<NotificationOutbox> findByEventKey(String eventKey);
 @EntityGraph(attributePaths="notification")
 Optional<NotificationOutbox> findWithNotificationById(UUID id);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select o from NotificationOutbox o where (o.status = :pending and o.nextAttemptAt <= :now) or (o.status = :processing and (o.lockedAt is null or o.lockedAt < :staleBefore)) order by o.createdAt asc")
 List<NotificationOutbox> lockClaimable(@Param("pending") NotificationOutboxStatus pending,
                                        @Param("processing") NotificationOutboxStatus processing,
                                        @Param("now") Instant now,
                                        @Param("staleBefore") Instant staleBefore,
                                        Pageable pageable);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @EntityGraph(attributePaths="notification")
 @Query("select o from NotificationOutbox o where o.notification.id = :notificationId")
 Optional<NotificationOutbox> findByNotificationIdForUpdate(@Param("notificationId") UUID notificationId);
 long countByStatus(NotificationOutboxStatus status);
}
