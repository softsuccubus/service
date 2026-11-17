package com.example.service.infrastructure;

import com.example.service.domain.Exam;
import com.example.service.domain.ExamStatus;
import com.example.service.domain.Paper;
import com.example.service.domain.PaperItem;
import com.example.service.domain.PaperVersion;
import com.example.service.domain.PaperVersionStatus;
import com.example.service.domain.Problem;
import com.example.service.domain.ProblemVersion;
import com.example.service.domain.Role;
import com.example.service.domain.RoleCode;
import com.example.service.domain.TestCase;
import com.example.service.domain.TestCaseVersion;
import com.example.service.domain.User;
import com.example.service.domain.UserStatus;
import com.example.service.domain.VersionStatus;
import com.example.service.domain.Visibility;
import com.example.service.infrastructure.judge.TestDataStorage;
import com.example.service.infrastructure.repository.ExamRepository;
import com.example.service.infrastructure.repository.PaperItemRepository;
import com.example.service.infrastructure.repository.PaperRepository;
import com.example.service.infrastructure.repository.PaperVersionRepository;
import com.example.service.infrastructure.repository.ProblemRepository;
import com.example.service.infrastructure.repository.ProblemVersionRepository;
import com.example.service.infrastructure.repository.RoleRepository;
import com.example.service.infrastructure.repository.TestCaseRepository;
import com.example.service.infrastructure.repository.TestCaseVersionRepository;
import com.example.service.infrastructure.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 初始化数据：内置角色与演示账号（幂等）。
 * 演示题库/试卷/考试仅在数据库为空时创建，方便首次启动即可走通完整流程。
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ProblemRepository problemRepository;
    private final ProblemVersionRepository problemVersionRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseVersionRepository testCaseVersionRepository;
    private final PaperRepository paperRepository;
    private final PaperVersionRepository paperVersionRepository;
    private final PaperItemRepository paperItemRepository;
    private final ExamRepository examRepository;
    private final TestDataStorage testDataStorage;
    private final JsonSupport jsonSupport;
    private final Clock clock;
    private final boolean enabled;
    private final String adminPassword;
    private final String teacherPassword;
    private final String studentPassword;

    public DataSeeder(RoleRepository roleRepository,
                      UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      ProblemRepository problemRepository,
                      ProblemVersionRepository problemVersionRepository,
                      TestCaseRepository testCaseRepository,
                      TestCaseVersionRepository testCaseVersionRepository,
                      PaperRepository paperRepository,
                      PaperVersionRepository paperVersionRepository,
                      PaperItemRepository paperItemRepository,
                      ExamRepository examRepository,
                      TestDataStorage testDataStorage,
                      JsonSupport jsonSupport,
                      Clock clock,
                      @Value("${app.seed.enabled:true}") boolean enabled,
                      @Value("${app.seed.admin-password:admin123456}") String adminPassword,
                      @Value("${app.seed.teacher-password:teacher123456}") String teacherPassword,
                      @Value("${app.seed.student-password:student123456}") String studentPassword) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.problemRepository = problemRepository;
        this.problemVersionRepository = problemVersionRepository;
        this.testCaseRepository = testCaseRepository;
        this.testCaseVersionRepository = testCaseVersionRepository;
        this.paperRepository = paperRepository;
        this.paperVersionRepository = paperVersionRepository;
        this.paperItemRepository = paperItemRepository;
        this.examRepository = examRepository;
        this.testDataStorage = testDataStorage;
        this.jsonSupport = jsonSupport;
        this.clock = clock;
        this.enabled = enabled;
        this.adminPassword = adminPassword;
        this.teacherPassword = teacherPassword;
        this.studentPassword = studentPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        Map<RoleCode, Role> roles = seedRoles();
        User admin = seedUser("admin", "系统管理员", adminPassword, roles, RoleCode.ADMIN);
        User teacher = seedUser("teacher", "演示教师", teacherPassword, roles, RoleCode.TEACHER);
        User student = seedUser("student", "演示学生", studentPassword, roles, RoleCode.STUDENT);
        seedDemoContent(teacher, List.of(student));
        log.info("初始化数据完成：账号 admin(id={}) / teacher(id={}) / student(id={})",
                admin.getId(), teacher.getId(), student.getId());
    }

    private Map<RoleCode, Role> seedRoles() {
        Map<RoleCode, Role> roles = new EnumMap<>(RoleCode.class);
        for (RoleCode code : RoleCode.values()) {
            Role role = roleRepository.findByCode(code).orElseGet(() -> roleRepository.save(
                    new Role(code, switch (code) {
                        case STUDENT -> "学生";
                        case TEACHER -> "教师";
                        case ADMIN -> "管理员";
                    }, "内置角色")));
            roles.put(code, role);
        }
        return roles;
    }

    private User seedUser(String username, String displayName, String rawPassword,
                          Map<RoleCode, Role> roles, RoleCode roleCode) {
        return userRepository.findByUsername(username).orElseGet(() -> {
            User user = new User();
            user.setUsername(username);
            user.setDisplayName(displayName);
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            user.setStatus(UserStatus.ACTIVE);
            user.setRoles(new HashSet<>(Set.of(roles.get(roleCode))));
            user.setCreatedAt(clock.instant());
            user.setUpdatedAt(clock.instant());
            return userRepository.save(user);
        });
    }

    /** 首次启动创建一道演示题、一份试卷与一场进行中的考试。 */
    private void seedDemoContent(User teacher, List<User> students) {
        if (problemRepository.existsByProblemCode("DEMO-001")) {
            return;
        }
        Instant now = clock.instant();

        Problem problem = new Problem();
        problem.setProblemCode("DEMO-001");
        problem.setOwnerId(teacher.getId());
        problem.setCreatedAt(now);
        problemRepository.save(problem);

        ProblemVersion version = new ProblemVersion();
        version.setProblemId(problem.getId());
        version.setVersionNo(1);
        version.setTitle("A+B Problem");
        version.setDescription("给定两个整数 a 和 b，输出它们的和。");
        version.setInputDesc("一行两个整数 a、b（-10^9 ≤ a,b ≤ 10^9）。");
        version.setOutputDesc("输出一个整数，表示 a + b。");
        version.setLanguagesJson(jsonSupport.write(List.of("JAVA", "CPP", "PYTHON")));
        version.setTimeLimitMs(1000);
        version.setMemoryLimitMb(256);
        version.setStatus(VersionStatus.DRAFT);
        version.setCreatedAt(now);
        problemVersionRepository.save(version);

        List<Long> caseVersionIds = new java.util.ArrayList<>();
        caseVersionIds.add(createTestCase(problem, "样例 1", "1 2", "3",
                new BigDecimal("20.00"), Visibility.PUBLIC));
        caseVersionIds.add(createTestCase(problem, "隐藏 1", "-5 8", "3",
                new BigDecimal("40.00"), Visibility.HIDDEN));
        caseVersionIds.add(createTestCase(problem, "隐藏 2", "1000000000 1000000000", "2000000000",
                new BigDecimal("40.00"), Visibility.HIDDEN));

        version.setTestCaseVersionIdsJson(jsonSupport.write(caseVersionIds));
        version.setStatus(VersionStatus.PUBLISHED);
        version.setPublishedAt(now);
        problemVersionRepository.save(version);
        problem.setCurrentVersionId(version.getId());
        problemRepository.save(problem);

        Paper paper = new Paper();
        paper.setName("演示试卷");
        paper.setOwnerId(teacher.getId());
        paper.setCreatedAt(now);
        paperRepository.save(paper);

        PaperVersion paperVersion = new PaperVersion();
        paperVersion.setPaperId(paper.getId());
        paperVersion.setVersionNo(1);
        paperVersion.setTotalScore(new BigDecimal("100.00"));
        paperVersion.setStatus(PaperVersionStatus.PUBLISHED);
        paperVersion.setCreatedAt(now);
        paperVersionRepository.save(paperVersion);

        PaperItem item = new PaperItem();
        item.setPaperVersionId(paperVersion.getId());
        item.setProblemVersionId(version.getId());
        item.setItemOrder(1);
        item.setScore(new BigDecimal("100.00"));
        paperItemRepository.save(item);

        paper.setCurrentVersionId(paperVersion.getId());
        paperRepository.save(paper);

        Exam exam = new Exam();
        exam.setName("演示考试（A+B）");
        exam.setPaperVersionId(paperVersion.getId());
        exam.setOwnerId(teacher.getId());
        exam.setStartAt(now.minus(Duration.ofMinutes(5)));
        exam.setEndAt(now.plus(Duration.ofDays(7)));
        exam.setDurationMinutes(120);
        exam.setAdmissionJson(jsonSupport.write(Map.of("studentIds",
                students.stream().map(User::getId).toList())));
        exam.setSubmissionRuleJson(jsonSupport.write(Map.of(
                "maxSubmissionsPerProblem", 10,
                "maxCodeBytes", 65536,
                "allowedLanguages", List.of("JAVA", "CPP", "PYTHON"))));
        exam.setStatus(ExamStatus.RUNNING);
        exam.setCreatedAt(now);
        exam.setPublishedAt(now);
        examRepository.save(exam);
        log.info("已创建演示题目/试卷/考试，考试 ID={}", exam.getId());
    }

    private Long createTestCase(Problem problem, String name, String input, String output,
                                BigDecimal points, Visibility visibility) {
        TestCase testCase = new TestCase();
        testCase.setProblemId(problem.getId());
        testCase.setCaseName(name);
        testCase.setCreatedAt(clock.instant());
        testCaseRepository.save(testCase);

        TestCaseVersion version = new TestCaseVersion();
        version.setTestCaseId(testCase.getId());
        version.setVersionNo(1);
        version.setPoints(points);
        version.setVisibility(visibility);
        version.setCreatedAt(clock.instant());
        String prefix = "problem-" + problem.getId() + "/case-" + testCase.getId() + "/v1";
        version.setInputRef(testDataStorage.write(prefix + ".in", input));
        version.setExpectedRef(testDataStorage.write(prefix + ".out", output));
        testCaseVersionRepository.save(version);
        return version.getId();
    }
}
