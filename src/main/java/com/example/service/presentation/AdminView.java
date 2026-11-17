package com.example.service.presentation;

import com.example.service.api.dto.AdminDtos.AuditEventView;
import com.example.service.api.dto.AdminDtos.JudgeConfigView;
import com.example.service.api.dto.AdminDtos.UserView;
import com.example.service.application.AdminService;
import com.example.service.application.JudgeDispatchService;
import com.example.service.domain.UserStatus;
import com.example.service.infrastructure.security.AuthPrincipal;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** 管理员视图组装：账户、评测配置与最近审计事件。 */
@Component
public class AdminView {

    private final AdminService adminService;
    private final JudgeDispatchService judgeDispatchService;

    public AdminView(AdminService adminService, JudgeDispatchService judgeDispatchService) {
        this.adminService = adminService;
        this.judgeDispatchService = judgeDispatchService;
    }

    public Dashboard build(AuthPrincipal principal) {
        List<UserView> users = adminService.listUsers(principal);
        JudgeConfigView judgeConfig = judgeDispatchService.config(principal);
        List<AuditEventView> audit = adminService.audit(principal, null, null).stream().limit(50).toList();
        long active = users.stream().filter(u -> u.status() == UserStatus.ACTIVE).count();
        return new Dashboard("ADMIN", principal.displayName(), Instant.now(), users, active,
                judgeConfig, audit);
    }

    public record Dashboard(String role, String displayName, Instant serverTime,
                            List<UserView> users, long activeUsers,
                            JudgeConfigView judgeConfig, List<AuditEventView> recentAuditEvents) {
    }
}
