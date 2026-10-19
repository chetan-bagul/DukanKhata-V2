package com.dukankhata.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

public interface CustomerLedgerRepository extends JpaRepository<CustomerLedgerEntry, UUID> {
    List<CustomerLedgerEntry> findAllByCustomerIdAndShopIdOrderByCreatedAtDesc(UUID customerId, UUID shopId);

    @Query("select e.paymentMethod, coalesce(sum(e.amount), 0) from CustomerLedgerEntry e " +
            "where e.shopId = :shopId and e.type = com.dukankhata.customer.LedgerEntryType.PAYMENT " +
            "and e.createdAt >= :from and e.createdAt < :to and e.paymentMethod is not null " +
            "group by e.paymentMethod")
    List<Object[]> sumRepaymentsByShopAndCreatedAt(@Param("shopId") UUID shopId,
                                                   @Param("from") Instant from,
                                                   @Param("to") Instant to);
}
