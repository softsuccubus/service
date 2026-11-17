package com.example.service.infrastructure.judge;

import com.example.service.domain.Visibility;
import com.example.service.domain.Verdict;

import java.math.BigDecimal;
import java.util.List;

/** 评测引擎的输入输出模型，与具体实现（mock / 容器沙箱）解耦。 */
public final class JudgeModel {

    private JudgeModel() {
    }

    /** 单个测试点。输入/期望输出只以引用键传递，避免隐藏数据进入日志或响应。 */
    public record JudgeTestCase(Long testCaseVersionId,
                                String caseName,
                                BigDecimal points,
                                Visibility visibility,
                                String inputRef,
                                String expectedRef) {
    }

    public record JudgeRequest(Long submissionId,
                               Long problemVersionId,
                               String language,
                               String sourceCode,
                               int timeLimitMs,
                               int memoryLimitMb,
                               List<JudgeTestCase> testCases) {
    }

    public record JudgeCaseOutcome(Long testCaseVersionId,
                                   Verdict verdict,
                                   BigDecimal earnedScore,
                                   Integer timeUsedMs,
                                   Long memoryUsedKb,
                                   String detailSummary) {
    }

    public record JudgeOutcome(Verdict overallVerdict,
                               List<JudgeCaseOutcome> caseOutcomes,
                               String engineName) {
    }
}
