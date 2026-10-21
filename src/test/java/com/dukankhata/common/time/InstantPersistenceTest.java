package com.dukankhata.common.time;

import com.dukankhata.shop.Shop;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class InstantPersistenceTest {
    @Autowired EntityManager entityManager;

    @Test
    void instantTimestampRoundTripsWithoutChangingTheUtcInstant() {
        Shop shop = new Shop();
        shop.setName("UTC persistence check");
        entityManager.persist(shop);
        entityManager.flush();
        var expected = shop.getCreatedAt().truncatedTo(ChronoUnit.MICROS);
        var id = shop.getId();

        entityManager.clear();

        var actual = entityManager.find(Shop.class, id).getCreatedAt();
        assertThat(Duration.between(expected, actual).abs())
                .isLessThanOrEqualTo(Duration.of(1, ChronoUnit.MICROS));
    }
}
