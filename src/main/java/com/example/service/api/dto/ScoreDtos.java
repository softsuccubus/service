package com.example.service.api.dto;

import com.example.service.domain.ExamRecordStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 成绩查询与人工改分 DTO。 */
public final class ScoreDtos {

    private ScoreDtos() {
    }

    public record ProblemScore(Long problemVersionId, String problemTitle, BigDecimal score, boolean manual) {
    }

    public record ScoreRow(Long examRecordId, Long studentId, String studentUsername, String studentDisplayName,
                           ExamRecordStatus recordStatus, BigDecimal totalScore,
                           List<ProblemScore> problemScores) {
    }

    public record ExamScoreView(Long examId, String examName, BigDecimal totalPossibleScore,
                                Instant serverTime, List<ScoreRow> rows) {
    }

    public record MyScoreView(Long examId, String examName, Long examRecordId, ExamRecordStatus status,
                              BigDecimal totalScore, Instant startedAt, Instant deadlineAt,
                              List<ProblemScore> problemScores) {
    }

    public record ManualScoreRequest(@NotNull(message = "分数不能为空")
                                     @DecimalMin(value = "0.00", message = "分数不能为负") BigDecimal score,
                                     @NotBlank(message = "改分原因不能为空") String reason) {
    }
}
