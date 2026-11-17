package com.example.service.application;

import com.example.service.api.dto.ExamDtos.CreateExamRequest;
import com.example.service.api.dto.ExamDtos.ExamMonitorView;
import com.example.service.api.dto.ExamDtos.ExamView;
import com.example.service.api.dto.ExamDtos.RecordSummaryView;
import com.example.service.api.dto.ExamDtos.StudentExamView;
import com.example.service.api.dto.ExamDtos.UpdateExamRequest;
import com.example.service.api.support.ApiException;
import com.example.service.domain.Exam;
import com.example.service.domain.ExamRecord;
import com.example.service.domain.ExamRecordStatus;
import com.example.service.domain.ExamStatus;
import com.example.service.domain.JudgeTaskStatus;
import com.example.service.domain.PaperVersion;
import com.example.service.domain.PaperVersionStatus;
import com.example.service.domain.User;
import com.example.service.infrastructure.JsonSupport;
import com.example.service.infrastructure.repository.ExamRecordRepository;
import com.example.service.infrastructure.repository.ExamRepository;
import com.example.service.infrastructure.repository.JudgeTaskRepository;
import com.example.service.infrastructure.repository.PaperRepository;
import com.example.service.infrastructure.repository.PaperVersionRepository;
import com.example.service.infrastructure.repository.SubmissionRepository;
import com.example.service.infrastructure.repository.UserRepository;
import com.example.service.infrastructure.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 考试生命周期与监控（UC-07/UC-08/UC-12）。
 *
 * <p>状态机（BR-01）：DRAFT → SCHEDULED → RUNNING → SUSPENDED/ENDED → ARCHIVED。
 * 暂停仅阻止进入与提交，不延长个人截止时间；结束为终态，不可恢复作答。</p>
 */
@Service
public class ExamService {

    /** 允许的状态迁移表。 */
    private static final Map<ExamStatus, Set<ExamStatus>> TRANSITIONS = Map.of(
            ExamStatus.DRAFT, Set.of(ExamStatus.SCHEDULED, ExamStatus.ARCHIVED),
            ExamStatus.SCHEDULED, Set.of(ExamStatus.RUNNING, ExamStatus.DRAFT, ExamStatus.ARCHIVED),
            ExamStatus.RUNNING, Set.of(ExamStatus.SUSPENDED, ExamStatus.ENDED),
            ExamStatus.SUSPENDED, Set.of(ExamStatus.RUNNING, ExamStatus.ENDED),
            ExamStatus.ENDED, Set.of(ExamStatus.ARCHIVED),
            ExamStatus.ARCHIVED, Set.of());

