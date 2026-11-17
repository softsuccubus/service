package com.example.service.application;

import com.example.service.api.dto.ExamDtos.DraftView;
import com.example.service.api.dto.ExamDtos.EnterExamResponse;
import com.example.service.api.dto.ExamDtos.ExamPaperView;
import com.example.service.api.dto.ExamDtos.SaveDraftRequest;
import com.example.service.api.support.ApiException;
import com.example.service.domain.AnswerDraft;
import com.example.service.domain.Exam;
import com.example.service.domain.ExamRecord;
import com.example.service.domain.ExamRecordStatus;
import com.example.service.domain.ExamStatus;
import com.example.service.domain.PaperItem;
import com.example.service.domain.User;

import com.example.service.infrastructure.repository.AnswerDraftRepository;
import com.example.service.infrastructure.repository.ExamRecordRepository;
import com.example.service.infrastructure.repository.PaperItemRepository;
import com.example.service.infrastructure.repository.SubmissionRepository;
import com.example.service.infrastructure.repository.UserRepository;
import com.example.service.infrastructure.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 进入考试（UC-09）与草稿保存（UC-10）。
 * 截止时间 = min(考试结束时间, 进入时间 + 个人时长)（BR-02），全部使用服务端时间。
 */
@Service
public class ExamEntryService {

