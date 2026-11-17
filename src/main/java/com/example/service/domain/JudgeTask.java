package com.example.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 评判任务（judge_task）：一次提交唯一一个逻辑任务（uk_judge_task_submission），
 * 通过租约与幂等键实现至少一次投递下的安全消费（BR-05）。
 */
@Entity
@Table(name = "judge_task",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_judge_task_submission", columnNames = "submission_id"),
                @UniqueConstraint(name = "uk_judge_task_idempotency", columnNames = "idempotency_key")
        })
@Getter
@Setter
@NoArgsConstructor
public class JudgeTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JudgeTaskStatus status = JudgeTaskStatus.QUEUED;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    /** 失败原因，不得包含源代码或隐藏测试输入。 */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