    private final ExamRepository examRepository;
    private final ExamRecordRepository examRecordRepository;
    private final PaperRepository paperRepository;
    private final PaperVersionRepository paperVersionRepository;
    private final SubmissionRepository submissionRepository;
    private final JudgeTaskRepository judgeTaskRepository;
    private final UserRepository userRepository;
    private final JsonSupport jsonSupport;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    public ExamService(ExamRepository examRepository,
                       ExamRecordRepository examRecordRepository,
                       PaperRepository paperRepository,
                       PaperVersionRepository paperVersionRepository,
                       SubmissionRepository submissionRepository,
                       JudgeTaskRepository judgeTaskRepository,
                       UserRepository userRepository,
                       JsonSupport jsonSupport,
                       AccessGuard accessGuard,
                       AuditService auditService,
                       Clock clock) {
        this.examRepository = examRepository;
        this.examRecordRepository = examRecordRepository;
        this.paperRepository = paperRepository;
        this.paperVersionRepository = paperVersionRepository;
        this.submissionRepository = submissionRepository;
        this.judgeTaskRepository = judgeTaskRepository;
        this.userRepository = userRepository;
        this.jsonSupport = jsonSupport;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public ExamView createExam(CreateExamRequest request, AuthPrincipal principal) {
        accessGuard.requireStaff(principal);
        validateWindow(request.startAt(), request.endAt(), request.durationMinutes());
        Exam exam = new Exam();
        exam.setOwnerId(principal.userId());
        exam.setCreatedAt(clock.instant());
        applyDefinition(exam, request.name(), request.paperVersionId(), request.startAt(), request.endAt(),
                request.durationMinutes(), request.admissionStudentIds(), request.allowedLanguages(),
                request.maxSubmissionsPerProblem(), request.maxCodeBytes());
        exam.setStatus(ExamStatus.DRAFT);
        examRepository.save(exam);
        auditService.record(principal.userId(), "EXAM_CREATE", "exam", exam.getId(), null);
        return view(exam);
    }

    @Transactional
    public ExamView updateExam(Long examId, UpdateExamRequest request, AuthPrincipal principal) {
        Exam exam = requireOwnedExam(examId, principal);
        if (exam.getStatus() != ExamStatus.DRAFT && exam.getStatus() != ExamStatus.SCHEDULED) {
            throw ApiException.conflict("只有草稿或已排期考试可以修改，当前状态: " + exam.getStatus());
        }
        validateWindow(request.startAt(), request.endAt(), request.durationMinutes());
        applyDefinition(exam, request.name(), request.paperVersionId(), request.startAt(), request.endAt(),
                request.durationMinutes(), request.admissionStudentIds(), request.allowedLanguages(),
                request.maxSubmissionsPerProblem(), request.maxCodeBytes());
        examRepository.save(exam);
        auditService.record(principal.userId(), "EXAM_UPDATE", "exam", examId, null);
        return view(exam);
    }

    /** 发布排期：DRAFT → SCHEDULED。 */
    @Transactional
    public ExamView publish(Long examId, AuthPrincipal principal) {
        Exam exam = requireOwnedExam(examId, principal);
        transition(exam, ExamStatus.SCHEDULED, principal.userId(), "发布考试");
        exam.setPublishedAt(clock.instant());
        examRepository.save(exam);
        return view(exam);
    }

    /** 开始考试：SCHEDULED → RUNNING（也可由定时任务在到达开始时间后自动触发）。 */
    @Transactional
    public ExamView start(Long examId, AuthPrincipal principal) {
        Exam exam = requireOwnedExam(examId, principal);
        transition(exam, ExamStatus.RUNNING, principal.userId(), "开始考试");
        examRepository.save(exam);
        return view(exam);
    }

    @Transactional
    public ExamView suspend(Long examId, AuthPrincipal principal) {
        Exam exam = requireOwnedExam(examId, principal);
        transition(exam, ExamStatus.SUSPENDED, principal.userId(), "暂停考试");
        examRepository.save(exam);
        return view(exam);
    }

    @Transactional
    public ExamView resume(Long examId, AuthPrincipal principal) {
        Exam exam = requireOwnedExam(examId, principal);
        transition(exam, ExamStatus.RUNNING, principal.userId(), "恢复考试");
        examRepository.save(exam);
        return view(exam);
    }

    /** 结束考试：同时把所有进行中的考试记录置为已提交，并锁定作答。 */
    @Transactional
    public ExamView end(Long examId, AuthPrincipal principal) {
        Exam exam = requireOwnedExam(examId, principal);
        transition(exam, ExamStatus.ENDED, principal.userId(), "结束考试");
        exam.setEndedAt(clock.instant());
        examRepository.save(exam);
        for (ExamRecord record : examRecordRepository.findByExamIdOrderByStartedAtAsc(examId)) {
            if (record.getStatus() == ExamRecordStatus.IN_PROGRESS) {
                record.setStatus(ExamRecordStatus.SUBMITTED);
                examRecordRepository.save(record);
            }
        }
        return view(exam);
    }

    @Transactional
    public ExamView archive(Long examId, AuthPrincipal principal) {
        Exam exam = requireOwnedExam(examId, principal);
        transition(exam, ExamStatus.ARCHIVED, principal.userId(), "归档考试");
        examRepository.save(exam);
        return view(exam);
    }

    @Transactional(readOnly = true)
    public List<ExamView> listExams(AuthPrincipal principal) {
        accessGuard.requireStaff(principal);
        List<Exam> exams = principal.isAdmin()
                ? examRepository.findAllByOrderByIdDesc()
                : examRepository.findByOwnerIdOrderByIdDesc(principal.userId());
        return exams.stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ExamView getExam(Long examId, AuthPrincipal principal) {
        return view(requireOwnedExam(examId, principal));
    }

    /** 学生可见的考试列表：只展示已排期/进行中/暂停/已结束的考试，并计算是否可进入。 */
    @Transactional(readOnly = true)
    public List<StudentExamView> studentExams(AuthPrincipal principal) {
        accessGuard.requireStudent(principal);
        List<Exam> exams = examRepository.findByStatusInOrderByStartAtAsc(
                List.of(ExamStatus.SCHEDULED, ExamStatus.RUNNING, ExamStatus.SUSPENDED, ExamStatus.ENDED));
        Instant now = clock.instant();
        List<StudentExamView> views = new ArrayList<>();
        for (Exam exam : exams) {
            ExamRecord record = examRecordRepository
                    .findByExamIdAndStudentId(exam.getId(), principal.userId()).orElse(null);
            boolean admitted = isAdmitted(exam, principal.userId());
            boolean canEnter = admitted && exam.getStatus() == ExamStatus.RUNNING
                    && (record != null || (now.isBefore(exam.getEndAt()) && !now.isBefore(exam.getStartAt())));
            views.add(new StudentExamView(exam.getId(), exam.getName(), exam.getStatus(),
                    exam.getStartAt(), exam.getEndAt(), exam.getDurationMinutes(), canEnter,
                    record != null, record == null ? null : record.getId(),
                    record == null ? null : record.getStatus(),
                    record == null ? null : record.getTotalScore(), now));
        }
        return views;
    }

    /** 教师监控（UC-12）：参与人数、状态分布与评测队列积压。 */
    @Transactional(readOnly = true)
    public ExamMonitorView monitor(Long examId, AuthPrincipal principal) {
        Exam exam = requireOwnedExam(examId, principal);
        List<ExamRecord> records = examRecordRepository.findByExamIdOrderByStartedAtAsc(examId);
        List<Long> recordIds = records.stream().map(ExamRecord::getId).toList();
        long submissions = recordIds.isEmpty() ? 0 : submissionRepository.countByExamRecordIdIn(recordIds);
        return new ExamMonitorView(examId, exam.getName(), exam.getStatus(), clock.instant(),
                records.size(),
                records.stream().filter(r -> r.getStatus() == ExamRecordStatus.IN_PROGRESS).count(),
                records.stream().filter(r -> r.getStatus() == ExamRecordStatus.SUBMITTED).count(),
                records.stream().filter(r -> r.getStatus() == ExamRecordStatus.EXPIRED).count(),
                submissions,
                judgeTaskRepository.countByStatusIn(List.of(JudgeTaskStatus.QUEUED)),
                judgeTaskRepository.countByStatusIn(List.of(JudgeTaskStatus.RUNNING)),
                judgeTaskRepository.countByStatusIn(List.of(JudgeTaskStatus.MANUAL_REVIEW)),
                exam.getEndedAt());
    }

    /** 参与学生列表（含姓名与状态）。 */
    @Transactional(readOnly = true)
    public List<RecordSummaryView> participants(Long examId, AuthPrincipal principal) {
        requireOwnedExam(examId, principal);
        List<ExamRecord> records = examRecordRepository.findByExamIdOrderByStartedAtAsc(examId);
        Map<Long, User> users = records.isEmpty() ? Map.of()
                : userRepository.findAllById(records.stream().map(ExamRecord::getStudentId).toList()).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        return records.stream().map(r -> {
            User user = users.get(r.getStudentId());
            return new RecordSummaryView(r.getId(), r.getStudentId(),
                    user == null ? null : user.getUsername(),
                    user == null ? null : user.getDisplayName(),
                    r.getStatus(), r.getStartedAt(), r.getDeadlineAt(), r.getTotalScore());
        }).toList();
    }

    /** 生命周期定时任务：SCHEDULED 到点开始、RUNNING 到点结束、超时记录标记过期。 */
    @Transactional
    public int runLifecycle() {
        Instant now = clock.instant();
        int changed = 0;
        for (Exam exam : examRepository.findDueToStart(ExamStatus.SCHEDULED, now)) {
            exam.setStatus(ExamStatus.RUNNING);
            examRepository.save(exam);
            auditService.record(null, "EXAM_AUTO_START", "exam", exam.getId(), "到达开始时间");
            changed++;
        }
        for (Exam exam : examRepository.findDueToEnd(ExamStatus.RUNNING, now)
                .stream().toList()) {
            exam.setStatus(ExamStatus.ENDED);
            exam.setEndedAt(now);
            examRepository.save(exam);
            auditService.record(null, "EXAM_AUTO_END", "exam", exam.getId(), "到达结束时间");
            changed++;
        }
        changed += examRecordRepository.expireOverdue(ExamRecordStatus.IN_PROGRESS,
                ExamRecordStatus.EXPIRED, now);
        return changed;
    }

    /**
     * 进入考试时使用：加锁读取并校验状态，避免并发下重复进入或状态被覆盖。
     */
    @Transactional
    public Exam lockForEntry(Long examId) {
        return examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> ApiException.notFound("考试不存在"));
    }

    @Transactional(readOnly = true)
    public Exam requireExam(Long examId) {
        return examRepository.findById(examId)
                .orElseThrow(() -> ApiException.notFound("考试不存在"));
    }

    public boolean isAdmitted(Exam exam, Long studentId) {
        Map<String, Object> admission = jsonSupport.readMap(exam.getAdmissionJson());
        Object ids = admission.get("studentIds");
        if (!(ids instanceof List<?> list) || list.isEmpty()) {
            return true;
        }
        return list.stream().anyMatch(v -> v instanceof Number n && n.longValue() == studentId);
    }

    /** 考试规则：最大提交次数、代码长度上限与允许语言。 */
    @Transactional(readOnly = true)
    public SubmissionRule submissionRule(Exam exam) {
        Map<String, Object> rule = jsonSupport.readMap(exam.getSubmissionRuleJson());
        int maxSubmissions = intValue(rule.get("maxSubmissionsPerProblem"), 5);
        int maxCodeBytes = intValue(rule.get("maxCodeBytes"), 65536);
        List<String> languages = rule.get("allowedLanguages") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        return new SubmissionRule(maxSubmissions, maxCodeBytes, languages);
    }

    private void applyDefinition(Exam exam, String name, Long paperVersionId, Instant startAt, Instant endAt,
                                 int durationMinutes, List<Long> admissionStudentIds,
                                 List<String> allowedLanguages, Integer maxSubmissionsPerProblem,
                                 Integer maxCodeBytes) {
        PaperVersion paperVersion = paperVersionRepository.findById(paperVersionId)
                .orElseThrow(() -> ApiException.notFound("试卷版本不存在"));
        if (paperVersion.getStatus() != PaperVersionStatus.PUBLISHED) {
            throw ApiException.badRequest("只能引用已发布的试卷版本");
        }
        exam.setName(name.trim());
        exam.setPaperVersionId(paperVersionId);
        exam.setStartAt(startAt);
        exam.setEndAt(endAt);
        exam.setDurationMinutes(durationMinutes);

        Map<String, Object> admission = new LinkedHashMap<>();
        admission.put("studentIds", admissionStudentIds == null ? List.of() : admissionStudentIds);
        exam.setAdmissionJson(jsonSupport.write(admission));

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("maxSubmissionsPerProblem", maxSubmissionsPerProblem == null ? 5 : maxSubmissionsPerProblem);
        rule.put("maxCodeBytes", maxCodeBytes == null ? 65536 : maxCodeBytes);
        rule.put("allowedLanguages", allowedLanguages == null ? List.of() : allowedLanguages);
        exam.setSubmissionRuleJson(jsonSupport.write(rule));
    }

    private void validateWindow(Instant startAt, Instant endAt, int durationMinutes) {
        if (startAt == null || endAt == null) {
            throw ApiException.badRequest("开始与结束时间不能为空");
        }
        if (!endAt.isAfter(startAt)) {
            throw ApiException.badRequest("结束时间必须晚于开始时间");
        }
        if (durationMinutes <= 0) {
            throw ApiException.badRequest("考试时长必须大于 0");
        }
        if (Duration.between(startAt, endAt).toMinutes() < durationMinutes) {
            throw ApiException.badRequest("考试窗口时长小于个人作答时长，请调整时间或时长");
        }
    }

    private void transition(Exam exam, ExamStatus target, Long actorUserId, String reason) {
        ExamStatus from = exam.getStatus();
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(target)) {
            throw ApiException.conflict("非法的状态迁移: " + from + " -> " + target);
        }
        exam.setStatus(target);
        auditService.recordChange(actorUserId, "EXAM_" + target.name(), "exam", exam.getId(), reason,
                "{\"status\":\"" + from + "\"}", "{\"status\":\"" + target + "\"}");
    }

