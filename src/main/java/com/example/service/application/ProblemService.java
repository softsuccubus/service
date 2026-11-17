package com.example.service.application;

import com.example.service.api.dto.ProblemDtos.CreateProblemRequest;
import com.example.service.api.dto.ProblemDtos.CreateTestCaseRequest;
import com.example.service.api.dto.ProblemDtos.ProblemDetail;
import com.example.service.api.dto.ProblemDtos.ProblemSummary;
import com.example.service.api.dto.ProblemDtos.SampleView;
import com.example.service.api.dto.ProblemDtos.StudentProblemView;
import com.example.service.api.dto.ProblemDtos.TestCaseView;
import com.example.service.api.dto.ProblemDtos.UpdateProblemRequest;
import com.example.service.api.dto.ProblemDtos.VersionView;
import com.example.service.api.support.ApiException;
import com.example.service.domain.Problem;
import com.example.service.domain.ProblemVersion;
import com.example.service.domain.TestCase;
import com.example.service.domain.TestCaseVersion;
import com.example.service.domain.VersionStatus;
import com.example.service.domain.Visibility;
import com.example.service.infrastructure.JsonSupport;
import com.example.service.infrastructure.judge.TestDataStorage;
import com.example.service.infrastructure.repository.ProblemRepository;
import com.example.service.infrastructure.repository.ProblemVersionRepository;
import com.example.service.infrastructure.repository.TestCaseRepository;
import com.example.service.infrastructure.repository.TestCaseVersionRepository;
import com.example.service.infrastructure.security.AuthPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 题库与测试点管理（UC-04/UC-05）。
 * 题目内容以版本保存；发布后版本不可变，并在发布时冻结测试点版本，保证评测结果可复现。
 */
@Service
public class ProblemService {

    private final ProblemRepository problemRepository;
    private final ProblemVersionRepository problemVersionRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseVersionRepository testCaseVersionRepository;
    private final TestDataStorage testDataStorage;
    private final JsonSupport jsonSupport;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;
    private final Set<String> supportedLanguages;

    public ProblemService(ProblemRepository problemRepository,
                          ProblemVersionRepository problemVersionRepository,
                          TestCaseRepository testCaseRepository,
                          TestCaseVersionRepository testCaseVersionRepository,
                          TestDataStorage testDataStorage,
                          JsonSupport jsonSupport,
                          AccessGuard accessGuard,
                          AuditService auditService,
                          Clock clock,
                          @Value("${app.judge.supported-languages:JAVA,CPP,PYTHON}") String languages) {
        this.problemRepository = problemRepository;
        this.problemVersionRepository = problemVersionRepository;
        this.testCaseRepository = testCaseRepository;
        this.testCaseVersionRepository = testCaseVersionRepository;
        this.testDataStorage = testDataStorage;
        this.jsonSupport = jsonSupport;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
        this.supportedLanguages = new LinkedHashSet<>(Arrays.stream(languages.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(s -> s.toUpperCase(Locale.ROOT)).toList());
    }

    @Transactional
    public ProblemDetail createProblem(CreateProblemRequest request, AuthPrincipal principal) {
        accessGuard.requireStaff(principal);
        String code = request.problemCode() == null || request.problemCode().isBlank()
                ? "P-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT)
                : request.problemCode().trim();
        if (problemRepository.existsByProblemCode(code)) {
            throw ApiException.conflict("题目编号已存在: " + code);
        }
        Problem problem = new Problem();
        problem.setProblemCode(code);
        problem.setOwnerId(principal.userId());
        problem.setCreatedAt(clock.instant());
        problemRepository.save(problem);

        ProblemVersion version = new ProblemVersion();
        version.setProblemId(problem.getId());
        version.setVersionNo(1);
        applyContent(version, request.title(), request.description(), request.inputDesc(), request.outputDesc(),
                request.languages(), request.timeLimitMs(), request.memoryLimitMb());
        version.setStatus(VersionStatus.DRAFT);
        version.setCreatedAt(clock.instant());
        problemVersionRepository.save(version);

        problem.setCurrentVersionId(version.getId());
        problemRepository.save(problem);
        auditService.record(principal.userId(), "PROBLEM_CREATE", "problem", problem.getId(), null);
        return detail(problem.getId());
    }

    /** 修改题目：草稿版本直接修改；已发布则自动创建新草稿版本（旧版本保持不可变）。 */
    @Transactional
    public ProblemDetail updateProblem(Long problemId, UpdateProblemRequest request, AuthPrincipal principal) {
        Problem problem = requireProblem(problemId);
        accessGuard.requireOwnerOrAdmin(principal, problem.getOwnerId(), "题目");
        List<ProblemVersion> versions = problemVersionRepository.findByProblemIdOrderByVersionNoDesc(problemId);
        ProblemVersion latest = versions.isEmpty() ? null : versions.getFirst();
        ProblemVersion target;
        if (latest != null && latest.getStatus() == VersionStatus.DRAFT) {
            target = latest;
        } else {
            target = new ProblemVersion();
            target.setProblemId(problemId);
            target.setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1);
            target.setStatus(VersionStatus.DRAFT);
            target.setCreatedAt(clock.instant());
        }
        applyContent(target, request.title(), request.description(), request.inputDesc(), request.outputDesc(),
                request.languages(), request.timeLimitMs(), request.memoryLimitMb());
        problemVersionRepository.save(target);
        auditService.record(principal.userId(), "PROBLEM_UPDATE", "problem", problemId, null);
        return detail(problemId);
    }

