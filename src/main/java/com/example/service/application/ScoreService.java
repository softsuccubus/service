package com.example.service.application;

import com.example.service.api.dto.ScoreDtos.ExamScoreView;
import com.example.service.api.dto.ScoreDtos.ManualScoreRequest;
import com.example.service.api.dto.ScoreDtos.MyScoreView;
import com.example.service.api.dto.ScoreDtos.ProblemScore;
import com.example.service.api.dto.ScoreDtos.ScoreRow;
import com.example.service.api.support.ApiException;
import com.example.service.domain.Exam;
import com.example.service.domain.ExamRecord;
import com.example.service.domain.ExamRecordStatus;
import com.example.service.domain.ProblemVersion;
import com.example.service.domain.Score;
import com.example.service.domain.Submission;
import com.example.service.domain.TestCaseVersion;
import com.example.service.domain.User;
import com.example.service.infrastructure.JsonSupport;
import com.example.service.infrastructure.repository.ExamRecordRepository;
import com.example.service.infrastructure.repository.ExamRepository;
import com.example.service.infrastructure.repository.JudgeResultRepository;
import com.example.service.infrastructure.repository.PaperItemRepository;
import com.example.service.infrastructure.repository.PaperVersionRepository;
import com.example.service.infrastructure.repository.ProblemVersionRepository;
import com.example.service.infrastructure.repository.ScoreRepository;
import com.example.service.infrastructure.repository.SubmissionRepository;
import com.example.service.infrastructure.repository.TestCaseVersionRepository;
import com.example.service.infrastructure.repository.UserRepository;
import com.example.service.infrastructure.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 成绩计算与查询：
 * 1. 每题取所有成功提交中的最高测试点得分，按测试点总分比例折算为题目得分并封顶（BR-07）；
 * 2. 人工改分优先于自动计算，且必须记录原因与操作人；
 * 3. 考试记录总分为各题得分之和。
 */