    private final ExamService examService;
    private final ExamRecordRepository examRecordRepository;
    private final AnswerDraftRepository answerDraftRepository;
    private final PaperItemRepository paperItemRepository;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final ProblemService problemService;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    public ExamEntryService(ExamService examService,
                            ExamRecordRepository examRecordRepository,
                            AnswerDraftRepository answerDraftRepository,
                            PaperItemRepository paperItemRepository,
                            SubmissionRepository submissionRepository,
                            UserRepository userRepository,
                            ProblemService problemService,
                            AccessGuard accessGuard,
                            AuditService auditService,
                            Clock clock) {
        this.examService = examService;
        this.examRecordRepository = examRecordRepository;
        this.answerDraftRepository = answerDraftRepository;
        this.paperItemRepository = paperItemRepository;
        this.submissionRepository = submissionRepository;
        this.userRepository = userRepository;
        this.problemService = problemService;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** 进入考试：首次进入创建考试记录；重复进入返回同一记录，不重置计时。 */
    @Transactional
    public EnterExamResponse enter(Long examId, AuthPrincipal principal) {
        accessGuard.requireStudent(principal);
        Instant now = clock.instant();
        Exam exam = examService.lockForEntry(examId);
        if (!examService.isAdmitted(exam, principal.userId())) {
            throw ApiException.forbidden("你不在本场考试的准入名单中");
        }
        ExamRecord existing = examRecordRepository
                .findByExamIdAndStudentId(examId, principal.userId()).orElse(null);
        if (existing != null) {
            if (existing.getStatus() == ExamRecordStatus.EXPIRED) {
                throw ApiException.conflict("已超过个人作答截止时间，无法继续作答");
            }
            if (existing.getStatus() == ExamRecordStatus.SUBMITTED) {
                return response(exam, existing, now);
            }
            if (exam.getStatus() != ExamStatus.RUNNING) {
                throw ApiException.conflict("考试当前状态为 " + exam.getStatus() + "，暂不可作答");
            }
            return response(exam, existing, now);
        }

        if (exam.getStatus() != ExamStatus.RUNNING) {
            throw ApiException.conflict("考试尚未开始或已结束，当前状态: " + exam.getStatus());
        }
        if (now.isBefore(exam.getStartAt())) {
            throw ApiException.conflict("考试尚未开始");
        }
        if (!now.isBefore(exam.getEndAt())) {
            throw ApiException.conflict("考试已结束");
        }
        User student = userRepository.findById(principal.userId())
                .orElseThrow(() -> ApiException.unauthorized("账户不存在"));
        Instant personalDeadline = now.plus(Duration.ofMinutes(exam.getDurationMinutes()));
        Instant deadline = personalDeadline.isBefore(exam.getEndAt()) ? personalDeadline : exam.getEndAt();
        if (!deadline.isAfter(now)) {
            throw ApiException.conflict("考试窗口已不足，无法开始作答");
        }
        ExamRecord record = new ExamRecord();
        record.setExamId(examId);
        record.setStudentId(student.getId());
        record.setStartedAt(now);
        record.setDeadlineAt(deadline);
        record.setStatus(ExamRecordStatus.IN_PROGRESS);
        examRecordRepository.save(record);
        auditService.record(principal.userId(), "EXAM_ENTER", "exam_record", record.getId(), null);
        return response(exam, record, now);
    }

    /** 作答页数据：按试卷顺序返回题目与学生自己的草稿，不含隐藏测试点。 */
    @Transactional
    public ExamPaperView paper(Long examId, AuthPrincipal principal) {
        accessGuard.requireStudent(principal);
        Exam exam = examService.requireExam(examId);
        ExamRecord record = requireActiveRecord(exam, principal.userId());
        List<PaperItem> items = paperItemRepository.findByPaperVersionIdOrderByItemOrderAsc(exam.getPaperVersionId());
        List<com.example.service.api.dto.ProblemDtos.StudentProblemView> problems = items.stream()
                .map(item -> problemService.studentViewOfVersion(item.getProblemVersionId()))
                .toList();
        List<DraftView> drafts = answerDraftRepository.findByExamRecordIdOrderByProblemVersionIdAsc(record.getId())
                .stream().map(this::draftView).toList();
        return new ExamPaperView(record.getId(), exam.getId(), exam.getName(), record.getDeadlineAt(),
                remainingSeconds(record), record.getStatus(), exam.getStatus(), problems, drafts);
    }

    /** 保存草稿：仅进行中的记录可保存；baseVersion 用于乐观并发校验。 */
    @Transactional
    public DraftView saveDraft(Long examId, Long problemVersionId, SaveDraftRequest request,
                               AuthPrincipal principal) {
        accessGuard.requireStudent(principal);
        Instant now = clock.instant();
        Exam exam = examService.requireExam(examId);
        ExamRecord record = requireActiveRecord(exam, principal.userId());
        if (record.getStatus() != ExamRecordStatus.IN_PROGRESS) {
            throw ApiException.conflict("考试记录已结束，不能保存草稿");
        }
        if (exam.getStatus() == ExamStatus.SUSPENDED) {
            throw ApiException.conflict("考试已暂停，暂不可保存");
        }
        if (!now.isBefore(record.getDeadlineAt())) {
            throw ApiException.conflict("已超过个人作答截止时间");
        }
        paperItemRepository.findByPaperVersionIdAndProblemVersionId(exam.getPaperVersionId(), problemVersionId)
                .orElseThrow(() -> ApiException.badRequest("该题目不在本次试卷中"));
        problemService.studentViewOfVersion(problemVersionId);

        AnswerDraft draft = answerDraftRepository
                .findByExamRecordIdAndProblemVersionId(record.getId(), problemVersionId)
                .orElseGet(AnswerDraft::new);
        if (request.baseVersion() != null && draft.getId() != null
                && draft.getDraftVersion() != request.baseVersion()) {
            throw ApiException.conflict("草稿已被其他窗口更新，请刷新后重试");
        }
        String language = request.language() == null ? draft.getLanguage()
                : request.language().trim().toUpperCase(Locale.ROOT);
        ExamService.SubmissionRule rule = examService.submissionRule(exam);
        if (language != null && !rule.allowedLanguages().isEmpty()
                && rule.allowedLanguages().stream().noneMatch(l -> l.equalsIgnoreCase(language))) {
            throw ApiException.badRequest("该考试不允许使用语言: " + language);
        }
        String content = request.content() == null ? "" : request.content();
        if (content.length() > rule.maxCodeBytes()) {
            throw ApiException.badRequest("代码长度超过上限 " + rule.maxCodeBytes() + " 字节");
        }
        draft.setExamRecordId(record.getId());
        draft.setProblemVersionId(problemVersionId);
        draft.setLanguage(language);
        draft.setContent(content);
        draft.setSavedAt(now);
        answerDraftRepository.save(draft);
        return draftView(draft);
    }

    @Transactional(readOnly = true)
    public List<DraftView> drafts(Long examId, AuthPrincipal principal) {
        accessGuard.requireStudent(principal);
        examService.requireExam(examId);
        ExamRecord record = examRecordRepository.findByExamIdAndStudentId(examId, principal.userId())
                .orElseThrow(() -> ApiException.notFound("尚未进入该考试"));
        return answerDraftRepository.findByExamRecordIdOrderByProblemVersionIdAsc(record.getId())
                .stream().map(this::draftView).toList();
    }

    /** 提交整场考试：阻止后续作答并汇总当前得分。 */
    @Transactional
    public com.example.service.api.dto.ExamDtos.SubmitExamResponse submitExam(Long examId, AuthPrincipal principal) {
        accessGuard.requireStudent(principal);
        Instant now = clock.instant();
        examService.requireExam(examId);
        ExamRecord record = examRecordRepository.findByExamIdAndStudentId(examId, principal.userId())
                .orElseThrow(() -> ApiException.notFound("尚未进入该考试"));
        if (record.getStatus() == ExamRecordStatus.SUBMITTED) {
            return new com.example.service.api.dto.ExamDtos.SubmitExamResponse(record.getId(),
                    record.getTotalScore(), now, record.getStatus());
        }
        if (record.getStatus() == ExamRecordStatus.EXPIRED) {
            throw ApiException.conflict("已超过个人作答截止时间，考试已自动结束");
        }
        record.setStatus(ExamRecordStatus.SUBMITTED);
        examRecordRepository.save(record);
        auditService.record(principal.userId(), "EXAM_SUBMIT", "exam_record", record.getId(), null);
        return new com.example.service.api.dto.ExamDtos.SubmitExamResponse(record.getId(), record.getTotalScore(),
                now, record.getStatus());
    }

    /** 学生本人的考试记录详情（含每题得分）。 */
    @Transactional(readOnly = true)
    public ExamRecord requireOwnRecord(Long examRecordId, AuthPrincipal principal) {
        ExamRecord record = examRecordRepository.findById(examRecordId)
                .orElseThrow(() -> ApiException.notFound("考试记录不存在"));
        if (!record.getStudentId().equals(principal.userId()) && !principal.isStaff()) {
            throw ApiException.forbidden("无权查看他人的考试记录");
        }
        return record;
    }

    private ExamRecord requireActiveRecord(Exam exam, Long studentId) {
        ExamRecord record = examRecordRepository.findByExamIdAndStudentId(exam.getId(), studentId)
                .orElseThrow(() -> ApiException.conflict("请先进入考试"));
        if (record.getStatus() == ExamRecordStatus.IN_PROGRESS && !clock.instant().isBefore(record.getDeadlineAt())) {
            record.setStatus(ExamRecordStatus.EXPIRED);
            examRecordRepository.save(record);
            throw ApiException.conflict("已超过个人作答截止时间，考试已自动结束");
        }
        return record;
    }

    private EnterExamResponse response(Exam exam, ExamRecord record, Instant now) {
        return new EnterExamResponse(record.getId(), exam.getId(), exam.getName(), record.getStartedAt(),
                record.getDeadlineAt(), remainingSeconds(record), record.getStatus());
    }

    private long remainingSeconds(ExamRecord record) {
        long seconds = Duration.between(clock.instant(), record.getDeadlineAt()).getSeconds();
        return Math.max(0, seconds);
    }

    private DraftView draftView(AnswerDraft draft) {
        return new DraftView(draft.getId(), draft.getProblemVersionId(), draft.getLanguage(),
                draft.getContent(), draft.getDraftVersion(), draft.getSavedAt());
    }

    /** 统计某记录的提交次数（供规则校验复用）。 */
    @Transactional(readOnly = true)
    public long submissionCount(Long examRecordId, Long problemVersionId) {
        return submissionRepository.countByExamRecordIdAndProblemVersionId(examRecordId, problemVersionId);
    }

    /** 题目语言是否符合考试与题目配置（供提交校验复用）。 */
    static boolean languageAllowed(ExamService.SubmissionRule rule, List<String> problemLanguages, String language) {
        if (language == null) {
            return false;
        }
        boolean examOk = rule.allowedLanguages().isEmpty()
                || rule.allowedLanguages().stream().anyMatch(l -> l.equalsIgnoreCase(language));
        boolean problemOk = problemLanguages.isEmpty()
                || problemLanguages.stream().anyMatch(l -> l.equalsIgnoreCase(language));
        return examOk && problemOk;
    }
}
