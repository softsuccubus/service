package com.example.service.api.dto;

import com.example.service.domain.VersionStatus;
import com.example.service.domain.Visibility;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 题目与测试点相关 DTO。测试点输入/期望输出只在教师端返回。 */
public final class ProblemDtos {

    private ProblemDtos() {
    }

    public record CreateProblemRequest(String problemCode,
                                       @NotBlank(message = "题目标题不能为空") @Size(max = 200) String title,
                                       @NotBlank(message = "题目描述不能为空") String description,
                                       String inputDesc,
                                       String outputDesc,
                                       @NotEmpty(message = "至少选择一种允许语言") List<String> languages,
                                       @Min(100) @Max(600_000) Integer timeLimitMs,
                                       @Min(16) @Max(4096) Integer memoryLimitMb) {
    }

    public record UpdateProblemRequest(@NotBlank(message = "题目标题不能为空") @Size(max = 200) String title,
                                       @NotBlank(message = "题目描述不能为空") String description,
                                       String inputDesc,
                                       String outputDesc,
                                       @NotEmpty(message = "至少选择一种允许语言") List<String> languages,
                                       @Min(100) @Max(600_000) Integer timeLimitMs,
                                       @Min(16) @Max(4096) Integer memoryLimitMb) {
    }

    public record CreateTestCaseRequest(@NotBlank(message = "测试点名称不能为空") @Size(max = 100) String caseName,
                                        @NotNull(message = "输入不能为空") String input,
                                        @NotNull(message = "期望输出不能为空") String expectedOutput,
                                        @NotNull @DecimalMin(value = "0.00", message = "测试点分值不能为负") BigDecimal points,
                                        Visibility visibility) {
    }

    public record ProblemSummary(Long id, String problemCode, Long ownerId,
                                 Long currentVersionId, Integer currentVersionNo,
                                 Integer latestVersionNo, VersionStatus latestVersionStatus,
                                 boolean archived, Instant createdAt) {
    }

    public record VersionView(Long id, Long problemId, int versionNo, String title, String description,
                              String inputDesc, String outputDesc, List<String> languages,
                              int timeLimitMs, int memoryLimitMb, VersionStatus status,
                              Instant publishedAt, List<SampleView> samples) {
    }

    public record TestCaseView(Long id, Long testCaseId, int versionNo, String caseName,
                               String input, String expectedOutput, BigDecimal points, Visibility visibility) {
    }

    /** 学生可见的样例：只包含公开测试点。 */
    public record SampleView(String caseName, String input, String expectedOutput) {
    }

    public record ProblemDetail(ProblemSummary summary, List<VersionView> versions, List<TestCaseView> testCases) {
    }

    /** 学生端题目详情：不含隐藏测试数据。 */
    public record StudentProblemView(Long problemId, Long problemVersionId, int versionNo, String title,
                                     String description, String inputDesc, String outputDesc,
                                     List<String> languages, int timeLimitMs, int memoryLimitMb,
                                     List<SampleView> samples) {
    }
}
