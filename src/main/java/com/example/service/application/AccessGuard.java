package com.example.service.application;

import com.example.service.api.support.ApiException;
import com.example.service.infrastructure.security.AuthPrincipal;
import org.springframework.stereotype.Component;

/** 最小权限访问控制（默认拒绝）。对象级权限由各服务进一步校验。 */
@Component
public class AccessGuard {

    public AuthPrincipal requireAuthenticated(AuthPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("未登录或登录态已失效");
        }
        return principal;
    }

    public AuthPrincipal requireStaff(AuthPrincipal principal) {
        requireAuthenticated(principal);
        if (!principal.isStaff()) {
            throw ApiException.forbidden("仅教师或管理员可执行该操作");
        }
        return principal;
    }

    public AuthPrincipal requireAdmin(AuthPrincipal principal) {
        requireAuthenticated(principal);
        if (!principal.isAdmin()) {
            throw ApiException.forbidden("仅管理员可执行该操作");
        }
        return principal;
    }

    public AuthPrincipal requireStudent(AuthPrincipal principal) {
        requireAuthenticated(principal);
        if (!principal.isStudent()) {
            throw ApiException.forbidden("仅学生可执行该操作");
        }
        return principal;
    }

    /** 资源创建者或管理员。 */
    public void requireOwnerOrAdmin(AuthPrincipal principal, Long ownerId, String resource) {
        requireStaff(principal);
        if (!principal.isAdmin() && !principal.userId().equals(ownerId)) {
            throw ApiException.forbidden("无权操作该" + resource);
        }
    }
}
