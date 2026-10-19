package com.dukankhata.common.security;

import com.dukankhata.common.api.ApiError;
import com.dukankhata.common.observability.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

@Component
public class ApiSecurityExceptionHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper objectMapper;

    public ApiSecurityExceptionHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (Boolean.TRUE.equals(request.getAttribute("sessionRevocationUnavailable"))) {
            write(response, request, HttpStatus.SERVICE_UNAVAILABLE, "SESSION_REVOCATION_UNAVAILABLE",
                    "Authentication is temporarily unavailable. Please try again shortly.");
            return;
        }
        write(response, request, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Authentication is required to access this resource.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       org.springframework.security.access.AccessDeniedException accessDeniedException) throws IOException {
        write(response, request, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this action.");
    }

    private void write(HttpServletResponse response, HttpServletRequest request,
                       HttpStatus status, String code, String message) throws IOException {
        String correlationId = (String) org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY);
        ApiError error = new ApiError(false, status.value(), code, message, null,
                Instant.now(), request.getRequestURI(), correlationId);
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader(CorrelationIdFilter.HEADER, correlationId);
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