    private Exam requireOwnedExam(Long examId, AuthPrincipal principal) {
        accessGuard.requireStaff(principal);
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> ApiException.notFound("考试不存在"));
        accessGuard.requireOwnerOrAdmin(principal, exam.getOwnerId(), "考试");
        return exam;
    }

    private ExamView view(Exam exam) {
        String paperName = paperVersionRepository.findById(exam.getPaperVersionId())
                .flatMap(pv -> paperRepository.findById(pv.getPaperId()))
                .map(p -> p.getName())
                .orElse(null);
        List<Long> admitted = jsonSupport.readMap(exam.getAdmissionJson()).get("studentIds") instanceof List<?> list
                ? list.stream().filter(Number.class::isInstance).map(v -> ((Number) v).longValue()).toList()
                : List.of();
        return new ExamView(exam.getId(), exam.getName(), exam.getOwnerId(), exam.getPaperVersionId(),
                paperName, exam.getStartAt(), exam.getEndAt(), exam.getDurationMinutes(), exam.getStatus(),
                admitted, jsonSupport.readMap(exam.getSubmissionRuleJson()), exam.getPublishedAt(),
                exam.getEndedAt(), exam.getCreatedAt(), examRecordRepository.countByExamId(exam.getId()),
                clock.instant());
    }

    private int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** 提交规则快照。 */
    public record SubmissionRule(int maxSubmissionsPerProblem, int maxCodeBytes, List<String> allowedLanguages) {
    }
}
