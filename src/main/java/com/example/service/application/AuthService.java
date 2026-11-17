package com.example.service.application;

import com.example.service.api.dto.AuthDtos.LoginRequest;
import com.example.service.api.dto.AuthDtos.LoginResponse;
import com.example.service.api.dto.AuthDtos.UserInfo;
import com.example.service.api.support.ApiException;
import com.example.service.domain.RoleCode;
import com.example.service.domain.User;
import com.example.service.domain.UserStatus;
import com.example.service.infrastructure.repository.UserRepository;
import com.example.service.infrastructure.security.AuthPrincipal;
import com.example.service.infrastructure.security.TokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** UC-01 登录与角色授权。密码使用 BCrypt 散列，令牌为 HMAC 签名且由服务端校验。 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final Clock clock;
    private final AccessGuard accessGuard;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       TokenService tokenService, Clock clock, AccessGuard accessGuard) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.clock = clock;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username().trim())
                .orElseThrow(() -> ApiException.unauthorized("用户名或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("用户名或密码错误");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw ApiException.forbidden("账户状态为 " + user.getStatus() + "，无法登录");
        }
        String token = tokenService.issue(user.getId(), clock.instant());
        return new LoginResponse(token, toInfo(user));
    }

    @Transactional(readOnly = true)
    public UserInfo me(AuthPrincipal principal) {
        accessGuard.requireAuthenticated(principal);
        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> ApiException.unauthorized("账户不存在"));
        return toInfo(user);
    }

    public static UserInfo toInfo(User user) {
        return new UserInfo(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getRoles().stream().map(r -> r.getCode())
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(RoleCode.class))),
                user.getStatus());
    }
}
