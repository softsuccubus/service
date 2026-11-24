package com.example.service.api;

import com.example.service.api.dto.ProblemDtos.CreateProblemRequest;
import com.example.service.api.dto.ProblemDtos.CreateTestCaseRequest;
import com.example.service.api.dto.ProblemDtos.ProblemDetail;
import com.example.service.api.dto.ProblemDtos.ProblemSummary;
import com.example.service.api.dto.ProblemDtos.StudentProblemView;
import com.example.service.api.dto.ProblemDtos.TestCaseView;
import com.example.service.api.dto.ProblemDtos.UpdateProblemRequest;
import com.example.service.api.support.ApiResponse;
import com.example.service.application.ProblemService;
import com.example.service.infrastructure.security.AuthPrincipal;
import com.example.service.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 题库与测试点接口（UC-04/UC-05）。 */
@RestController
@RequestMapping("/api/problems")
public class ProblemController {

    private final ProblemService problemService;

    public ProblemController(ProblemService problemService) {
        this.problemService = problemService;
    }

    @GetMapping
    public ApiResponse<List<ProblemSummary>> list(@CurrentUser AuthPrincipal principal,
                                                   @RequestParam(defaultValue = "false") boolean onlyMine,
                                                   @RequestParam(defaultValue = "false") boolean archived) {
        return ApiResponse.ok(problemService.listProblems(principal, onlyMine, archived));
    }

    /** 学生端：已发布题目列表。 */
    @GetMapping("/published")
    public ApiResponse<List<StudentProblemView>> published() {
        return ApiResponse.ok(problemService.listPublishedProblems());
    }

    @GetMapping("/{problemId}")
    public ApiResponse<ProblemDetail> detail(@PathVariable Long problemId) {
        return ApiResponse.ok(problemService.detail(problemId));
    }

    /** 学生端：单题详情（不含隐藏测试点）。 */
    @GetMapping("/{problemId}/student-view")
    public ApiResponse<StudentProblemView> studentView(@PathVariable Long problemId) {
        return ApiResponse.ok(problemService.studentProblem(problemId));
    }

    @PostMapping
    public ApiResponse<ProblemDetail> create(@Valid @RequestBody CreateProblemRequest request,
                                             @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(problemService.createProblem(request, principal));
    }

    @PutMapping("/{problemId}")
    public ApiResponse<ProblemDetail> update(@PathVariable Long problemId,
                                             @Valid @RequestBody UpdateProblemRequest request,
                                             @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(problemService.updateProblem(problemId, request, principal));
    }

    @PostMapping("/{problemId}/publish")
    public ApiResponse<ProblemDetail> publish(@PathVariable Long problemId,
                                              @RequestParam(required = false) Long versionId,
                                              @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(problemService.publish(problemId, versionId, principal));
    }

    /** 归档题目（软删除）。 */
    @DeleteMapping("/{problemId}")
    public ApiResponse<Void> archive(@PathVariable Long problemId, @CurrentUser AuthPrincipal principal) {
        problemService.archiveProblem(problemId, principal);
        return ApiResponse.ok();
    }

    /** 恢复已归档题目。 */
    @PostMapping("/{problemId}/restore")
    public ApiResponse<ProblemDetail> restore(@PathVariable Long problemId,
                                              @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(problemService.restoreProblem(problemId, principal));
    }

    /** 永久删除已归档题目（不可恢复）。 */
    @DeleteMapping("/{problemId}/permanent")
    public ApiResponse<Void> delete(@PathVariable Long problemId, @CurrentUser AuthPrincipal principal) {
        problemService.deleteProblem(problemId, principal);
        return ApiResponse.ok();
    }

    @PostMapping("/{problemId}/test-cases")
    public ApiResponse<TestCaseView> addTestCase(@PathVariable Long problemId,
                                                 @Valid @RequestBody CreateTestCaseRequest request,
                                                 @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(problemService.addTestCase(problemId, request, principal));
    }

    @PutMapping("/{problemId}/test-cases/{testCaseId}")
    public ApiResponse<TestCaseView> updateTestCase(@PathVariable Long problemId,
                                                    @PathVariable Long testCaseId,
                                                    @Valid @RequestBody CreateTestCaseRequest request,
                                                    @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(problemService.updateTestCase(problemId, testCaseId, request, principal));
    }

    @DeleteMapping("/{problemId}/test-cases/{testCaseId}")
    public ApiResponse<Void> archiveTestCase(@PathVariable Long problemId, @PathVariable Long testCaseId,
                                             @CurrentUser AuthPrincipal principal) {
        problemService.archiveTestCase(problemId, testCaseId, principal);
        return ApiResponse.ok();
    }
}
