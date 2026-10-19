package com.dukankhata.subscription;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
public class SubscriptionDataInitializer {
    @Bean
    CommandLineRunner seedPlans(
            SubscriptionPlanRepository repository,
            @Value("${subscription.plans.small-monthly:150}") BigDecimal smallMonthly,
            @Value("${subscription.plans.small-yearly:1500}") BigDecimal smallYearly,
            @Value("${subscription.plans.large-monthly:299}") BigDecimal largeMonthly,
            @Value("${subscription.plans.large-yearly:2999}") BigDecimal largeYearly) {
        return args -> {
            seedIfMissing(repository, "SMALL_MONTHLY", "Small Shop Monthly", smallMonthly, 30);
            seedIfMissing(repository, "SMALL_YEARLY", "Small Shop Yearly", smallYearly, 365);
            seedIfMissing(repository, "LARGE_MONTHLY", "Large Shop Monthly", largeMonthly, 30);
            seedIfMissing(repository, "LARGE_YEARLY", "Large Shop Yearly", largeYearly, 365);
        };
    }

    /**
     * Seeds only absent plan codes. Existing plans may have been deliberately
     * priced, renamed, or deactivated by an operator; startup must not silently
     * overwrite those decisions from environment configuration.
     */
    private void seedIfMissing(SubscriptionPlanRepository repository, String code, String name,
                               BigDecimal amount, int durationDays) {
        if (repository.findByCode(code).isPresent()) {
            return;
        }

        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setCode(code);
        plan.setName(name);
        plan.setAmount(amount);
        plan.setCurrency("INR");
        plan.setDurationDays(durationDays);
        plan.setActive(true);
        try {
            repository.saveAndFlush(plan);
        } catch (DataIntegrityViolationException concurrentInsert) {
            // Multiple instances can start simultaneously. A unique-code race is
            // harmless only if another instance successfully created this code.
            if (repository.findByCode(code).isEmpty()) {
                throw concurrentInsert;
            }
        }
    }
}
