package com.example.service.api;

import com.example.service.api.dto.AdminDtos.AuditEventView;
import com.example.service.api.dto.AdminDtos.CreateUserRequest;
import com.example.service.api.dto.AdminDtos.JudgeConfigView;
import com.example.service.api.dto.AdminDtos.JudgeTaskView;
import com.example.service.api.dto.AdminDtos.UpdateUserRolesRequest;
import com.example.service.api.dto.AdminDtos.UpdateUserStatusRequest;
import com.example.service.api.dto.AdminDtos.UserView;
import com.example.service.api.support.ApiResponse;
import com.example.service.application.AdminService;
import com.example.service.application.JudgeDispatchService;
import com.example.service.domain.JudgeTaskStatus;
import com.example.service.infrastructure.security.AuthPrincipal;
import com.example.service.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端接口（UC-02/UC-03，用户、评测任务与审计）。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final JudgeDispatchService judgeDispatchService;

    public AdminController(AdminService adminService, JudgeDispatchService judgeDispatchService) {
        this.adminService = adminService;
        this.judgeDispatchService = judgeDispatchService;
    }

    @GetMapping("/users")
    public ApiResponse<List<UserView>> users(@CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(adminService.listUsers(principal));
    }

    @PostMapping("/users")
    public ApiResponse<UserView> createUser(@Valid @RequestBody CreateUserRequest request,
                                            @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(adminService.createUser(request, principal));
    }

    @PutMapping("/users/{userId}/status")
    public ApiResponse<UserView> updateStatus(@PathVariable Long userId,
                                              @Valid @RequestBody UpdateUserStatusRequest request,
                                              @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(adminService.updateStatus(userId, request, principal));
    }

    @PutMapping("/users/{userId}/roles")
    public ApiResponse<UserView> updateRoles(@PathVariable Long userId,
                                             @Valid @RequestBody UpdateUserRolesRequest request,
                                             @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(adminService.updateRoles(userId, request, principal));
    }

    @GetMapping("/audit-events")
    public ApiResponse<List<AuditEventView>> audit(@RequestParam(required = false) String targetType,
                                                   @RequestParam(required = false) Long targetId,
                                                   @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(adminService.audit(principal, targetType, targetId));
    }

    @GetMapping("/judge-tasks")
    public ApiResponse<List<JudgeTaskView>> judgeTasks(@RequestParam(required = false) JudgeTaskStatus status,
                                                       @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(judgeDispatchService.listTasks(status, principal));
    }

    @PostMapping("/judge-tasks/{taskId}/retry")
    public ApiResponse<JudgeTaskView> retry(@PathVariable Long taskId, @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(judgeDispatchService.retry(taskId, principal));
    }

    @PostMapping("/judge-tasks/{taskId}/manual-review")
    public ApiResponse<JudgeTaskView> manualReview(@PathVariable Long taskId,
                                                   @RequestParam(required = false) String reason,
                                                   @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(judgeDispatchService.manualReview(taskId,
                reason == null ? "管理员转人工复核" : reason, principal));
    }

    @GetMapping("/judge-config")
    public ApiResponse<JudgeConfigView> judgeConfig(@CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(judgeDispatchService.config(principal));
    }
}
