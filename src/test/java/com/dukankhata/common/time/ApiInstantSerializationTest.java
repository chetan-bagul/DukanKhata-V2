package com.dukankhata.common.time;

import com.dukankhata.common.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class ApiInstantSerializationTest {
    @Autowired ObjectMapper objectMapper;

    @Test
    void apiInstantsSerializeAsUtcIsoTimestamps() throws Exception {
        var error = new ApiError(false, 400, "TEST", "test", null,
                Instant.parse("2026-03-31T18:30:00Z"), "/test", "correlation");

        assertThat(objectMapper.writeValueAsString(error))
                .contains("\"timestamp\":\"2026-03-31T18:30:00Z\"");
    }
}
