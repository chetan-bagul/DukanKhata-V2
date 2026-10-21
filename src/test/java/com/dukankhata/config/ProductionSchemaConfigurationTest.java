package com.dukankhata.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSchemaConfigurationTest {
    @Test
    void applicationDoesNotCreateAnUnusedGeneratedInMemoryCredential() {
        var application = com.dukankhata.DukanKhataApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(application.exclude()).contains(UserDetailsServiceAutoConfiguration.class);
    }

    @Test
    void productionProfileValidatesWithProviderTlsConfiguration() throws Exception {
        var loader = new YamlPropertySourceLoader();
        var base = loader.load("application", new ClassPathResource("application.yml")).getFirst();
        var production = loader.load("application-prod", new ClassPathResource("application-prod.yml")).getFirst();
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove("systemProperties");
        environment.getPropertySources().remove("systemEnvironment");
        environment.getPropertySources().addFirst(new MapPropertySource("testConfig", java.util.Map.of(
                "DB_SSL_ROOT_CERT", "C:/test-only/supabase-root.crt")));
        environment.getPropertySources().addFirst(production);
        environment.getPropertySources().addLast(base);

        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.datasource.hikari.data-source-properties.sslmode")).isEqualTo("verify-full");
        assertThat(environment.getProperty("spring.datasource.hikari.data-source-properties.sslrootcert"))
                .isEqualTo("C:/test-only/supabase-root.crt");
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("3");
        assertThat(environment.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo("0");
        assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("10000");
        assertThat(environment.getProperty("spring.datasource.hikari.data-source-properties.connectTimeout")).isEqualTo("10");
        assertThat(environment.getProperty("spring.datasource.hikari.data-source-properties.socketTimeout")).isEqualTo("45");
        assertThat(environment.getProperty("spring.datasource.hikari.data-source-properties.options"))
                .isEqualTo("-c statement_timeout=30000");
        assertThat(environment.getProperty("spring.data.redis.ssl.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.data.redis.connect-timeout")).isEqualTo("2s");
        assertThat(environment.getProperty("spring.data.redis.timeout")).isEqualTo("2s");
        assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db,redis,mappedSchema");
        assertThat(production.getProperty("spring.datasource.url")).isEqualTo("${DB_URL}");
        assertThat(production.getProperty("spring.datasource.username")).isEqualTo("${DB_USERNAME}");
        assertThat(production.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
    }

    @Test
    void productionCaCertificateIsRequiredRatherThanSilentlyOmitted() throws Exception {
        var production = new YamlPropertySourceLoader()
                .load("application-prod", new ClassPathResource("application-prod.yml")).getFirst();
        var resolver = new PropertySourcesPropertyResolver(new MutablePropertySources());

        assertThat(production.getProperty("spring.datasource.hikari.data-source-properties.sslrootcert"))
                .isEqualTo("${DB_SSL_ROOT_CERT}");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.resolveRequiredPlaceholders("${DB_SSL_ROOT_CERT}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productionRequiresExternalDatabaseRedisAndApplicationConfiguration() throws Exception {
        var production = new YamlPropertySourceLoader()
                .load("application-prod", new ClassPathResource("application-prod.yml")).getFirst();

        assertThat(production.getProperty("spring.datasource.url")).isEqualTo("${DB_URL}");
        assertThat(production.getProperty("spring.datasource.username")).isEqualTo("${DB_USERNAME}");
        assertThat(production.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
        assertThat(production.getProperty("spring.data.redis.url")).isEqualTo("${REDIS_URL}");
        assertThat(production.getProperty("security.jwt.secret")).isEqualTo("${JWT_SECRET}");
        assertThat(production.getProperty("app.cors.allowed-origins")).isEqualTo("${CORS_ALLOWED_ORIGINS}");

        var resolver = new PropertySourcesPropertyResolver(new MutablePropertySources());
        for (String required : java.util.List.of("${DB_URL}", "${DB_USERNAME}", "${DB_PASSWORD}",
                "${DB_SSL_ROOT_CERT}", "${REDIS_URL}", "${JWT_SECRET}", "${CORS_ALLOWED_ORIGINS}",
                "${RAZORPAY_KEY_ID}", "${RAZORPAY_KEY_SECRET}", "${RAZORPAY_WEBHOOK_SECRET}")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.resolveRequiredPlaceholders(required))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void flywayIsNotPresentOnTheApplicationRuntimeClasspath() {
        assertThat(ClassUtils.isPresent("org.flywaydb.core.Flyway", getClass().getClassLoader())).isFalse();
    }

    @Test
    void baseProfileValidatesInsteadOfCreatingSchema() throws Exception {
        var base = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).getFirst();

        var resolver = new PropertySourcesPropertyResolver(new MutablePropertySources());
        assertThat(resolver.resolveRequiredPlaceholders(
                base.getProperty("spring.jpa.hibernate.ddl-auto").toString()))
                .isEqualTo("validate");
    }
}
