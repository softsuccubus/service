package com.example.service.api.dto;

import com.example.service.domain.RoleCode;
import com.example.service.domain.UserStatus;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/** 认证相关请求/响应 DTO。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank(message = "用户名不能为空") String username,
                               @NotBlank(message = "密码不能为空") String password) {
    }

    public record UserInfo(Long id, String username, String displayName, String email,
                           Set<RoleCode> roles, UserStatus status) {
    }

    public record LoginResponse(String token, UserInfo user) {
    }
}