@Service
public class ScoreService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ScoreRepository scoreRepository;
    private final ExamRecordRepository examRecordRepository;
    private final ExamRepository examRepository;
    private final PaperItemRepository paperItemRepository;
    private final PaperVersionRepository paperVersionRepository;
    private final ProblemVersionRepository problemVersionRepository;
    private final TestCaseVersionRepository testCaseVersionRepository;
    private final JudgeResultRepository judgeResultRepository;
    private final UserRepository userRepository;
    private final JsonSupport jsonSupport;
    private final Clock clock;
    private final AccessGuard accessGuard;
    private final AuditService auditService;

    public ScoreService(ScoreRepository scoreRepository,
                        ExamRecordRepository examRecordRepository,
                        ExamRepository examRepository,
                        PaperItemRepository paperItemRepository,
                        PaperVersionRepository paperVersionRepository,
                        ProblemVersionRepository problemVersionRepository,
                        TestCaseVersionRepository testCaseVersionRepository,
                        JudgeResultRepository judgeResultRepository,
                        UserRepository userRepository,
                        JsonSupport jsonSupport,
                        Clock clock,
                        AccessGuard accessGuard,
                        AuditService auditService) {
        this.scoreRepository = scoreRepository;
        this.examRecordRepository = examRecordRepository;
        this.examRepository = examRepository;
        this.paperItemRepository = paperItemRepository;
        this.paperVersionRepository = paperVersionRepository;
        this.problemVersionRepository = problemVersionRepository;
        this.testCaseVersionRepository = testCaseVersionRepository;
        this.judgeResultRepository = judgeResultRepository;
        this.userRepository = userRepository;
        this.jsonSupport = jsonSupport;
        this.clock = clock;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
    }

    /** 评测成功或人工改分后重算某考试记录的某题得分。 */
    @Transactional
    public void recompute(Long examRecordId, Long problemVersionId) {
        ExamRecord record = examRecordRepository.findById(examRecordId)
                .orElseThrow(() -> ApiException.notFound("考试记录不存在"));
        Exam exam = examRepository.findById(record.getExamId())
                .orElseThrow(() -> ApiException.notFound("考试不存在"));
        var item = paperItemRepository
                .findByPaperVersionIdAndProblemVersionId(exam.getPaperVersionId(), problemVersionId)
                .orElse(null);
        if (item == null) {
            return;
        }
        Score existing = scoreRepository.findByExamRecordIdAndProblemVersionId(examRecordId, problemVersionId)
                .orElse(null);
        if (existing != null && existing.isManual()) {
            return;
        }

        BigDecimal possiblePoints = totalCasePoints(problemVersionId);
        BigDecimal bestEarned = bestEarnedPoints(examRecordId, problemVersionId);
        BigDecimal problemScore = possiblePoints.signum() > 0
                ? bestEarned.multiply(item.getScore()).divide(possiblePoints, 4, RoundingMode.HALF_UP)
                : ZERO;
        problemScore = clamp(problemScore, item.getScore());

        Score score = existing == null ? new Score() : existing;
        score.setExamRecordId(examRecordId);
        score.setProblemVersionId(problemVersionId);
        score.setScoreValue(scale(problemScore));
        score.setManual(false);
        score.setUpdatedBy(null);
        score.setUpdatedAt(clock.instant());
        scoreRepository.save(score);
        recomputeRecordTotal(examRecordId);
    }

    @Transactional
    public void recomputeRecordTotal(Long examRecordId) {
        ExamRecord record = examRecordRepository.findById(examRecordId)
                .orElseThrow(() -> ApiException.notFound("考试记录不存在"));
        BigDecimal total = scoreRepository.findByExamRecordId(examRecordId).stream()
                .map(Score::getScoreValue)
                .reduce(ZERO, BigDecimal::add);
        record.setTotalScore(scale(total));
        examRecordRepository.save(record);
    }

    @Transactional(readOnly = true)
    public ExamScoreView examScores(Long examId, AuthPrincipal principal) {
        Exam exam = requireExamAccess(examId, principal);
        List<ExamRecord> records = examRecordRepository.findByExamIdOrderByStartedAtAsc(examId);
        List<Long> recordIds = records.stream().map(ExamRecord::getId).toList();
        Map<Long, List<Score>> scoresByRecord = recordIds.isEmpty()
                ? Map.of()
                : scoreRepository.findByExamRecordIdIn(recordIds).stream()
                .collect(Collectors.groupingBy(Score::getExamRecordId));
        var items = paperItemRepository.findByPaperVersionIdOrderByItemOrderAsc(exam.getPaperVersionId());
        Map<Long, String> titles = problemTitles(items.stream().map(i -> i.getProblemVersionId()).toList());
        Map<Long, User> users = usersById(records.stream().map(ExamRecord::getStudentId).toList());

        List<ScoreRow> rows = new ArrayList<>();
        for (ExamRecord record : records) {
            User user = users.get(record.getStudentId());
            List<Score> recordScores = scoresByRecord.getOrDefault(record.getId(), List.of());
            List<ProblemScore> problemScores = items.stream().map(item -> {
                Score score = recordScores.stream()
                        .filter(s -> s.getProblemVersionId().equals(item.getProblemVersionId()))
                        .findFirst().orElse(null);
                return new ProblemScore(item.getProblemVersionId(),
                        titles.get(item.getProblemVersionId()),
                        score == null ? ZERO : score.getScoreValue(),
                        score != null && score.isManual());
            }).toList();
            rows.add(new ScoreRow(record.getId(), record.getStudentId(),
                    user == null ? null : user.getUsername(),
                    user == null ? null : user.getDisplayName(),
                    record.getStatus(), record.getTotalScore(), problemScores));
        }
        BigDecimal totalPossible = paperVersionRepository.findById(exam.getPaperVersionId())
                .map(v -> v.getTotalScore()).orElse(ZERO);
        return new ExamScoreView(examId, exam.getName(), totalPossible, clock.instant(), rows);
    }

    @Transactional(readOnly = true)
    public List<MyScoreView> myScores(AuthPrincipal principal) {
        accessGuard.requireStudent(principal);
        List<ExamRecord> records = examRecordRepository.findByStudentIdOrderByStartedAtDesc(principal.userId());
        List<MyScoreView> views = new ArrayList<>();
        for (ExamRecord record : records) {
            Exam exam = examRepository.findById(record.getExamId()).orElse(null);
            if (exam == null) {
                continue;
            }
            var items = paperItemRepository.findByPaperVersionIdOrderByItemOrderAsc(exam.getPaperVersionId());
            Map<Long, String> titles = problemTitles(items.stream().map(i -> i.getProblemVersionId()).toList());
            Map<Long, Score> scoreMap = scoreRepository.findByExamRecordId(record.getId()).stream()
                    .collect(Collectors.toMap(Score::getProblemVersionId, s -> s, (a, b) -> a));
            List<ProblemScore> problemScores = items.stream().map(item -> {
                Score score = scoreMap.get(item.getProblemVersionId());
                return new ProblemScore(item.getProblemVersionId(), titles.get(item.getProblemVersionId()),
                        score == null ? ZERO : score.getScoreValue(), score != null && score.isManual());
            }).toList();
            views.add(new MyScoreView(exam.getId(), exam.getName(), record.getId(), record.getStatus(),
                    record.getTotalScore(), record.getStartedAt(), record.getDeadlineAt(), problemScores));
        }
        return views;
    }

    /** 人工改分：仅考试负责人或管理员可操作，必须填写原因并留痕。 */
    @Transactional
    public ProblemScore manualAdjust(Long examId, Long examRecordId, Long problemVersionId,
                                     ManualScoreRequest request, AuthPrincipal principal) {
        Exam exam = requireExamAccess(examId, principal);
        ExamRecord record = examRecordRepository.findById(examRecordId)
                .orElseThrow(() -> ApiException.notFound("考试记录不存在"));
        if (!record.getExamId().equals(examId)) {
            throw ApiException.badRequest("考试记录不属于该考试");
        }
        var item = paperItemRepository
                .findByPaperVersionIdAndProblemVersionId(exam.getPaperVersionId(), problemVersionId)
                .orElseThrow(() -> ApiException.badRequest("该题目不在本次试卷中"));
        if (request.score().compareTo(item.getScore()) > 0) {
            throw ApiException.badRequest("分数不能超过该题满分 " + item.getScore());
        }
        Score score = scoreRepository.findByExamRecordIdAndProblemVersionId(examRecordId, problemVersionId)
                .orElseGet(Score::new);
        String before = score.getId() == null ? null : jsonSupport.write(Map.of(
                "score", score.getScoreValue(), "manual", score.isManual()));
        score.setExamRecordId(examRecordId);
        score.setProblemVersionId(problemVersionId);
        score.setScoreValue(scale(request.score()));
        score.setManual(true);
        score.setUpdatedBy(principal.userId());
        score.setUpdatedAt(clock.instant());
        scoreRepository.save(score);
        recomputeRecordTotal(examRecordId);
        String after = jsonSupport.write(Map.of(
                "score", score.getScoreValue(), "manual", true,
                "operatorUserId", principal.userId()));
        auditService.recordChange(principal.userId(), "SCORE_MANUAL_ADJUST", "score", score.getId(),
                request.reason(), before, after);
        ProblemVersion version = problemVersionRepository.findById(problemVersionId).orElse(null);
        return new ProblemScore(problemVersionId, version == null ? null : version.getTitle(),
                score.getScoreValue(), true);
    }

    /** 成绩导出为 CSV（UTF-8 BOM，便于 Excel 打开）。 */
    @Transactional(readOnly = true)
    public String exportCsv(Long examId, AuthPrincipal principal) {
        ExamScoreView view = examScores(examId, principal);
        Map<Long, String> titles = view.rows().isEmpty()
                ? Map.of()
                : view.rows().getFirst().problemScores().stream()
                .collect(Collectors.toMap(ProblemScore::problemVersionId,
                        p -> p.problemTitle() == null ? ("题目" + p.problemVersionId()) : p.problemTitle(),
                        (a, b) -> a, LinkedHashMap::new));
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("用户名,姓名,状态,总分");
        List<Long> problemIds = titles.keySet().stream().toList();
        for (Long id : problemIds) {
            csv.append(',').append(csvCell(titles.get(id)));
        }
        csv.append('\n');
        for (ScoreRow row : view.rows()) {
            csv.append(csvCell(row.studentUsername())).append(',')
                    .append(csvCell(row.studentDisplayName())).append(',')
                    .append(row.recordStatus()).append(',')
                    .append(row.totalScore().toPlainString());
            Map<Long, BigDecimal> map = row.problemScores().stream()
                    .collect(Collectors.toMap(ProblemScore::problemVersionId, ProblemScore::score, (a, b) -> a));
            for (Long id : problemIds) {
                csv.append(',').append(map.getOrDefault(id, ZERO).toPlainString());
            }
            csv.append('\n');
        }
        return csv.toString();
    }

    /** 当前学生可查看的考试记录，按考试分组便于前端展示。 */
    @Transactional(readOnly = true)
    public List<ExamRecord> recordsOfStudent(Long studentId) {
        return examRecordRepository.findByStudentIdOrderByStartedAtDesc(studentId);
    }

    @Transactional(readOnly = true)
    public ExamScoreView examScoresForSystem(Long examId) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> ApiException.notFound("考试不存在"));
        List<ExamRecord> records = examRecordRepository.findByExamIdOrderByStartedAtAsc(examId);
        List<Long> recordIds = records.stream().map(ExamRecord::getId).toList();
        Map<Long, List<Score>> scoresByRecord = recordIds.isEmpty()
                ? Map.of()
                : scoreRepository.findByExamRecordIdIn(recordIds).stream()
                .collect(Collectors.groupingBy(Score::getExamRecordId));
        var items = paperItemRepository.findByPaperVersionIdOrderByItemOrderAsc(exam.getPaperVersionId());
        Map<Long, String> titles = problemTitles(items.stream().map(i -> i.getProblemVersionId()).toList());
        Map<Long, User> users = usersById(records.stream().map(ExamRecord::getStudentId).toList());
        List<ScoreRow> rows = new ArrayList<>();
        for (ExamRecord record : records) {
            User user = users.get(record.getStudentId());
            List<Score> recordScores = scoresByRecord.getOrDefault(record.getId(), List.of());
            List<ProblemScore> problemScores = items.stream().map(item -> {
                Score score = recordScores.stream()
                        .filter(s -> s.getProblemVersionId().equals(item.getProblemVersionId()))
                        .findFirst().orElse(null);
                return new ProblemScore(item.getProblemVersionId(), titles.get(item.getProblemVersionId()),
                        score == null ? ZERO : score.getScoreValue(), score != null && score.isManual());
            }).toList();
            rows.add(new ScoreRow(record.getId(), record.getStudentId(),
                    user == null ? null : user.getUsername(),
                    user == null ? null : user.getDisplayName(),
                    record.getStatus(), record.getTotalScore(), problemScores));
        }
        BigDecimal totalPossible = paperVersionRepository.findById(exam.getPaperVersionId())
                .map(v -> v.getTotalScore()).orElse(ZERO);
        return new ExamScoreView(examId, exam.getName(), totalPossible, clock.instant(), rows);
    }

    private Exam requireExamAccess(Long examId, AuthPrincipal principal) {
        accessGuard.requireStaff(principal);
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> ApiException.notFound("考试不存在"));
        accessGuard.requireOwnerOrAdmin(principal, exam.getOwnerId(), "考试");
        return exam;
    }

    private BigDecimal totalCasePoints(Long problemVersionId) {
        ProblemVersion version = problemVersionRepository.findById(problemVersionId)
                .orElseThrow(() -> ApiException.notFound("题目版本不存在"));
        List<Long> ids = jsonSupport.readLongList(version.getTestCaseVersionIdsJson());
        if (ids.isEmpty()) {
            return ZERO;
        }
        List<TestCaseVersion> cases = testCaseVersionRepository.findByIdInOrderByIdAsc(ids);
        return cases.stream().map(TestCaseVersion::getPoints).reduce(ZERO, BigDecimal::add);
    }

    private BigDecimal bestEarnedPoints(Long examRecordId, Long problemVersionId) {
        List<Object[]> rows = judgeResultRepository.findEarnedPointsBySubmission(examRecordId, problemVersionId,
                com.example.service.domain.JudgeTaskStatus.SUCCEEDED);
        Map<Long, BigDecimal> perSubmission = new HashMap<>();
        for (Object[] row : rows) {
            Long submissionId = row[0] == null ? null : ((Number) row[0]).longValue();
            if (submissionId == null) {
                continue;
            }
            BigDecimal earned = row[1] == null ? ZERO : new BigDecimal(row[1].toString());
            perSubmission.merge(submissionId, earned, BigDecimal::add);
        }
        return perSubmission.values().stream().max(Comparator.naturalOrder()).orElse(ZERO);
    }

    private Map<Long, String> problemTitles(List<Long> problemVersionIds) {
        if (problemVersionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> titles = new HashMap<>();
        for (ProblemVersion version : problemVersionRepository.findAllById(problemVersionIds)) {
            titles.put(version.getId(), version.getTitle());
        }
        return titles;
    }

    private Map<Long, User> usersById(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    private BigDecimal clamp(BigDecimal value, BigDecimal max) {
        if (value.signum() < 0) {
            return ZERO;
        }
        return value.compareTo(max) > 0 ? max : value;
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
