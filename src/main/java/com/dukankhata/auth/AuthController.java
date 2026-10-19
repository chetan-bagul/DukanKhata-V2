package com.dukankhata.auth;
import com.dukankhata.common.audit.AuditResource;

import com.dukankhata.auth.dto.*;
import com.dukankhata.common.api.ApiResponse;
import com.dukankhata.common.ratelimit.LoginRateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@AuditResource("AUTH")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final LoginRateLimitService loginRateLimitService;
    private final JwtRevocationService jwtRevocationService;

    public AuthController(AuthService authService, LoginRateLimitService loginRateLimitService,
                          JwtRevocationService jwtRevocationService) {
        this.authService = authService;
        this.loginRateLimitService = loginRateLimitService;
        this.jwtRevocationService = jwtRevocationService;
    }

    @PostMapping("/signup")
    public ApiResponse<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(authService.signup(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        loginRateLimitService.check(request.mobile(), httpRequest);
        return ApiResponse.ok(authService.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(Authentication authentication) {
        return ApiResponse.ok(authService.me((User) authentication.getPrincipal()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String authorization) {
        jwtRevocationService.revoke(authorization.substring(7));
        return ApiResponse.success("Logged out successfully");
    }
}
