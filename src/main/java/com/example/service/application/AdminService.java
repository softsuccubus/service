package com.example.service.application;

import com.example.service.api.dto.AdminDtos.AuditEventView;
import com.example.service.api.dto.AdminDtos.CreateUserRequest;
import com.example.service.api.dto.AdminDtos.UpdateUserRolesRequest;
import com.example.service.api.dto.AdminDtos.UpdateUserStatusRequest;
import com.example.service.api.dto.AdminDtos.UserView;
import com.example.service.api.support.ApiException;
import com.example.service.domain.Role;
import com.example.service.domain.RoleCode;
import com.example.service.domain.User;
import com.example.service.domain.UserStatus;
import com.example.service.infrastructure.repository.RoleRepository;
import com.example.service.infrastructure.repository.UserRepository;
import com.example.service.infrastructure.security.AuthPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 账户管理（UC-02/UC-03，仅管理员）：最小权限、密码散列、禁用即失效。 */
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    public AdminService(UserRepository userRepository, RoleRepository roleRepository,
                        PasswordEncoder passwordEncoder, AccessGuard accessGuard,
                        AuditService auditService, Clock clock) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserView> listUsers(AuthPrincipal principal) {
        accessGuard.requireAdmin(principal);
        return userRepository.findAllByOrderByIdAsc().stream().map(this::view).toList();
    }

    @Transactional
    public UserView createUser(CreateUserRequest request, AuthPrincipal principal) {
        accessGuard.requireAdmin(principal);
        String username = request.username().trim();
        if (userRepository.existsByUsername(username)) {
            throw ApiException.conflict("用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().trim());
        user.setEmail(request.email());
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(resolveRoles(request.roles()));
        user.setCreatedAt(clock.instant());
        user.setUpdatedAt(clock.instant());
        userRepository.save(user);
        auditService.record(principal.userId(), "USER_CREATE", "sys_user", user.getId(),
                "角色: " + request.roles());
        return view(user);
    }

    @Transactional
    public UserView updateStatus(Long userId, UpdateUserStatusRequest request, AuthPrincipal principal) {
        accessGuard.requireAdmin(principal);
        if (userId.equals(principal.userId()) && request.status() != UserStatus.ACTIVE) {
            throw ApiException.badRequest("不能禁用或锁定当前登录账户");
        }
        User user = requireUser(userId);
        UserStatus before = user.getStatus();
        user.setStatus(request.status());
        user.setUpdatedAt(clock.instant());
        userRepository.save(user);
        auditService.recordChange(principal.userId(), "USER_STATUS_CHANGE", "sys_user", userId,
                "账户状态调整", "{\"status\":\"" + before + "\"}",
                "{\"status\":\"" + request.status() + "\"}");
        return view(user);
    }

    @Transactional
    public UserView updateRoles(Long userId, UpdateUserRolesRequest request, AuthPrincipal principal) {
        accessGuard.requireAdmin(principal);
        User user = requireUser(userId);
        Set<RoleCode> before = user.getRoles().stream().map(Role::getCode)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(RoleCode.class)));
        user.setRoles(resolveRoles(request.roles()));
        user.setUpdatedAt(clock.instant());
        userRepository.save(user);
        auditService.recordChange(principal.userId(), "USER_ROLES_CHANGE", "sys_user", userId,
                "角色调整", before.toString(), request.roles().toString());
        return view(user);
    }

    @Transactional(readOnly = true)
    public List<AuditEventView> audit(AuthPrincipal principal, String targetType, Long targetId) {
        accessGuard.requireAdmin(principal);
        var events = targetType == null ? auditRecent() : auditService.forTarget(targetType, targetId);
        return events.stream().map(e -> new AuditEventView(e.getId(), e.getActorUserId(), e.getAction(),
                e.getTargetType(), e.getTargetId(), e.getReason(), e.getBeforeJson(), e.getAfterJson(),
                e.getCreatedAt())).toList();
    }

    private List<com.example.service.domain.AuditEvent> auditRecent() {
        return auditService.latest();
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("用户不存在"));
    }

    private Set<Role> resolveRoles(Set<RoleCode> codes) {
        Set<Role> roles = new HashSet<>();
        for (RoleCode code : codes) {
            roles.add(roleRepository.findByCode(code)
                    .orElseThrow(() -> ApiException.badRequest("角色不存在: " + code)));
        }
        return roles;
    }

    private UserView view(User user) {
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getStatus(),
                user.getRoles().stream().map(Role::getCode)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(RoleCode.class))),
                user.getCreatedAt());
    }
}
