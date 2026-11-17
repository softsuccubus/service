package com.example.service.infrastructure.security;

import com.example.service.domain.RoleCode;

import java.util.Set;

/** 当前请求的已认证主体，由鉴权拦截器写入线程上下文。 */
public record AuthPrincipal(Long userId, String username, String displayName, Set<RoleCode> roles) {

    public boolean hasRole(RoleCode role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole(RoleCode.ADMIN);
    }

    public boolean isTeacher() {
        return hasRole(RoleCode.TEACHER);
    }

    public boolean isStudent() {
        return hasRole(RoleCode.STUDENT);
    }

    /** 教师或管理员。 */
    public boolean isStaff() {
        return isTeacher() || isAdmin();
    }
}
