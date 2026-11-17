package com.example.service.application;

import com.example.service.api.dto.SubmissionDtos.JudgeResultView;
import com.example.service.api.dto.SubmissionDtos.SubmitRequest;
import com.example.service.api.dto.SubmissionDtos.SubmitResponse;
import com.example.service.api.dto.SubmissionDtos.SubmissionView;
import com.example.service.api.support.ApiException;
import com.example.service.domain.Exam;
import com.example.service.domain.ExamRecord;
import com.example.service.domain.ExamRecordStatus;
import com.example.service.domain.ExamStatus;
import com.example.service.domain.JudgeResult;
import com.example.service.domain.JudgeTask;
import com.example.service.domain.JudgeTaskStatus;
import com.example.service.domain.OutboxEvent;
import com.example.service.domain.PaperItem;
import com.example.service.domain.ProblemVersion;
import com.example.service.domain.Submission;
import com.example.service.domain.TestCaseVersion;
import com.example.service.domain.Verdict;
import com.example.service.domain.Visibility;
import com.example.service.infrastructure.JsonSupport;
import com.example.service.infrastructure.MessagePublisher;
import com.example.service.infrastructure.repository.ExamRecordRepository;
import com.example.service.infrastructure.repository.JudgeResultRepository;
import com.example.service.infrastructure.repository.JudgeTaskRepository;
import com.example.service.infrastructure.repository.OutboxEventRepository;
import com.example.service.infrastructure.repository.PaperItemRepository;
import com.example.service.infrastructure.repository.ProblemVersionRepository;
import com.example.service.infrastructure.repository.SubmissionRepository;
import com.example.service.infrastructure.repository.TestCaseVersionRepository;
import com.example.service.infrastructure.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 代码提交（UC-11）。
 *
 * <p>提交、评测任务与 Outbox 事件在同一事务内写入（BR-04），保证"提交成功即评测可靠入队"。
 * 幂等键唯一：重复请求返回既有提交，不重复创建任务（BR-05）。</p>
 */
