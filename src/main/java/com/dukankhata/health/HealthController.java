package com.dukankhata.health;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/v1")
public class HealthController {
    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private final JdbcTemplate jdbcTemplate;
    private final RedisConnectionFactory redisConnectionFactory;
    private final MappedSchemaHealthIndicator mappedSchemaHealthIndicator;

    public HealthController(JdbcTemplate jdbcTemplate, RedisConnectionFactory redisConnectionFactory,
                            MappedSchemaHealthIndicator mappedSchemaHealthIndicator) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
        this.mappedSchemaHealthIndicator = mappedSchemaHealthIndicator;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "dukankhata-backend");
    }

    @GetMapping("/live")
    public Map<String, String> live() {
        return Map.of("status", "UP", "check", "liveness");
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (RuntimeException exception) {
            return unavailable("database", exception);
        }

        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            if (!"PONG".equalsIgnoreCase(connection.ping())) {
                return unavailable("redis", null);
            }
        } catch (RuntimeException exception) {
            return unavailable("redis", exception);
        }

        if (!mappedSchemaHealthIndicator.isMappedSchemaAvailable()) {
            return unavailable("schema", null);
        }
        return ResponseEntity.ok(Map.of("status", "UP", "check", "readiness", "redis", "UP", "database", "UP"));
    }

    private ResponseEntity<Map<String, String>> unavailable(String dependency, RuntimeException exception) {
        if (exception != null) {
            log.warn("Readiness dependency unavailable dependency={} exceptionType={}",
                    dependency, exception.getClass().getSimpleName());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "DOWN", "check", "readiness", "dependency", dependency));
    }
}
