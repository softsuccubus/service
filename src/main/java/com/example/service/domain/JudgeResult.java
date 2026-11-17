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

import java.math.BigDecimal;
import java.time.Instant;

/** 评测结果（judge_result）：同任务同测试点唯一，保证重复消费不重复记分。 */
@Entity
@Table(name = "judge_result",
        uniqueConstraints = @UniqueConstraint(name = "uk_judge_result_case",
                columnNames = {"judge_task_id", "test_case_version_id"}))
@Getter
@Setter
@NoArgsConstructor
public class JudgeResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "judge_task_id", nullable = false)
    private Long judgeTaskId;

    @Column(name = "test_case_version_id", nullable = false)
    private Long testCaseVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Verdict verdict;

    @Column(name = "earned_score", nullable = false, precision = 8, scale = 2)
    private BigDecimal earnedScore = BigDecimal.ZERO;

    @Column(name = "time_used_ms")
    private Integer timeUsedMs;

    @Column(name = "memory_used_kb")
    private Long memoryUsedKb;

    /** 仅保存允许公开的摘要，不含隐藏输入。 */
    @Column(name = "detail_summary", length = 1000)
    private String detailSummary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
