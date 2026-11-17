package com.example.service.api.dto;

import com.example.service.domain.JudgeTaskStatus;
import com.example.service.domain.RoleCode;
import com.example.service.domain.UserStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 用户管理、评测任务运维与审计查询 DTO。 */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record CreateUserRequest(@NotBlank(message = "用户名不能为空") @Size(max = 64) String username,
                                    @NotBlank(message = "密码不能为空") @Size(min = 6, max = 72) String password,
                                    @NotBlank(message = "显示名称不能为空") @Size(max = 100) String displayName,
                                    @Size(max = 128) String email,
                                    @NotEmpty(message = "至少分配一个角色") Set<RoleCode> roles) {
    }

    public record UpdateUserStatusRequest(@NotNull(message = "状态不能为空") UserStatus status) {
    }

    public record UpdateUserRolesRequest(@NotEmpty(message = "至少分配一个角色") Set<RoleCode> roles) {
    }

    public record UserView(Long id, String username, String displayName, String email,
                           UserStatus status, Set<RoleCode> roles, Instant createdAt) {
    }

    public record JudgeTaskView(Long id, Long submissionId, JudgeTaskStatus status, int attemptCount,
                                Instant leaseUntil, String lastError, Instant queuedAt,
                                Instant startedAt, Instant finishedAt) {
    }

    public record AuditEventView(Long id, Long actorUserId, String action, String targetType, Long targetId,
                                 String reason, String beforeJson, String afterJson, Instant createdAt) {
    }

    public record JudgeConfigView(String engine, List<String> supportedLanguages, int maxAttempts,
                                  int leaseSeconds, int concurrency, boolean workerEnabled) {
    }
}
