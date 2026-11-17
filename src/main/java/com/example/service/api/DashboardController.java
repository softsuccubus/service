package com.example.service.api;

import com.example.service.api.dto.ProblemDtos.ProblemSummary;
import com.example.service.api.support.ApiResponse;
import com.example.service.application.ProblemService;
import com.example.service.infrastructure.security.AuthPrincipal;
import com.example.service.infrastructure.security.CurrentUser;
import com.example.service.presentation.AdminView;
import com.example.service.presentation.StudentView;
import com.example.service.presentation.TeacherView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 首页聚合接口：按角色返回不同的视图数据（最小展示原则）。 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final StudentView studentView;
    private final TeacherView teacherView;
    private final AdminView adminView;

    public DashboardController(StudentView studentView, TeacherView teacherView, AdminView adminView) {
        this.studentView = studentView;
        this.teacherView = teacherView;
        this.adminView = adminView;
    }

    @GetMapping
    public ApiResponse<Object> dashboard(@CurrentUser AuthPrincipal principal) {
        if (principal.isAdmin()) {
            return ApiResponse.ok(adminView.build(principal));
        }
        if (principal.isStaff()) {
            return ApiResponse.ok(teacherView.build(principal));
        }
        return ApiResponse.ok(studentView.build(principal));
    }
}
