package com.dukankhata.auth;

import com.dukankhata.auth.dto.AuthResponse;
import com.dukankhata.auth.dto.MeResponse;
import com.dukankhata.common.ratelimit.LoginRateLimitService;
import com.dukankhata.common.observability.CorrelationIdFilter;
import com.dukankhata.common.observability.RequestLoggingFilter;
import com.dukankhata.common.security.ApiSecurityExceptionHandlers;
import com.dukankhata.shop.Shop;
import com.dukankhata.subscription.SubscriptionAccessFilter;
import com.dukankhata.subscription.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AuthController.class, properties = {
        "server.port=8080",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
        "security.jwt.secret=auth-security-test-secret-with-more-than-32-bytes",
        "subscription.trial-days=30",
        "app.cors.allowed-origins=http://localhost:3000,http://localhost:5175,https://app.dukankhata.in"
})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
        SubscriptionAccessFilter.class, CorrelationIdFilter.class,
        RequestLoggingFilter.class, ApiSecurityExceptionHandlers.class})
class AuthSecurityTest {
    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration;
    @Autowired FilterRegistrationBean<SubscriptionAccessFilter> subscriptionFilterRegistration;

    @MockBean AuthService authService;
    @MockBean LoginRateLimitService loginRateLimitService;
    @MockBean UserRepository userRepository;
    @MockBean SubscriptionRepository subscriptionRepository;
    @MockBean JwtRevocationService jwtRevocationService;

    private UUID userId;
    private UUID shopId;
    private User user;

    @BeforeEach
    void setupUser() {
        userId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        Shop shop = mock(Shop.class);
        when(shop.getId()).thenReturn(shopId);
        when(shop.getName()).thenReturn("Test shop");
        when(shop.getOperatingMode()).thenReturn("SMALL");
        when(shop.getPlanStatus()).thenReturn("TRIAL");
        user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getShop()).thenReturn(shop);
        when(user.getRole()).thenReturn("OWNER");
        when(user.getName()).thenReturn("Test owner");
        when(user.getMobile()).thenReturn("9876543210");
        when(user.isActive()).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(authService.me(user)).thenReturn(new MeResponse(userId, shopId, "Test owner", "9876543210",
                "OWNER", "Test shop", "SMALL", "TRIAL"));
    }

    @Test
    void anonymousMeRequestReturnsStandardUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidBearerTokenMeRequestReturnsStandardUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void malformedBearerTokenCannotReadMe() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not.a.valid.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidSignatureCannotReadMe() throws Exception {
        String token = new JwtService("different-test-secret-with-more-than-32-bytes", 60_000)
                .generateToken(user);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void validBearerTokenCanReadMe() throws Exception {
        String token = jwtService.generateToken(user);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(userId.toString()));
        verify(authService).me(user);
    }

    @Test
    void expiredBearerTokenCannotReadMe() throws Exception {
        String expiredToken = io.jsonwebtoken.Jwts.builder()
                .subject(userId.toString())
                .issuedAt(new java.util.Date(System.currentTimeMillis() - 10_000))
                .expiration(new java.util.Date(System.currentTimeMillis() - 1_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "auth-security-test-secret-with-more-than-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void jwtLifetimeMustBePositive() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new JwtService("auth-security-test-secret-with-more-than-32-bytes", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JWT expiration must be greater than zero");
    }

    @Test
    void securityFiltersAreRegisteredOnlyInsideTheSecurityChain() {
        org.assertj.core.api.Assertions.assertThat(jwtFilterRegistration.isEnabled()).isFalse();
        org.assertj.core.api.Assertions.assertThat(subscriptionFilterRegistration.isEnabled()).isFalse();
    }

    @Test
    void nonPublicApiRoutesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void logoutRevokesTokenAndReplayIsRejected() throws Exception {
        String token = jwtService.generateToken(user);
        when(jwtRevocationService.isRevoked(token)).thenReturn(false, true);
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        verify(jwtRevocationService).revoke(token);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void redisFailureDuringRevocationCheckFailsClosedWithServiceUnavailable() throws Exception {
        String token = jwtService.generateToken(user);
        when(jwtRevocationService.isRevoked(token)).thenThrow(new SessionRevocationUnavailableException());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SESSION_REVOCATION_UNAVAILABLE"));
    }

    @Test
    void corsAllowsConfiguredExactOriginAndRejectsUnlistedOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/auth/signup")
                        .header("Origin", "http://localhost:5175")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5175"));

        mockMvc.perform(options("/api/v1/auth/me")
                        .header("Origin", "https://app.dukankhata.in")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.dukankhata.in"));

        mockMvc.perform(options("/api/v1/auth/me")
                        .header("Origin", "https://attacker.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void signupAndLoginRemainPublic() throws Exception {
        AuthResponse authResponse = new AuthResponse("access-token", "Bearer", userId, shopId,
                "Test owner", "9876543210", "OWNER", "Test shop", "SMALL", "TRIAL");
        when(authService.signup(any())).thenReturn(authResponse);
        when(authService.login(any())).thenReturn(authResponse);

        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test owner\",\"mobile\":\"9876543210\",\"password\":\"password123\",\"shopName\":\"Test shop\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"9876543210\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
        verify(authService).signup(any());
        verify(authService).login(any());
    }
}
