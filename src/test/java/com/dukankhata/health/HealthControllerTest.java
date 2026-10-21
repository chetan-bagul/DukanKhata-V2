package com.dukankhata.health;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RedisConnectionFactory redisConnectionFactory = mock(RedisConnectionFactory.class);
    private final MappedSchemaHealthIndicator schemaHealth = mock(MappedSchemaHealthIndicator.class);
    private final HealthController controller = new HealthController(jdbcTemplate, redisConnectionFactory, schemaHealth);

    @Test
    void readinessIsUnavailableWhenDatabaseFailsWithoutExposingDetails() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new IllegalStateException("internal host and credentials"));

        var response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "DOWN").containsEntry("dependency", "database");
        assertThat(response.getBody().toString()).doesNotContain("internal host", "credentials");
    }

    @Test
    void readinessIsUnavailableWhenRedisFails() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(redisConnectionFactory.getConnection()).thenThrow(new IllegalStateException("redis internal detail"));

        var response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "DOWN").containsEntry("dependency", "redis");
        assertThat(response.getBody().toString()).doesNotContain("redis internal detail");
    }

    @Test
    void readinessIsUnavailableWhenMappedSchemaIsInvalid() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        RedisConnection connection = mock(RedisConnection.class);
        when(redisConnectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        when(schemaHealth.isMappedSchemaAvailable()).thenReturn(false);

        var response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "DOWN").containsEntry("dependency", "schema");
    }

    @Test
    void readinessReturnsUpOnlyWhenAllDependenciesAreHealthy() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        RedisConnection connection = mock(RedisConnection.class);
        when(redisConnectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        when(schemaHealth.isMappedSchemaAvailable()).thenReturn(true);

        var response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }
}
