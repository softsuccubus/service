package com.example.service;

import com.example.service.api.dto.AuthDtos.LoginRequest;
import com.example.service.api.dto.ExamDtos.CreateExamRequest;
import com.example.service.api.dto.ExamDtos.EnterExamResponse;
import com.example.service.api.dto.ExamDtos.SaveDraftRequest;
import com.example.service.api.dto.PaperDtos.CreatePaperRequest;
import com.example.service.api.dto.PaperDtos.PaperItemRequest;
import com.example.service.api.dto.PaperDtos.PaperView;
import com.example.service.api.dto.ProblemDtos.CreateProblemRequest;
import com.example.service.api.dto.ProblemDtos.CreateTestCaseRequest;
import com.example.service.api.dto.ProblemDtos.ProblemDetail;
import com.example.service.api.dto.ProblemDtos.UpdateProblemRequest;
import com.example.service.api.dto.ScoreDtos.ManualScoreRequest;
import com.example.service.api.dto.SubmissionDtos.SubmitRequest;
import com.example.service.api.dto.SubmissionDtos.SubmitResponse;
import com.example.service.api.support.ApiException;
import com.example.service.application.AuthService;
import com.example.service.application.ExamEntryService;
import com.example.service.application.ExamService;
import com.example.service.application.PaperService;
import com.example.service.application.ProblemService;
import com.example.service.application.ScoreService;
import com.example.service.application.SubmissionService;
import com.example.service.domain.ExamRecord;
import com.example.service.domain.ExamRecordStatus;
import com.example.service.domain.JudgeTaskStatus;
import com.example.service.domain.Role;
import com.example.service.domain.RoleCode;
import com.example.service.domain.User;
import com.example.service.domain.UserStatus;
import com.example.service.domain.Visibility;
import com.example.service.infrastructure.JudgeWorker;
import com.example.service.infrastructure.repository.ExamRecordRepository;
import com.example.service.infrastructure.repository.JudgeTaskRepository;
import com.example.service.infrastructure.repository.RoleRepository;
import com.example.service.infrastructure.repository.UserRepository;
import com.example.service.infrastructure.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端到端流程测试：登录 → 出题 → 组卷 → 排考 → 进入 → 草稿 → 提交 → 评测 → 得分 → 人工改分。
 * 使用 mock 评测引擎，不执行任何用户代码。
 */
@SpringBootTest
class PlatformFlowTest {

    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuthService authService;
    @Autowired
    private ProblemService problemService;
    @Autowired
    private PaperService paperService;
    @Autowired
    private ExamService examService;
    @Autowired
    private ExamEntryService examEntryService;
    @Autowired
    private SubmissionService submissionService;
    @Autowired
    private ScoreService scoreService;
    @Autowired
    private JudgeWorker judgeWorker;
    @Autowired
    private JudgeTaskRepository judgeTaskRepository;
    @Autowired
    private ExamRecordRepository examRecordRepository;
    @Autowired
    private Clock clock;

    private User teacher;
    private User student;
    private AuthPrincipal teacherPrincipal;
    private AuthPrincipal studentPrincipal;

    @BeforeEach
    void setUp() {
        teacher = ensureUser("t_" + System.nanoTime(), RoleCode.TEACHER);
        student = ensureUser("s_" + System.nanoTime(), RoleCode.STUDENT);
        teacherPrincipal = principalOf(teacher);
        studentPrincipal = principalOf(student);
    }

