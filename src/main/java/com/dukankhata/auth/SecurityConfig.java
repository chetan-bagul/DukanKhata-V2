package com.dukankhata.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.dukankhata.subscription.SubscriptionAccessFilter;
import com.dukankhata.common.observability.CorrelationIdFilter;
import com.dukankhata.common.observability.RequestLoggingFilter;
import com.dukankhata.common.security.ApiSecurityExceptionHandlers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SubscriptionAccessFilter subscriptionAccessFilter;
    private final CorrelationIdFilter correlationIdFilter;
    private final RequestLoggingFilter requestLoggingFilter;
    private final ApiSecurityExceptionHandlers apiSecurityExceptionHandlers;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, SubscriptionAccessFilter subscriptionAccessFilter,
                          CorrelationIdFilter correlationIdFilter, RequestLoggingFilter requestLoggingFilter,
                          ApiSecurityExceptionHandlers apiSecurityExceptionHandlers) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.subscriptionAccessFilter = subscriptionAccessFilter;
        this.correlationIdFilter = correlationIdFilter;
        this.requestLoggingFilter = requestLoggingFilter;
        this.apiSecurityExceptionHandlers = apiSecurityExceptionHandlers;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/me").authenticated()
                        .requestMatchers(
                                "/api/v1/auth/signup",
                                "/api/v1/auth/login",
                                "/api/v1/subscription/plans",
                                "/api/v1/subscription/webhook",
                                "/api/v1/health",
                                "/api/v1/live",
                                "/api/v1/ready",
                                "/actuator/health",
                                "/actuator/health/**"
                        ).permitAll()
                        .anyRequest().authenticated())
                  .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(apiSecurityExceptionHandlers)
                        .accessDeniedHandler(apiSecurityExceptionHandlers))
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(subscriptionAccessFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(requestLoggingFilter, SubscriptionAccessFilter.class)
                .build();
    }

    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration() {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(jwtAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<SubscriptionAccessFilter> subscriptionAccessFilterRegistration() {
        FilterRegistrationBean<SubscriptionAccessFilter> registration =
                new FilterRegistrationBean<>(subscriptionAccessFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:${CORS_ALLOWED_ORIGINS:http://localhost:3000,http://localhost:5173,http://localhost:5175}}")
            String configuredOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> allowedOrigins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toList());
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));
        config.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Idempotency-Key"
        ));
        config.setExposedHeaders(List.of(
                "X-Page", "X-Page-Size", "X-Has-More"
        ));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
