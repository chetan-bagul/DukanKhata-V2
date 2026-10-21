package com.dukankhata.common.exception;

import com.dukankhata.common.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    @Test
    void unexpectedErrorsDoNotExposeExceptionDetails() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        String internalDetail = "jdbc:postgresql://internal-host/db?password=must-not-leak";

        ApiError body = handler.handleUnexpected(new IllegalStateException(internalDetail), request)
                .getBody();

        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message()).doesNotContain(internalDetail, "must-not-leak", "internal-host");
    }
}
