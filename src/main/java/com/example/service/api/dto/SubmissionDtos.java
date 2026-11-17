package com.example.service.api.dto;

import com.example.service.domain.JudgeTaskStatus;
import com.example.service.domain.Verdict;
import com.example.service.domain.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 代码提交与评测结果 DTO。 */
public final class SubmissionDtos {

    private SubmissionDtos() {
    }

    public record SubmitRequest(@NotNull(message = "题目版本不能为空") Long problemVersionId,
                                @NotBlank(message = "语言不能为空") @Size(max = 32) String language,
                                @NotBlank(message = "代码不能为空") String sourceCode,
                                @Size(max = 128) String idempotencyKey) {
    }

    public record SubmitResponse(Long submissionId, Long judgeTaskId, JudgeTaskStatus judgeStatus,
                                 int submitNo, Instant submittedAt, boolean idempotentReplay) {
    }

    public record JudgeResultView(Long testCaseVersionId, Visibility visibility, Verdict verdict,
                                  BigDecimal earnedScore, Integer timeUsedMs, Long memoryUsedKb,
                                  String detailSummary) {
    }

    public record SubmissionView(Long id, Long examRecordId, Long problemVersionId, String language,
                                 int submitNo, Instant submittedAt, JudgeTaskStatus judgeStatus,
                                 Verdict overallVerdict, BigDecimal earnedScore, BigDecimal totalPossible,
                                 String lastError, List<JudgeResultView> results) {
    }
}
