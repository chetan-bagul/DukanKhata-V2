package com.dukankhata.subscription;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:subscription-initializer-existing;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class SubscriptionInitializerExistingSchemaTest {
    @Autowired SubscriptionPlanRepository plans;

    @Test
    void compatiblePlanTableIsSeededAndSubsequentRunsKeepOneRowPerCode() throws Exception {
        var initializer = new SubscriptionDataInitializer();
        var planInitializer = initializer.seedPlans(plans, new BigDecimal("150"), new BigDecimal("1500"),
                new BigDecimal("199"), new BigDecimal("1990"));
        assertNotNull(planInitializer);
        planInitializer.run();
        planInitializer.run();

        assertThat(plans.findAll()).hasSize(4);
        assertThat(plans.findByCodeAndActiveTrue("SMALL_MONTHLY")).isPresent();
        assertThat(plans.findByCodeAndActiveTrue("LARGE_YEARLY")).isPresent();
    }

    @Test
    void startupDoesNotOverwriteExistingPlanPricingOrMetadata() throws Exception {
        SubscriptionPlan existing = new SubscriptionPlan();
        existing.setCode("SMALL_MONTHLY");
        existing.setName("Operator-approved name");
        existing.setAmount(new BigDecimal("999.00"));
        existing.setCurrency("INR");
        existing.setDurationDays(45);
        existing.setActive(true);
        plans.saveAndFlush(existing);

        new SubscriptionDataInitializer().seedPlans(plans, new BigDecimal("150"), new BigDecimal("1500"),
                new BigDecimal("199"), new BigDecimal("1990")).run();

        SubscriptionPlan unchanged = plans.findByCodeAndActiveTrue("SMALL_MONTHLY").orElseThrow();
        assertThat(unchanged.getId()).isEqualTo(existing.getId());
        assertThat(unchanged.getName()).isEqualTo("Operator-approved name");
        assertThat(unchanged.getAmount()).isEqualByComparingTo("999.00");
        assertThat(unchanged.getDurationDays()).isEqualTo(45);
    }

    @Test
    void startupDoesNotReactivateAnIntentionallyInactivePlan() throws Exception {
        SubscriptionPlan existing = new SubscriptionPlan();
        existing.setCode("SMALL_MONTHLY");
        existing.setName("Retired plan");
        existing.setAmount(new BigDecimal("999.00"));
        existing.setCurrency("INR");
        existing.setDurationDays(30);
        existing.setActive(false);
        plans.saveAndFlush(existing);

        new SubscriptionDataInitializer().seedPlans(plans, new BigDecimal("150"), new BigDecimal("1500"),
                new BigDecimal("199"), new BigDecimal("1990")).run();

        SubscriptionPlan unchanged = plans.findByCode("SMALL_MONTHLY").orElseThrow();
        assertThat(unchanged.getId()).isEqualTo(existing.getId());
        assertThat(unchanged.isActive()).isFalse();
        assertThat(unchanged.getAmount()).isEqualByComparingTo("999.00");
    }
}
