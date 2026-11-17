package com.example.service.infrastructure.security;

import com.example.service.api.support.ApiException;

/** 请求级认证上下文（ThreadLocal），由拦截器在请求结束时清理。 */
public final class AuthContext {

    private static final ThreadLocal<AuthPrincipal> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(AuthPrincipal principal) {
        HOLDER.set(principal);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static AuthPrincipal get() {
        return HOLDER.get();
    }

    public static AuthPrincipal require() {
        AuthPrincipal principal = HOLDER.get();
        if (principal == null) {
            throw ApiException.unauthorized("未登录或登录态已失效");
        }
        return principal;
    }
}
