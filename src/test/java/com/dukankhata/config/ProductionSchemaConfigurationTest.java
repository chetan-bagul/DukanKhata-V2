package com.dukankhata.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.env.YamlPropertySourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSchemaConfigurationTest {
    @Test
    void productionProfileDisablesHibernateDdlAndFlyway() throws Exception {
        var loader = new YamlPropertySourceLoader();
        var base = loader.load("application", new ClassPathResource("application.yml")).getFirst();
        var production = loader.load("application-prod", new ClassPathResource("application-prod.yml")).getFirst();
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove("systemProperties");
        environment.getPropertySources().remove("systemEnvironment");
        environment.getPropertySources().addFirst(production);
        environment.getPropertySources().addLast(base);

        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class)).isFalse();
        assertThat(production.getProperty("spring.datasource.url")).isEqualTo("${DB_URL}");
    }
}