    /** 发布指定版本（默认最新草稿）：冻结测试点版本快照，并更新题目当前版本。 */
    @Transactional
    public ProblemDetail publish(Long problemId, Long versionId, AuthPrincipal principal) {
        Problem problem = requireProblem(problemId);
        accessGuard.requireOwnerOrAdmin(principal, problem.getOwnerId(), "题目");
        ProblemVersion version = versionId == null
                ? problemVersionRepository.findFirstByProblemIdOrderByVersionNoDesc(problemId)
                .orElseThrow(() -> ApiException.badRequest("题目没有可发布的版本"))
                : problemVersionRepository.findById(versionId)
                .orElseThrow(() -> ApiException.notFound("题目版本不存在"));
        if (!version.getProblemId().equals(problemId)) {
            throw ApiException.badRequest("版本不属于该题目");
        }
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw ApiException.conflict("只有草稿版本可以发布");
        }
        List<TestCase> cases = testCaseRepository.findByProblemIdAndArchivedFalseOrderByIdAsc(problemId);
        List<Long> caseVersionIds = new ArrayList<>();
        for (TestCase testCase : cases) {
            testCaseVersionRepository.findFirstByTestCaseIdOrderByVersionNoDesc(testCase.getId())
                    .ifPresent(cv -> caseVersionIds.add(cv.getId()));
        }
        if (caseVersionIds.isEmpty()) {
            throw ApiException.badRequest("发布前至少需要一个测试点");
        }
        version.setTestCaseVersionIdsJson(jsonSupport.write(caseVersionIds));
        version.setStatus(VersionStatus.PUBLISHED);
        version.setPublishedAt(clock.instant());
        problemVersionRepository.save(version);

