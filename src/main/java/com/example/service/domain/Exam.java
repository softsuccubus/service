package com.example.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 考试（exam）：绑定一个已发布试卷版本，保存时间窗、准入与提交规则。
 * 状态迁移在应用层校验并通过 row_version 乐观锁避免并发覆盖（BR-01）。
 */
@Entity
@Table(name = "exam")
@Getter
@Setter
@NoArgsConstructor
public class Exam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "paper_version_id", nullable = false)
    private Long paperVersionId;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    /** 准入规则 JSON：{"studentIds":[...]}；为空表示不限制具体名单。 */
    @Column(name = "admission_json", nullable = false, columnDefinition = "TEXT")
    private String admissionJson = "{}";

    /** 提交规则 JSON：{"maxSubmissionsPerProblem":3,"maxCodeBytes":65536,"allowedLanguages":[...]}。 */
    @Column(name = "submission_rule_json", nullable = false, columnDefinition = "TEXT")
    private String submissionRuleJson = "{}";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExamStatus status = ExamStatus.DRAFT;

    @Version
    @Column(name = "row_version", nullable = false)
    private int rowVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "ended_at")
    private Instant endedAt;
}
