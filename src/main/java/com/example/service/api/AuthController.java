package com.example.service.api;

import com.example.service.api.dto.AuthDtos.LoginRequest;
import com.example.service.api.dto.AuthDtos.LoginResponse;
import com.example.service.api.dto.AuthDtos.UserInfo;
import com.example.service.api.support.ApiResponse;
import com.example.service.application.AuthService;
import com.example.service.infrastructure.security.AuthPrincipal;
import com.example.service.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证接口（UC-01）。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<UserInfo> me(@CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(authService.me(principal));
    }
}