        problem.setCurrentVersionId(version.getId());
        problemRepository.save(problem);
        auditService.record(principal.userId(), "PROBLEM_PUBLISH", "problem_version", version.getId(),
                "发布版本 v" + version.getVersionNo());
        return detail(problemId);
    }

    @Transactional
    public void archiveProblem(Long problemId, AuthPrincipal principal) {
        Problem problem = requireProblem(problemId);
        accessGuard.requireOwnerOrAdmin(principal, problem.getOwnerId(), "题目");
        problem.setArchived(true);
        problemRepository.save(problem);
        auditService.record(principal.userId(), "PROBLEM_ARCHIVE", "problem", problemId, null);
    }

    @Transactional(readOnly = true)
    public List<ProblemSummary> listProblems(AuthPrincipal principal, boolean onlyMine) {
        accessGuard.requireStaff(principal);
        List<Problem> problems = onlyMine && !principal.isAdmin()
                ? problemRepository.findByOwnerIdAndArchivedFalseOrderByIdDesc(principal.userId())
                : problemRepository.findByArchivedFalseOrderByIdDesc();
        return problems.stream().map(this::summary).toList();
    }

    /** 学生可见的已发布题目列表（按题目当前发布版本）。 */
    @Transactional(readOnly = true)
    public List<StudentProblemView> listPublishedProblems() {
        List<StudentProblemView> views = new ArrayList<>();
        for (Problem problem : problemRepository.findByArchivedFalseOrderByIdDesc()) {
            ProblemVersion version = publishedVersion(problem);
            if (version != null) {
                views.add(studentView(problem, version));
            }
        }
        return views;
    }

    @Transactional(readOnly = true)
    public ProblemDetail detail(Long problemId) {
        Problem problem = requireProblem(problemId);
        ProblemSummary summary = summary(problem);
        List<VersionView> versions = problemVersionRepository.findByProblemIdOrderByVersionNoDesc(problemId)
                .stream().map(this::versionView).toList();
        List<TestCaseView> testCases = new ArrayList<>();
        for (TestCase testCase : testCaseRepository.findByProblemIdAndArchivedFalseOrderByIdAsc(problemId)) {
            testCaseVersionRepository.findFirstByTestCaseIdOrderByVersionNoDesc(testCase.getId())
                    .ifPresent(cv -> testCases.add(testCaseView(testCase, cv)));
        }
        return new ProblemDetail(summary, versions, testCases);
    }

    @Transactional(readOnly = true)
    public StudentProblemView studentProblem(Long problemId) {
        Problem problem = requireProblem(problemId);
        ProblemVersion version = publishedVersion(problem);
        if (version == null) {
            throw ApiException.notFound("题目尚未发布");
        }
        return studentView(problem, version);
    }

    @Transactional
    public TestCaseView addTestCase(Long problemId, CreateTestCaseRequest request, AuthPrincipal principal) {
        Problem problem = requireProblem(problemId);
        accessGuard.requireOwnerOrAdmin(principal, problem.getOwnerId(), "题目");
        TestCase testCase = new TestCase();
        testCase.setProblemId(problemId);
        testCase.setCaseName(request.caseName().trim());
        testCase.setCreatedAt(clock.instant());
        testCaseRepository.save(testCase);

        TestCaseVersion version = new TestCaseVersion();
        version.setTestCaseId(testCase.getId());
        version.setVersionNo(1);
        version.setPoints(request.points());
        version.setVisibility(request.visibility() == null ? Visibility.HIDDEN : request.visibility());
        version.setCreatedAt(clock.instant());
        String prefix = "problem-" + problemId + "/case-" + testCase.getId() + "/v1";
        version.setInputRef(testDataStorage.write(prefix + ".in", request.input()));
        version.setExpectedRef(testDataStorage.write(prefix + ".out", request.expectedOutput()));
        testCaseVersionRepository.save(version);
        auditService.record(principal.userId(), "TEST_CASE_CREATE", "test_case", testCase.getId(), null);
        return testCaseView(testCase, version);
    }

    /** 修改测试点生成新版本；已发布题目版本仍引用冻结的旧测试点版本。 */
    @Transactional
    public TestCaseView updateTestCase(Long problemId, Long testCaseId, CreateTestCaseRequest request,
                                       AuthPrincipal principal) {
        Problem problem = requireProblem(problemId);
        accessGuard.requireOwnerOrAdmin(principal, problem.getOwnerId(), "题目");
        TestCase testCase = testCaseRepository.findById(testCaseId)
                .filter(tc -> tc.getProblemId().equals(problemId))
                .orElseThrow(() -> ApiException.notFound("测试点不存在"));
        int versionNo = testCaseVersionRepository.findFirstByTestCaseIdOrderByVersionNoDesc(testCaseId)
                .map(v -> v.getVersionNo() + 1).orElse(1);
        TestCaseVersion version = new TestCaseVersion();
        version.setTestCaseId(testCaseId);
        version.setVersionNo(versionNo);
        version.setPoints(request.points());
        version.setVisibility(request.visibility() == null ? Visibility.HIDDEN : request.visibility());
        version.setCreatedAt(clock.instant());
        String prefix = "problem-" + problemId + "/case-" + testCaseId + "/v" + versionNo;
        version.setInputRef(testDataStorage.write(prefix + ".in", request.input()));
        version.setExpectedRef(testDataStorage.write(prefix + ".out", request.expectedOutput()));
        testCaseVersionRepository.save(version);
        testCase.setCaseName(request.caseName().trim());
        testCaseRepository.save(testCase);
        auditService.record(principal.userId(), "TEST_CASE_UPDATE", "test_case", testCaseId,
                "生成版本 v" + versionNo);
        return testCaseView(testCase, version);
    }

    @Transactional
    public void archiveTestCase(Long problemId, Long testCaseId, AuthPrincipal principal) {
        Problem problem = requireProblem(problemId);
        accessGuard.requireOwnerOrAdmin(principal, problem.getOwnerId(), "题目");
        TestCase testCase = testCaseRepository.findById(testCaseId)
                .filter(tc -> tc.getProblemId().equals(problemId))
                .orElseThrow(() -> ApiException.notFound("测试点不存在"));
        testCase.setArchived(true);
        testCaseRepository.save(testCase);
        auditService.record(principal.userId(), "TEST_CASE_ARCHIVE", "test_case", testCaseId, null);
    }

    /** 供考试作答页使用：按冻结快照返回学生可见题目（不含隐藏测试点）。 */
    @Transactional(readOnly = true)
    public StudentProblemView studentViewOfVersion(Long problemVersionId) {
        ProblemVersion version = problemVersionRepository.findById(problemVersionId)
                .orElseThrow(() -> ApiException.notFound("题目版本不存在"));
        Problem problem = requireProblem(version.getProblemId());
        return studentView(problem, version);
    }

    public Set<String> supportedLanguages() {
        return Set.copyOf(supportedLanguages);
    }

    private void applyContent(ProblemVersion version, String title, String description, String inputDesc,
                              String outputDesc, List<String> languages, Integer timeLimitMs,
                              Integer memoryLimitMb) {
        if (title == null || title.isBlank()) {
            throw ApiException.badRequest("题目标题不能为空");
        }
        if (languages == null || languages.isEmpty()) {
            throw ApiException.badRequest("至少选择一种允许语言");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String language : languages) {
            String code = language == null ? "" : language.trim().toUpperCase(Locale.ROOT);
            if (!supportedLanguages.contains(code)) {
                throw ApiException.badRequest("不支持的编程语言: " + language
                        + "，可选: " + String.join(",", supportedLanguages));
            }
            normalized.add(code);
        }
        version.setTitle(title.trim());
        version.setDescription(description);
        version.setInputDesc(inputDesc);
        version.setOutputDesc(outputDesc);
        version.setLanguagesJson(jsonSupport.write(normalized));
        version.setTimeLimitMs(timeLimitMs == null ? 1000 : timeLimitMs);
        version.setMemoryLimitMb(memoryLimitMb == null ? 256 : memoryLimitMb);
    }

    private Problem requireProblem(Long problemId) {
        return problemRepository.findById(problemId)
                .orElseThrow(() -> ApiException.notFound("题目不存在"));
    }

    private ProblemVersion publishedVersion(Problem problem) {
        if (problem.getCurrentVersionId() != null) {
            ProblemVersion current = problemVersionRepository.findById(problem.getCurrentVersionId()).orElse(null);
            if (current != null && current.getStatus() == VersionStatus.PUBLISHED) {
                return current;
            }
        }
        return problemVersionRepository
                .findFirstByProblemIdAndStatusOrderByVersionNoDesc(problem.getId(), VersionStatus.PUBLISHED)
                .orElse(null);
    }

    private ProblemSummary summary(Problem problem) {
        List<ProblemVersion> versions = problemVersionRepository.findByProblemIdOrderByVersionNoDesc(problem.getId());
        ProblemVersion latest = versions.isEmpty() ? null : versions.getFirst();
        Integer currentVersionNo = problem.getCurrentVersionId() == null ? null
                : versions.stream().filter(v -> v.getId().equals(problem.getCurrentVersionId()))
                .map(ProblemVersion::getVersionNo).findFirst().orElse(null);
        return new ProblemSummary(problem.getId(), problem.getProblemCode(), problem.getOwnerId(),
                problem.getCurrentVersionId(), currentVersionNo,
                latest == null ? null : latest.getVersionNo(),
                latest == null ? null : latest.getStatus(),
                problem.isArchived(), problem.getCreatedAt());
    }

    private VersionView versionView(ProblemVersion version) {
        return new VersionView(version.getId(), version.getProblemId(), version.getVersionNo(),
                version.getTitle(), version.getDescription(), version.getInputDesc(), version.getOutputDesc(),
                jsonSupport.readStringList(version.getLanguagesJson()), version.getTimeLimitMs(),
                version.getMemoryLimitMb(), version.getStatus(), version.getPublishedAt(),
                samples(version));
    }

    private TestCaseView testCaseView(TestCase testCase, TestCaseVersion version) {
        return new TestCaseView(version.getId(), testCase.getId(), version.getVersionNo(),
                testCase.getCaseName(), testDataStorage.read(version.getInputRef()),
                testDataStorage.read(version.getExpectedRef()), version.getPoints(), version.getVisibility());
    }

    private StudentProblemView studentView(Problem problem, ProblemVersion version) {
        List<String> languages = jsonSupport.readStringList(version.getLanguagesJson());
        return new StudentProblemView(problem.getId(), version.getId(), version.getVersionNo(),
                version.getTitle(), version.getDescription(), version.getInputDesc(), version.getOutputDesc(),
                languages, version.getTimeLimitMs(), version.getMemoryLimitMb(), samples(version));
    }

    /** 只返回 PUBLIC 测试点作为样例。 */
    private List<SampleView> samples(ProblemVersion version) {
        List<Long> ids = jsonSupport.readLongList(version.getTestCaseVersionIdsJson());
        if (ids.isEmpty()) {
            return List.of();
        }
        List<SampleView> samples = new ArrayList<>();
        Map<Long, String> names = new HashMap<>();
        for (TestCaseVersion cv : testCaseVersionRepository.findByIdInOrderByIdAsc(ids)) {
            if (cv.getVisibility() != Visibility.PUBLIC) {
                continue;
            }
            String name = names.computeIfAbsent(cv.getTestCaseId(), id -> testCaseRepository.findById(id)
                    .map(TestCase::getCaseName).orElse("样例"));
            samples.add(new SampleView(name, testDataStorage.read(cv.getInputRef()),
                    testDataStorage.read(cv.getExpectedRef())));
        }
        return samples;
    }
}
