package com.dukankhata.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {
    Optional<SubscriptionPlan> findByCode(String code);

    Optional<SubscriptionPlan> findByCodeAndActiveTrue(String code);

    List<SubscriptionPlan> findAllByActiveTrueOrderByAmountAsc();
}