    @Test
    void fullExamFlow() {
        // 1. 出题与测试点
        ProblemDetail problem = problemService.createProblem(new CreateProblemRequest(null, "两数之和",
                "输出 a+b", "两个整数", "一个整数", List.of("JAVA"), 1000, 256), teacherPrincipal);
        Long problemId = problem.summary().id();
        problemService.addTestCase(problemId, new CreateTestCaseRequest("样例", "1 2", "3",
                new BigDecimal("30.00"), Visibility.PUBLIC), teacherPrincipal);
        problemService.addTestCase(problemId, new CreateTestCaseRequest("隐藏", "100 200", "300",
                new BigDecimal("70.00"), Visibility.HIDDEN), teacherPrincipal);
        ProblemDetail published = problemService.publish(problemId, null, teacherPrincipal);
        Long problemVersionId = published.summary().currentVersionId();
        assertThat(published.versions().getFirst().status().name()).isEqualTo("PUBLISHED");

        // 2. 组卷并发布
        PaperView paper = paperService.createPaper(new CreatePaperRequest("期中试卷",
                List.of(new PaperItemRequest(problemVersionId, new BigDecimal("100.00"), 1))), teacherPrincipal);
        Long paperVersionId = paper.versions().getFirst().id();
        paperService.publish(paper.id(), paperVersionId, teacherPrincipal);

        // 3. 排考并开始
        Instant now = clock.instant();
        var exam = examService.createExam(new CreateExamRequest("期中考试", paperVersionId,
                now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofHours(2)), 60,
                List.of(student.getId()), List.of("JAVA"), 5, 65536), teacherPrincipal);
        examService.publish(exam.id(), teacherPrincipal);
        examService.start(exam.id(), teacherPrincipal);

        // 4. 学生进入、保存草稿
        EnterExamResponse entered = examEntryService.enter(exam.id(), studentPrincipal);
        Long recordId = entered.recordId();
        examEntryService.saveDraft(exam.id(), problemVersionId,
                new SaveDraftRequest("JAVA", "// 草稿", null), studentPrincipal);

