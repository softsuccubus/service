package com.example.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 试题版本（problem_version）。版本发布后不可变；
 * 已发布版本可被组卷/考试引用并作为快照参与评测。
 */
@Entity
@Table(name = "problem_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_problem_version", columnNames = {"problem_id", "version_no"}))
@Getter
@Setter
@NoArgsConstructor
public class ProblemVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "problem_id", nullable = false)
    private Long problemId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(nullable = false, length = 200)
    private String title;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String description;

    @Lob
    @Column(name = "input_desc", columnDefinition = "TEXT")
    private String inputDesc;

    @Lob
    @Column(name = "output_desc", columnDefinition = "TEXT")
    private String outputDesc;

    /** JSON 数组，例如 ["JAVA","CPP","PYTHON"]。 */
    @Column(name = "languages_json", nullable = false, columnDefinition = "TEXT")
    private String languagesJson;

    @Column(name = "time_limit_ms", nullable = false)
    private int timeLimitMs = 1000;

    @Column(name = "memory_limit_mb", nullable = false)
    private int memoryLimitMb = 256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VersionStatus status = VersionStatus.DRAFT;

    /** 发布时冻结的测试点版本 ID 列表（JSON 数组），保证考试题目快照与测试点一致（BR-03）。 */
    @Column(name = "test_case_version_ids_json", columnDefinition = "TEXT")
    private String testCaseVersionIdsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;
}
