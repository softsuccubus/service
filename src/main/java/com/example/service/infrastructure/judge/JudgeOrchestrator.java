package com.example.service.infrastructure.judge;

import com.example.service.application.AuditService;
import com.example.service.api.support.ApiException;
import com.example.service.domain.JudgeResult;
import com.example.service.domain.JudgeTask;
import com.example.service.domain.JudgeTaskStatus;
import com.example.service.domain.ProblemVersion;
import com.example.service.domain.Submission;
import com.example.service.domain.TestCase;
import com.example.service.domain.TestCaseVersion;
import com.example.service.domain.Verdict;
import com.example.service.infrastructure.JsonSupport;
import com.example.service.infrastructure.repository.JudgeResultRepository;
import com.example.service.infrastructure.repository.JudgeTaskRepository;
import com.example.service.infrastructure.repository.ProblemVersionRepository;
import com.example.service.infrastructure.repository.SubmissionRepository;
import com.example.service.infrastructure.repository.TestCaseRepository;
import com.example.service.infrastructure.repository.TestCaseVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 评测任务事务编排：领取、加载快照、保存结果、失败重试/转人工复核。
 * 所有状态迁移都在数据库事务内完成；引擎调用本身不占用事务。
 */
@Service
public class JudgeOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(JudgeOrchestrator.class);

    /** 可被消费者领取的状态。 */
    public static final Collection<JudgeTaskStatus> CLAIMABLE =
            List.of(JudgeTaskStatus.QUEUED, JudgeTaskStatus.RETRYING);

    private final JudgeTaskRepository judgeTaskRepository;
    private final SubmissionRepository submissionRepository;
    private final ProblemVersionRepository problemVersionRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseVersionRepository testCaseVersionRepository;
    private final JudgeResultRepository judgeResultRepository;
    private final AuditService auditService;
    private final JsonSupport jsonSupport;
    private final Clock clock;
    private final int leaseSeconds;
    private final int maxAttempts;

    public JudgeOrchestrator(JudgeTaskRepository judgeTaskRepository,
                             SubmissionRepository submissionRepository,
                             ProblemVersionRepository problemVersionRepository,
                             TestCaseRepository testCaseRepository,
                             TestCaseVersionRepository testCaseVersionRepository,
                             JudgeResultRepository judgeResultRepository,
                             AuditService auditService,
                             JsonSupport jsonSupport,
                             Clock clock,
                             @Value("${app.judge.worker.lease-seconds:60}") int leaseSeconds,
                             @Value("${app.judge.worker.max-attempts:3}") int maxAttempts) {
        this.judgeTaskRepository = judgeTaskRepository;
        this.submissionRepository = submissionRepository;
        this.problemVersionRepository = problemVersionRepository;
        this.testCaseRepository = testCaseRepository;
        this.testCaseVersionRepository = testCaseVersionRepository;
        this.judgeResultRepository = judgeResultRepository;
        this.auditService = auditService;
        this.jsonSupport = jsonSupport;
        this.clock = clock;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
    }

    /** 条件领取任务；失败说明任务已被其他消费者处理或尚未到重试时间。 */
    @Transactional
    public boolean claim(Long taskId) {
        Instant now = clock.instant();
        return judgeTaskRepository.claim(taskId, CLAIMABLE, JudgeTaskStatus.RUNNING,
                now.plusSeconds(leaseSeconds), now) == 1;
    }

    /** 加载评测所需的题目快照与测试点列表（只读）。 */
    @Transactional(readOnly = true)
    public JudgeContext loadContext(Long taskId) {
        JudgeTask task = judgeTaskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("评测任务不存在"));
        Submission submission = submissionRepository.findById(task.getSubmissionId())
                .orElseThrow(() -> ApiException.notFound("提交不存在"));
        ProblemVersion version = problemVersionRepository.findById(submission.getProblemVersionId())
                .orElseThrow(() -> ApiException.notFound("题目版本不存在"));
        List<TestCaseVersion> caseVersions = resolveCaseVersions(version);
        Map<Long, String> caseNames = new HashMap<>();
        Set<Long> caseIds = new LinkedHashSet<>();
        for (TestCaseVersion cv : caseVersions) {
            caseIds.add(cv.getTestCaseId());
        }
        for (TestCase tc : testCaseRepository.findAllById(caseIds)) {
            caseNames.put(tc.getId(), tc.getCaseName());
        }
        List<JudgeModel.JudgeTestCase> testCases = new ArrayList<>();
        for (TestCaseVersion cv : caseVersions) {
            testCases.add(new JudgeModel.JudgeTestCase(cv.getId(), caseNames.get(cv.getTestCaseId()),
                    cv.getPoints(), cv.getVisibility(), cv.getInputRef(), cv.getExpectedRef()));
        }
        JudgeModel.JudgeRequest request = new JudgeModel.JudgeRequest(
                submission.getId(), version.getId(), submission.getLanguage(), submission.getSourceCode(),
                version.getTimeLimitMs(), version.getMemoryLimitMb(), testCases);
        return new JudgeContext(taskId, submission.getId(), submission.getExamRecordId(),
                submission.getProblemVersionId(), request);
    }

    /**
     * 保存评测结果并置任务为成功。先删除旧结果再写入，配合唯一约束保证重复消费幂等。
     */
    @Transactional
    public CompletedJudge complete(Long taskId, JudgeModel.JudgeOutcome outcome) {
        JudgeTask task = judgeTaskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("评测任务不存在"));
        Submission submission = submissionRepository.findById(task.getSubmissionId())
                .orElseThrow(() -> ApiException.notFound("提交不存在"));

        judgeResultRepository.deleteByJudgeTaskId(taskId);
        judgeResultRepository.flush();

        for (JudgeModel.JudgeCaseOutcome caseOutcome : outcome.caseOutcomes()) {
            JudgeResult result = new JudgeResult();
            result.setJudgeTaskId(taskId);
            result.setTestCaseVersionId(caseOutcome.testCaseVersionId());
            result.setVerdict(caseOutcome.verdict());
            result.setEarnedScore(caseOutcome.earnedScore());
            result.setTimeUsedMs(caseOutcome.timeUsedMs());
            result.setMemoryUsedKb(caseOutcome.memoryUsedKb());
            result.setDetailSummary(truncate(caseOutcome.detailSummary()));
            judgeResultRepository.save(result);
        }

        task.setStatus(JudgeTaskStatus.SUCCEEDED);
        task.setFinishedAt(clock.instant());
        task.setLeaseUntil(null);
        task.setLastError(null);
        judgeTaskRepository.save(task);
        log.info("评测完成 task={} submission={} verdict={} engine={}", taskId, submission.getId(),
                outcome.overallVerdict(), outcome.engineName());
        return new CompletedJudge(submission.getExamRecordId(), submission.getProblemVersionId(), outcome);
    }

    /** 记录失败：未达最大尝试次数则延迟重试，否则转人工复核。 */
    @Transactional
    public void fail(Long taskId, String reason) {
        JudgeTask task = judgeTaskRepository.findById(taskId).orElse(null);
        if (task == null || task.getStatus() == JudgeTaskStatus.SUCCEEDED
                || task.getStatus() == JudgeTaskStatus.MANUAL_REVIEW) {
            return;
        }
        Instant now = clock.instant();
        task.setLastError(truncate(reason));
        task.setLeaseUntil(null);
        task.setFinishedAt(null);
        if (task.getAttemptCount() >= maxAttempts) {
            task.setStatus(JudgeTaskStatus.MANUAL_REVIEW);
            task.setFinishedAt(now);
            auditService.record(null, "JUDGE_MANUAL_REVIEW", "judge_task", taskId,
                    "评测失败次数达到上限: " + task.getAttemptCount());
            log.warn("评测任务 {} 转入人工复核（尝试 {} 次）", taskId, task.getAttemptCount());
        } else {
            task.setStatus(JudgeTaskStatus.RETRYING);
            // 指数退避：5s、10s、20s……，通过租约时间实现延迟可领取。
            long backoff = 5L * (1L << Math.max(0, task.getAttemptCount() - 1));
            task.setLeaseUntil(now.plusSeconds(Math.min(backoff, 300)));
            log.warn("评测任务 {} 失败，{} 秒后重试: {}", taskId, backoff, task.getLastError());
        }
        judgeTaskRepository.save(task);
    }

    /** 租约过期的 RUNNING 任务重新排队（消费者崩溃恢复）。 */
    @Transactional
    public void recoverExpiredLeases() {
        List<JudgeTask> expired = judgeTaskRepository.findExpiredLeases(JudgeTaskStatus.RUNNING, clock.instant());
        for (JudgeTask task : expired) {
            task.setStatus(JudgeTaskStatus.RETRYING);
            task.setLeaseUntil(null);
            task.setLastError("租约过期，任务重新排队");
            judgeTaskRepository.save(task);
            log.warn("评测任务 {} 租约过期，重新排队", task.getId());
        }
    }

    /** 管理员将任务标记为人工复核。 */
    @Transactional
    public void markManualReview(Long taskId, Long actorUserId, String reason) {
        JudgeTask task = judgeTaskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("评测任务不存在"));
        task.setStatus(JudgeTaskStatus.MANUAL_REVIEW);
        task.setFinishedAt(clock.instant());
        task.setLeaseUntil(null);
        task.setLastError(truncate(reason));
        judgeTaskRepository.save(task);
        auditService.record(actorUserId, "JUDGE_MANUAL_REVIEW", "judge_task", taskId, reason);
    }

    @Transactional(readOnly = true)
    public Optional<JudgeTask> findTask(Long taskId) {
        return judgeTaskRepository.findById(taskId);
    }

    private List<TestCaseVersion> resolveCaseVersions(ProblemVersion version) {
        List<Long> frozenIds = jsonSupport.readLongList(version.getTestCaseVersionIdsJson());
        if (!frozenIds.isEmpty()) {
            return testCaseVersionRepository.findByIdInOrderByIdAsc(frozenIds);
        }
        // 兼容未冻结的历史数据：取每个测试点的最新版本。
        List<TestCase> cases = testCaseRepository.findByProblemIdAndArchivedFalseOrderByIdAsc(version.getProblemId());
        List<TestCaseVersion> versions = new ArrayList<>();
        for (TestCase testCase : cases) {
            testCaseVersionRepository.findFirstByTestCaseIdOrderByVersionNoDesc(testCase.getId())
                    .ifPresent(versions::add);
        }
        return versions;
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    public record JudgeContext(Long taskId,
                               Long submissionId,
                               Long examRecordId,
                               Long problemVersionId,
                               JudgeModel.JudgeRequest request) {
    }

    public record CompletedJudge(Long examRecordId, Long problemVersionId, JudgeModel.JudgeOutcome outcome) {
    }
}