        // 5. 提交并评测
        SubmitResponse first = submissionService.submit(exam.id(),
                new SubmitRequest(problemVersionId, "JAVA", "// AC\nclass Main{}", "idem-1"), studentPrincipal);
        SubmitResponse replay = submissionService.submit(exam.id(),
                new SubmitRequest(problemVersionId, "JAVA", "// AC\nclass Main{}", "idem-1"), studentPrincipal);
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.submissionId()).isEqualTo(first.submissionId());

        assertThat(judgeWorker.execute(first.judgeTaskId())).isTrue();
        assertThat(judgeTaskRepository.findById(first.judgeTaskId()).orElseThrow().getStatus())
                .isEqualTo(JudgeTaskStatus.SUCCEEDED);

        ExamRecord record = examRecordRepository.findById(recordId).orElseThrow();
        assertThat(record.getTotalScore()).isEqualByComparingTo("100.00");

        // 6. WA 提交不会拉低最好成绩
        SubmitResponse second = submissionService.submit(exam.id(),
                new SubmitRequest(problemVersionId, "JAVA", "// WRONG_ANSWER", "idem-2"), studentPrincipal);
        judgeWorker.execute(second.judgeTaskId());
        ExamRecord afterWrongAnswer = examRecordRepository.findById(recordId).orElseThrow();
        assertThat(afterWrongAnswer.getTotalScore()).isEqualByComparingTo("100.00");

        // 7. 人工改分并审计
        scoreService.manualAdjust(exam.id(), recordId, problemVersionId,
                new ManualScoreRequest(new BigDecimal("90.00"), "成绩复核"), teacherPrincipal);
        assertThat(examRecordRepository.findById(recordId).orElseThrow().getTotalScore())
                .isEqualByComparingTo("90.00");

        // 8. 结束考试后禁止提交
        examService.end(exam.id(), teacherPrincipal);
        assertThatThrownBy(() -> submissionService.submit(exam.id(),
                new SubmitRequest(problemVersionId, "JAVA", "// AC", "idem-3"), studentPrincipal))
                .isInstanceOf(ApiException.class);
        assertThat(examRecordRepository.findById(recordId).orElseThrow().getStatus())
                .isEqualTo(ExamRecordStatus.SUBMITTED);
    }

    @Test
    void archiveRestoreAndDeleteProblem() {
        // 建题 + 测试点，便于验证归档只读与级联删除
        ProblemDetail created = problemService.createProblem(new CreateProblemRequest(null, "待归档题目",
                "描述", null, null, List.of("JAVA"), 1000, 256), teacherPrincipal);
        Long problemId = created.summary().id();
        problemService.addTestCase(problemId, new CreateTestCaseRequest("样例", "1", "1",
                new BigDecimal("100.00"), Visibility.PUBLIC), teacherPrincipal);

        // 归档后仍可查看详情，但不在在用列表、出现在归档箱
        problemService.archiveProblem(problemId, teacherPrincipal);
        assertThat(problemService.detail(problemId).summary().archived()).isTrue();
        assertThat(problemService.listProblems(teacherPrincipal, true, false))
                .noneMatch(p -> p.id().equals(problemId));
        assertThat(problemService.listProblems(teacherPrincipal, true, true))
                .anyMatch(p -> p.id().equals(problemId) && p.archived());

        // 已归档题目只读：不可修改、发布或增改测试点
        assertThatThrownBy(() -> problemService.updateProblem(problemId,
                new UpdateProblemRequest("新标题", "新描述",
                        null, null, List.of("JAVA"), 1000, 256), teacherPrincipal))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> problemService.publish(problemId, null, teacherPrincipal))
                .isInstanceOf(ApiException.class);

        // 恢复后重新可编辑并在用列表中
        problemService.restoreProblem(problemId, teacherPrincipal);
        assertThat(problemService.detail(problemId).summary().archived()).isFalse();
        assertThat(problemService.listProblems(teacherPrincipal, true, false))
                .anyMatch(p -> p.id().equals(problemId));

        // 未归档时不能删除；归档后可永久删除
        assertThatThrownBy(() -> problemService.deleteProblem(problemId, teacherPrincipal))
                .isInstanceOf(ApiException.class);
        problemService.archiveProblem(problemId, teacherPrincipal);
        problemService.deleteProblem(problemId, teacherPrincipal);
        assertThat(problemService.listProblems(teacherPrincipal, true, true))
                .noneMatch(p -> p.id().equals(problemId));
        assertThatThrownBy(() -> problemService.detail(problemId))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void cannotDeleteProblemReferencedByPaper() {
        ProblemDetail created = problemService.createProblem(new CreateProblemRequest(null, "已入卷题目",
                "描述", null, null, List.of("JAVA"), 1000, 256), teacherPrincipal);
        Long problemId = created.summary().id();
        problemService.addTestCase(problemId, new CreateTestCaseRequest("样例", "1", "1",
                new BigDecimal("100.00"), Visibility.PUBLIC), teacherPrincipal);
        ProblemDetail published = problemService.publish(problemId, null, teacherPrincipal);
        paperService.createPaper(new CreatePaperRequest("引用试卷",
                List.of(new PaperItemRequest(published.summary().currentVersionId(), new BigDecimal("100.00"), 1))),
                teacherPrincipal);

        problemService.archiveProblem(problemId, teacherPrincipal);
        assertThatThrownBy(() -> problemService.deleteProblem(problemId, teacherPrincipal))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void loginAndRoleChecks() {
        String username = "login_" + System.nanoTime();
        User user = ensureUser(username, RoleCode.STUDENT);
        var response = authService.login(new LoginRequest(username, "Password123"));
        assertThat(response.token()).isNotBlank();
        assertThat(response.user().id()).isEqualTo(user.getId());

        assertThatThrownBy(() -> authService.login(new LoginRequest(username, "wrong-password")))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> problemService.createProblem(new CreateProblemRequest(null, "标题",
                        "描述", null, null, List.of("JAVA"), 1000, 256), studentPrincipal))
                .isInstanceOf(ApiException.class);
    }

    private User ensureUser(String username, RoleCode roleCode) {
        Role role = roleRepository.findByCode(roleCode).orElseGet(() ->
                roleRepository.save(new Role(roleCode, roleCode.name(), "测试角色")));
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPasswordHash(passwordEncoder.encode("Password123"));
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(role));
        user.setCreatedAt(clock.instant());
        user.setUpdatedAt(clock.instant());
        return userRepository.save(user);
    }

    private AuthPrincipal principalOf(User user) {
        return new AuthPrincipal(user.getId(), user.getUsername(), user.getDisplayName(),
                Set.of(user.getRoles().iterator().next().getCode()));
    }
}
