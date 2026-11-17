package com.example.service.application;

import com.example.service.api.dto.AdminDtos.JudgeConfigView;
import com.example.service.api.dto.AdminDtos.JudgeTaskView;
import com.example.service.api.support.ApiException;
import com.example.service.domain.JudgeTask;
import com.example.service.domain.JudgeTaskStatus;
import com.example.service.domain.OutboxEvent;
import com.example.service.infrastructure.JsonSupport;
import com.example.service.infrastructure.MessagePublisher;
import com.example.service.infrastructure.judge.JudgeOrchestrator;
import com.example.service.infrastructure.repository.JudgeTaskRepository;
import com.example.service.infrastructure.repository.OutboxEventRepository;
import com.example.service.infrastructure.security.AuthPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 评测运维（UC-12）：查询任务、重试失败任务、转人工复核。
 * 人工动作全部留痕，且不修改既有评测结果，避免影响已记分数据。
 */
@Service
public class JudgeDispatchService {

    private final JudgeTaskRepository judgeTaskRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final JudgeOrchestrator orchestrator;
    private final JsonSupport jsonSupport;
    private final MessagePublisher messagePublisher;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;
    private final String engineName;
    private final List<String> supportedLanguages;
    private final int maxAttempts;
    private final int leaseSeconds;
    private final int concurrency;
    private final boolean workerEnabled;

    public JudgeDispatchService(JudgeTaskRepository judgeTaskRepository,
                                OutboxEventRepository outboxEventRepository,
                                JudgeOrchestrator orchestrator,
                                JsonSupport jsonSupport,
                                MessagePublisher messagePublisher,
                                AccessGuard accessGuard,
                                AuditService auditService,
                                Clock clock,
                                @Value("${app.judge.engine:mock}") String engineName,
                                @Value("${app.judge.supported-languages:JAVA,CPP,PYTHON}") String languages,
                                @Value("${app.judge.worker.max-attempts:3}") int maxAttempts,
                                @Value("${app.judge.worker.lease-seconds:60}") int leaseSeconds,
                                @Value("${app.judge.worker.concurrency:2}") int concurrency,
                                @Value("${app.judge.worker.enabled:true}") boolean workerEnabled) {
        this.judgeTaskRepository = judgeTaskRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.orchestrator = orchestrator;
        this.jsonSupport = jsonSupport;
        this.messagePublisher = messagePublisher;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
        this.engineName = engineName;
        this.supportedLanguages = Arrays.stream(languages.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(s -> s.toUpperCase(Locale.ROOT)).toList();
        this.maxAttempts = maxAttempts;
        this.leaseSeconds = leaseSeconds;
        this.concurrency = concurrency;
        this.workerEnabled = workerEnabled;
    }

    @Transactional(readOnly = true)
    public List<JudgeTaskView> listTasks(JudgeTaskStatus status, AuthPrincipal principal) {
        accessGuard.requireAdmin(principal);
        List<JudgeTask> tasks = status == null
                ? judgeTaskRepository.findAll(Sort.by(Sort.Direction.DESC, "id"))
                : judgeTaskRepository.findByStatusOrderByQueuedAtAsc(status);
        return tasks.stream().map(this::view).toList();
    }

    /** 重试：MANUAL_REVIEW / FAILED / RETRYING 可重新进入队列。 */
    @Transactional
    public JudgeTaskView retry(Long taskId, AuthPrincipal principal) {
        accessGuard.requireAdmin(principal);
        JudgeTask task = judgeTaskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("评测任务不存在"));
        if (task.getStatus() == JudgeTaskStatus.RUNNING || task.getStatus() == JudgeTaskStatus.SUCCEEDED
                || task.getStatus() == JudgeTaskStatus.QUEUED) {
            throw ApiException.conflict("任务状态为 " + task.getStatus() + "，无需重试");
        }
        task.setStatus(JudgeTaskStatus.QUEUED);
        task.setLeaseUntil(null);
        task.setFinishedAt(null);
        task.setLastError(null);
        judgeTaskRepository.save(task);

        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("judge_task");
        event.setAggregateId(task.getId());
        event.setEventType("JUDGE_TASK_QUEUED");
        event.setPayloadJson(jsonSupport.write(Map.of("taskId", task.getId(),
                "submissionId", task.getSubmissionId())));
        event.setCreatedAt(clock.instant());
        outboxEventRepository.save(event);
        messagePublisher.publish(new MessagePublisher.JudgeTaskMessage(task.getId()));
        auditService.record(principal.userId(), "JUDGE_TASK_RETRY", "judge_task", taskId, null);
        return view(task);
    }

    /** 转人工复核：由管理员确认后标记，后续可人工改分。 */
    @Transactional
    public JudgeTaskView manualReview(Long taskId, String reason, AuthPrincipal principal) {
        accessGuard.requireAdmin(principal);
        orchestrator.markManualReview(taskId, principal.userId(), reason);
        JudgeTask task = judgeTaskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("评测任务不存在"));
        return view(task);
    }

    @Transactional(readOnly = true)
    public JudgeConfigView config(AuthPrincipal principal) {
        accessGuard.requireAdmin(principal);
        return new JudgeConfigView(engineName, supportedLanguages, maxAttempts, leaseSeconds,
                concurrency, workerEnabled);
    }

    private JudgeTaskView view(JudgeTask task) {
        return new JudgeTaskView(task.getId(), task.getSubmissionId(), task.getStatus(),
                task.getAttemptCount(), task.getLeaseUntil(), task.getLastError(), task.getQueuedAt(),
                task.getStartedAt(), task.getFinishedAt());
    }
}
