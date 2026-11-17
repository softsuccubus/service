package com.example.service.infrastructure;

import com.example.service.api.support.ApiException;
import com.example.service.application.ScoreService;
import com.example.service.domain.JudgeTask;
import com.example.service.domain.JudgeTaskStatus;
import com.example.service.infrastructure.judge.InMemoryMessagePublisher;
import com.example.service.infrastructure.judge.JudgeEngine;
import com.example.service.infrastructure.judge.JudgeModel;
import com.example.service.infrastructure.judge.JudgeOrchestrator;
import com.example.service.infrastructure.repository.JudgeTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 评测消费者：
 * 1. 周期性从应用内队列与数据库取可领取任务，消息丢失时由数据库扫描兜底（至少一次）；
 * 2. 通过条件更新领取任务，多线程/多实例并发下同一任务只执行一次；
 * 3. 引擎调用在事务之外完成，结果写入走独立事务，再由成绩服务重算得分。
 */
@Component
public class JudgeWorker {

    private static final Logger log = LoggerFactory.getLogger(JudgeWorker.class);

    private final InMemoryMessagePublisher messagePublisher;
    private final JudgeTaskRepository judgeTaskRepository;
    private final JudgeOrchestrator orchestrator;
    private final JudgeEngine judgeEngine;
    private final ScoreService scoreService;
    private final Clock clock;
    private final boolean enabled;
    private final int concurrency;
    private final int batchSize;

    private final AtomicBoolean draining = new AtomicBoolean(false);

    public JudgeWorker(InMemoryMessagePublisher messagePublisher,
                       JudgeTaskRepository judgeTaskRepository,
                       JudgeOrchestrator orchestrator,
                       JudgeEngine judgeEngine,
                       ScoreService scoreService,
                       Clock clock,
                       @Value("${app.judge.worker.enabled:true}") boolean enabled,
                       @Value("${app.judge.worker.concurrency:2}") int concurrency,
                       @Value("${app.judge.worker.batch-size:10}") int batchSize) {
        this.messagePublisher = messagePublisher;
        this.judgeTaskRepository = judgeTaskRepository;
        this.orchestrator = orchestrator;
        this.judgeEngine = judgeEngine;
        this.scoreService = scoreService;
        this.clock = clock;
        this.enabled = enabled;
        this.concurrency = Math.max(1, concurrency);
        this.batchSize = Math.max(1, batchSize);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int concurrency() {
        return concurrency;
    }

    /** 主调度：先消费队列消息，再扫描数据库中可领取的任务。单实例内串行调度，避免并发重复领取。 */
    @Scheduled(fixedDelayString = "${app.judge.worker.poll-interval-ms:2000}")
    public void poll() {
        if (!enabled || !draining.compareAndSet(false, true)) {
            return;
        }
        try {
            MessagePublisher.JudgeTaskMessage message;
            while ((message = messagePublisher.poll()) != null) {
                execute(message.judgeTaskId());
            }
            List<JudgeTask> claimable = judgeTaskRepository.findClaimable(
                    JudgeOrchestrator.CLAIMABLE, clock.instant());
            for (JudgeTask task : claimable.stream().limit(batchSize).toList()) {
                execute(task.getId());
            }
        } catch (RuntimeException ex) {
            log.warn("评测任务消费异常: {}", ex.getMessage());
        } finally {
            draining.set(false);
        }
    }

    /** 租约恢复：消费者崩溃后遗留的 RUNNING 任务重新排队。 */
    @Scheduled(fixedDelayString = "${app.judge.worker.recovery-interval-ms:60000}")
    public void recover() {
        if (!enabled) {
            return;
        }
        try {
            orchestrator.recoverExpiredLeases();
        } catch (RuntimeException ex) {
            log.warn("评测租约恢复异常: {}", ex.getMessage());
        }
    }

    /** 执行单个任务：领取成功才继续，失败按策略重试或转人工复核。 */
    public boolean execute(Long taskId) {
        if (taskId == null || !orchestrator.claim(taskId)) {
            return false;
        }
        try {
            JudgeOrchestrator.JudgeContext context = orchestrator.loadContext(taskId);
            JudgeModel.JudgeOutcome outcome = judgeEngine.judge(context.request());
            JudgeOrchestrator.CompletedJudge completed = orchestrator.complete(taskId, outcome);
            scoreService.recompute(completed.examRecordId(), completed.problemVersionId());
            return true;
        } catch (ApiException ex) {
            orchestrator.fail(taskId, ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            log.warn("评测任务 {} 执行失败: {}", taskId, ex.toString());
            orchestrator.fail(taskId, "评测执行失败: " + ex.getClass().getSimpleName());
            return false;
        }
    }

    public long pendingCount() {
        return judgeTaskRepository.countByStatusIn(
                List.of(JudgeTaskStatus.QUEUED, JudgeTaskStatus.RUNNING, JudgeTaskStatus.RETRYING));
    }
}
