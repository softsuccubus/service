package com.example.service.api.dto;

import com.example.service.domain.ExamRecordStatus;
import com.example.service.domain.ExamStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 考试、作答、监控与成绩相关 DTO。 */
public final class ExamDtos {

    private ExamDtos() {
    }

    public record CreateExamRequest(@NotBlank(message = "考试名称不能为空") @Size(max = 200) String name,
                                    @NotNull(message = "试卷版本不能为空") Long paperVersionId,
                                    @NotNull(message = "开始时间不能为空") Instant startAt,
                                    @NotNull(message = "结束时间不能为空") Instant endAt,
                                    @Min(value = 1, message = "考试时长至少 1 分钟") int durationMinutes,
                                    List<Long> admissionStudentIds,
                                    List<String> allowedLanguages,
                                    @Min(1) Integer maxSubmissionsPerProblem,
                                    @Min(1024) Integer maxCodeBytes) {
    }

    public record UpdateExamRequest(@NotBlank(message = "考试名称不能为空") @Size(max = 200) String name,
                                    @NotNull(message = "试卷版本不能为空") Long paperVersionId,
                                    @NotNull(message = "开始时间不能为空") Instant startAt,
                                    @NotNull(message = "结束时间不能为空") Instant endAt,
                                    @Min(value = 1, message = "考试时长至少 1 分钟") int durationMinutes,
                                    List<Long> admissionStudentIds,
                                    List<String> allowedLanguages,
                                    @Min(1) Integer maxSubmissionsPerProblem,
                                    @Min(1024) Integer maxCodeBytes) {
    }

    public record ExamView(Long id, String name, Long ownerId, Long paperVersionId, String paperName,
                           Instant startAt, Instant endAt, int durationMinutes, ExamStatus status,
                           List<Long> admissionStudentIds, Map<String, Object> submissionRule,
                           Instant publishedAt, Instant endedAt, Instant createdAt,
                           long participants, Instant serverTime) {
    }

    public record StudentExamView(Long id, String name, ExamStatus status, Instant startAt, Instant endAt,
                                  int durationMinutes, boolean canEnter, boolean entered,
                                  Long examRecordId, ExamRecordStatus recordStatus,
                                  BigDecimal totalScore, Instant serverTime) {
    }

    public record EnterExamResponse(Long recordId, Long examId, String examName, Instant startedAt,
                                    Instant deadlineAt, long remainingSeconds, ExamRecordStatus status) {
    }

    public record ExamPaperView(Long recordId, Long examId, String examName, Instant deadlineAt,
                                long remainingSeconds, ExamRecordStatus status,
                                ExamStatus examStatus, List<ProblemDtos.StudentProblemView> problems,
                                List<DraftView> drafts) {
    }

    public record SaveDraftRequest(String language, String content, Integer baseVersion) {
    }

    public record DraftView(Long id, Long problemVersionId, String language, String content,
                            int draftVersion, Instant savedAt) {
    }

    public record RecordSummaryView(Long recordId, Long studentId, String studentUsername, String studentDisplayName,
                                    ExamRecordStatus status, Instant startedAt, Instant deadlineAt,
                                    BigDecimal totalScore) {
    }

    public record ExamMonitorView(Long examId, String examName, ExamStatus status, Instant serverTime,
                                  long participants, long inProgress, long submitted, long expired,
                                  long submissions, long judgeQueued, long judgeRunning,
                                  long manualReview, Instant endedAt) {
    }

    public record SubmitExamResponse(Long recordId, BigDecimal totalScore, Instant submittedAt,
                                     ExamRecordStatus status) {
    }
}
