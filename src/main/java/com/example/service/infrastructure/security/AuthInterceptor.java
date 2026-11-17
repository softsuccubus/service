package com.example.service.infrastructure.security;

import com.example.service.domain.User;
import com.example.service.domain.UserStatus;
import com.example.service.infrastructure.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.EnumSet;
import java.util.stream.Collectors;

/**
 * 鉴权拦截器：解析 Authorization: Bearer 令牌，加载账户并按最小权限鉴权。
 * 角色与账户状态实时查询，锁定/禁用账户立即失效。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;
    private final UserRepository userRepository;

    public AuthInterceptor(TokenService tokenService, UserRepository userRepository) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AuthContext.clear();
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            // 交由控制器/服务按需拒绝；公开接口无需令牌
            return true;
        }
        String token = header.substring(7).trim();
        tokenService.verify(token, Instant.now()).ifPresent(userId ->
                userRepository.findById(userId)
                        .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                        .map(AuthInterceptor::toPrincipal)
                        .ifPresent(AuthContext::set));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }

    private static AuthPrincipal toPrincipal(User user) {
        return new AuthPrincipal(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRoles().stream().map(r -> r.getCode())
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(com.example.service.domain.RoleCode.class))));
    }
}
