package com.example.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** 成绩（score）：考试记录与题目维度唯一（BR-07），人工改分需审计。 */
@Entity
@Table(name = "score",
        uniqueConstraints = @UniqueConstraint(name = "uk_score_record_problem",
                columnNames = {"exam_record_id", "problem_version_id"}))
@Getter
@Setter
@NoArgsConstructor
public class Score {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_record_id", nullable = false)
    private Long examRecordId;

    @Column(name = "problem_version_id", nullable = false)
    private Long problemVersionId;

    @Column(name = "score_value", nullable = false, precision = 8, scale = 2)
    private BigDecimal scoreValue = BigDecimal.ZERO;

    @Column(name = "is_manual", nullable = false)
    private boolean manual = false;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
