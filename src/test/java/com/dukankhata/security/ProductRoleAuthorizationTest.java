package com.dukankhata.security;

import com.dukankhata.auth.JwtAuthenticationFilter;
import com.dukankhata.auth.JwtRevocationService;
import com.dukankhata.auth.JwtService;
import com.dukankhata.auth.SecurityConfig;
import com.dukankhata.auth.User;
import com.dukankhata.auth.UserRepository;
import com.dukankhata.common.observability.CorrelationIdFilter;
import com.dukankhata.common.observability.RequestLoggingFilter;
import com.dukankhata.common.security.ApiSecurityExceptionHandlers;
import com.dukankhata.product.ProductController;
import com.dukankhata.product.ProductService;
import com.dukankhata.subscription.SubscriptionAccessFilter;
import com.dukankhata.subscription.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = ProductController.class, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
        "security.jwt.secret=role-authorization-test-secret-at-least-32-bytes",
        "subscription.trial-days=30"
})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
        SubscriptionAccessFilter.class, CorrelationIdFilter.class,
        RequestLoggingFilter.class, ApiSecurityExceptionHandlers.class})
class ProductRoleAuthorizationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @MockBean ProductService productService;
    @MockBean UserRepository userRepository;
    @MockBean JwtRevocationService jwtRevocationService;
    @MockBean SubscriptionRepository subscriptionRepository;

    private UUID userId;
    private User user;

    @BeforeEach
    void configureActiveTrialUser() {
        userId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        var shop = mock(com.dukankhata.shop.Shop.class);
        when(shop.getId()).thenReturn(shopId);
        when(shop.getOperatingMode()).thenReturn("SMALL");
        when(shop.getCreatedAt()).thenReturn(Instant.now());
        user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getShop()).thenReturn(shop);
        when(user.isActive()).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findTopByShopIdAndStatusAndEndsAtAfterOrderByCreatedAtDescIdDesc(
                eq(shopId), any(), any())).thenReturn(Optional.empty());
        when(subscriptionRepository.existsByShopIdAndRazorpayPaymentIdIsNotNull(shopId)).thenReturn(false);
        when(jwtRevocationService.isRevoked(anyString())).thenReturn(false);
    }

    @Test
    void ownerCanCreateProduct() throws Exception {
        when(user.getRole()).thenReturn("OWNER");

        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + jwtService.generateToken(user))
                        .contentType("application/json")
                        .content("{\"name\":\"Test product\",\"sellingPrice\":10.00}"))
                .andExpect(status().isCreated());

        verify(productService).create(any());
    }

    @Test
    void staffCannotCreateProduct() throws Exception {
        when(user.getRole()).thenReturn("STAFF");

        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + jwtService.generateToken(user))
                        .contentType("application/json")
                        .content("{\"name\":\"Test product\",\"sellingPrice\":10.00}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(productService);
    }
}