@Service
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final JudgeTaskRepository judgeTaskRepository;
    private final JudgeResultRepository judgeResultRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ExamRecordRepository examRecordRepository;
    private final PaperItemRepository paperItemRepository;
    private final ProblemVersionRepository problemVersionRepository;
    private final TestCaseVersionRepository testCaseVersionRepository;
    private final ExamService examService;
    private final AccessGuard accessGuard;
    private final JsonSupport jsonSupport;
    private final MessagePublisher messagePublisher;
    private final AuditService auditService;
    private final Clock clock;

    public SubmissionService(SubmissionRepository submissionRepository,
                             JudgeTaskRepository judgeTaskRepository,
                             JudgeResultRepository judgeResultRepository,
                             OutboxEventRepository outboxEventRepository,
                             ExamRecordRepository examRecordRepository,
                             PaperItemRepository paperItemRepository,
                             ProblemVersionRepository problemVersionRepository,
                             TestCaseVersionRepository testCaseVersionRepository,
                             ExamService examService,
                             AccessGuard accessGuard,
                             JsonSupport jsonSupport,
                             MessagePublisher messagePublisher,
                             AuditService auditService,
                             Clock clock) {
        this.submissionRepository = submissionRepository;
        this.judgeTaskRepository = judgeTaskRepository;
        this.judgeResultRepository = judgeResultRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.examRecordRepository = examRecordRepository;
        this.paperItemRepository = paperItemRepository;
        this.problemVersionRepository = problemVersionRepository;
        this.testCaseVersionRepository = testCaseVersionRepository;
        this.examService = examService;
        this.accessGuard = accessGuard;
        this.jsonSupport = jsonSupport;
        this.messagePublisher = messagePublisher;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public SubmitResponse submit(Long examId, SubmitRequest request, AuthPrincipal principal) {
        accessGuard.requireStudent(principal);
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            var replay = submissionRepository.findByIdempotencyKey(request.idempotencyKey().trim());
            if (replay.isPresent()) {
                Submission existing = replay.get();
                JudgeTask task = judgeTaskRepository.findBySubmissionId(existing.getId()).orElse(null);
                return new SubmitResponse(existing.getId(), task == null ? null : task.getId(),
                        task == null ? null : task.getStatus(), existing.getSubmitNo(),
                        existing.getSubmittedAt(), true);
            }
        }

        Exam exam = examService.requireExam(examId);
        ExamRecord record = examRecordRepository.findByExamIdAndStudentId(examId, principal.userId())
                .orElseThrow(() -> ApiException.conflict("请先进入考试"));
        Instant now = clock.instant();
        assertAccepting(exam, record, now);
        assertPaperContains(exam, request.problemVersionId());

        ProblemVersion version = problemVersionRepository.findById(request.problemVersionId())
                .orElseThrow(() -> ApiException.notFound("题目版本不存在"));
        ExamService.SubmissionRule rule = examService.submissionRule(exam);
        String language = normalizeLanguage(request.language());
        if (!ExamEntryService.languageAllowed(rule, jsonSupport.readStringList(version.getLanguagesJson()),
                language)) {
            throw ApiException.badRequest("该题目或考试不允许使用语言: " + language);
        }
        String code = request.sourceCode() == null ? "" : request.sourceCode();
        int bytes = code.getBytes(StandardCharsets.UTF_8).length;
        if (bytes == 0) {
            throw ApiException.badRequest("代码不能为空");
        }
        if (bytes > rule.maxCodeBytes()) {
            throw ApiException.badRequest("代码长度 " + bytes + " 字节超过上限 " + rule.maxCodeBytes() + " 字节");
        }
        long previousCount = submissionRepository
                .countByExamRecordIdAndProblemVersionId(record.getId(), version.getId());
        if (previousCount >= rule.maxSubmissionsPerProblem()) {
            throw ApiException.conflict("该题提交次数已达上限 " + rule.maxSubmissionsPerProblem() + " 次");
        }

        Submission submission = new Submission();
        submission.setExamRecordId(record.getId());
        submission.setProblemVersionId(version.getId());
        submission.setLanguage(language);
        submission.setSourceCode(code);
        submission.setSubmittedAt(now);
        submission.setSubmitNo((int) previousCount + 1);
        submission.setIdempotencyKey(request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                ? UUID.randomUUID().toString()
                : request.idempotencyKey().trim());
        submissionRepository.save(submission);

        JudgeTask task = new JudgeTask();
        task.setSubmissionId(submission.getId());
        task.setIdempotencyKey("submission-" + submission.getId());
        task.setStatus(JudgeTaskStatus.QUEUED);
        task.setQueuedAt(now);
        judgeTaskRepository.save(task);

        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("judge_task");
        event.setAggregateId(task.getId());
        event.setEventType("JUDGE_TASK_QUEUED");
        event.setPayloadJson(jsonSupport.write(Map.of(
                "taskId", task.getId(),
                "submissionId", submission.getId(),
                "examRecordId", record.getId())));
        event.setCreatedAt(now);
        outboxEventRepository.save(event);

        // 事务内尝试快速投递：失败无妨，Outbox 投递器会兜底。
        messagePublisher.publish(new MessagePublisher.JudgeTaskMessage(task.getId()));
        auditService.record(principal.userId(), "SUBMISSION_CREATE", "submission", submission.getId(), null);
        return new SubmitResponse(submission.getId(), task.getId(), task.getStatus(), submission.getSubmitNo(),
                submission.getSubmittedAt(), false);
    }

    @Transactional(readOnly = true)
    public List<SubmissionView> listForRecord(Long examRecordId, AuthPrincipal principal) {
        ExamRecord record = examRecordRepository.findById(examRecordId)
                .orElseThrow(() -> ApiException.notFound("考试记录不存在"));
        if (!record.getStudentId().equals(principal.userId()) && !principal.isStaff()) {
            throw ApiException.forbidden("无权查看他人的提交");
        }
        return submissionRepository.findByExamRecordIdOrderBySubmittedAtDesc(examRecordId).stream()
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public SubmissionView detail(Long submissionId, AuthPrincipal principal) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("提交不存在"));
        ExamRecord record = examRecordRepository.findById(submission.getExamRecordId())
                .orElseThrow(() -> ApiException.notFound("考试记录不存在"));
        if (!record.getStudentId().equals(principal.userId()) && !principal.isStaff()) {
            throw ApiException.forbidden("无权查看他人的提交");
        }
        return view(submission);
    }

    /** 学生本人某记录的提交历史（含最近一次评测状态）。 */
    @Transactional(readOnly = true)
    public List<SubmissionView> listForStudentExams(Long examId, AuthPrincipal principal) {
        accessGuard.requireStudent(principal);
        ExamRecord record = examRecordRepository.findByExamIdAndStudentId(examId, principal.userId())
                .orElseThrow(() -> ApiException.notFound("尚未进入该考试"));
        return listForRecord(record.getId(), principal);
    }

    private void assertAccepting(Exam exam, ExamRecord record, Instant now) {
        if (exam.getStatus() == ExamStatus.SUSPENDED) {
            throw ApiException.conflict("考试已暂停，暂不可提交");
        }
        if (exam.getStatus() != ExamStatus.RUNNING) {
            throw ApiException.conflict("考试未在进行中，当前状态: " + exam.getStatus());
        }
        if (record.getStatus() == ExamRecordStatus.EXPIRED) {
            throw ApiException.conflict("已超过个人作答截止时间");
        }
        if (record.getStatus() == ExamRecordStatus.SUBMITTED) {
            throw ApiException.conflict("考试已提交，不能再提交代码");
        }
        if (!now.isBefore(record.getDeadlineAt())) {
            record.setStatus(ExamRecordStatus.EXPIRED);
            examRecordRepository.save(record);
            throw ApiException.conflict("已超过个人作答截止时间");
        }
    }

    private void assertPaperContains(Exam exam, Long problemVersionId) {
        PaperItem item = paperItemRepository
                .findByPaperVersionIdAndProblemVersionId(exam.getPaperVersionId(), problemVersionId)
                .orElseThrow(() -> ApiException.badRequest("该题目不在本次试卷中"));
        if (item.getProblemVersionId() == null) {
            throw ApiException.badRequest("该题目不在本次试卷中");
        }
    }

    private String normalizeLanguage(String language) {
        return language == null ? "" : language.trim().toUpperCase(Locale.ROOT);
    }

    private SubmissionView view(Submission submission) {
        JudgeTask task = judgeTaskRepository.findBySubmissionId(submission.getId()).orElse(null);
        List<JudgeResult> results = task == null ? List.of()
                : judgeResultRepository.findByJudgeTaskIdOrderByIdAsc(task.getId());
        ProblemVersion version = problemVersionRepository.findById(submission.getProblemVersionId()).orElse(null);
        List<Long> frozen = version == null ? List.of()
                : jsonSupport.readLongList(version.getTestCaseVersionIdsJson());
        Map<Long, Visibility> visibility = new java.util.HashMap<>();
        BigDecimal possible = BigDecimal.ZERO;
        if (!frozen.isEmpty()) {
            for (TestCaseVersion cv : testCaseVersionRepository.findByIdInOrderByIdAsc(frozen)) {
                visibility.put(cv.getId(), cv.getVisibility());
                possible = possible.add(cv.getPoints() == null ? BigDecimal.ZERO : cv.getPoints());
            }
        }
        BigDecimal earned = results.stream().map(JudgeResult::getEarnedScore)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        Verdict overall = results.stream().map(JudgeResult::getVerdict)
                .filter(v -> v != Verdict.AC).findFirst().orElse(results.isEmpty() ? null : Verdict.AC);
        List<JudgeResultView> resultViews = new ArrayList<>();
        for (JudgeResult result : results) {
            resultViews.add(new JudgeResultView(result.getTestCaseVersionId(),
                    visibility.getOrDefault(result.getTestCaseVersionId(), Visibility.HIDDEN),
                    result.getVerdict(), result.getEarnedScore(), result.getTimeUsedMs(),
                    result.getMemoryUsedKb(), result.getDetailSummary()));
        }
        return new SubmissionView(submission.getId(), submission.getExamRecordId(),
                submission.getProblemVersionId(), submission.getLanguage(), submission.getSubmitNo(),
                submission.getSubmittedAt(), task == null ? null : task.getStatus(), overall,
                earned, possible, task == null ? null : task.getLastError(), resultViews);
    }
}
