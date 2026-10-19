package com.dukankhata.health;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("mappedSchema")
public class MappedSchemaHealthIndicator implements HealthIndicator {
    private final EntityManagerFactory entityManagerFactory;

    public MappedSchemaHealthIndicator(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public Health health() {
        try {
            validateMappedSchema();
            return Health.up().build();
        } catch (RuntimeException exception) {
            return Health.down().build();
        }
    }

    public boolean isMappedSchemaAvailable() {
        return health().getStatus().getCode().equals("UP");
    }

    private void validateMappedSchema() {
        entityManagerFactory.unwrap(SessionFactory.class)
                .getSchemaManager()
                .validateMappedObjects();
    }
}
