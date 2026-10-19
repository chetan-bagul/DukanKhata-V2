package com.dukankhata.subscription;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:subscription-initializer-empty;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
class SubscriptionInitializerMissingSchemaTest {
    @Autowired SubscriptionPlanRepository plans;

    @Test
    void emptyDatabaseFailsAtSubscriptionPlanLookupWithoutCreatingSchema() throws Exception {
        var planInitializer = new SubscriptionDataInitializer().seedPlans(plans, new BigDecimal("150"),
                new BigDecimal("1500"), new BigDecimal("199"), new BigDecimal("1990"));
        assertNotNull(planInitializer);

        assertThatThrownBy(() -> planInitializer.run())
                .isInstanceOf(DataAccessException.class);
    }
}
