package com.example.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 代码提交（submission）：不可变版本，提交时间由服务端生成（BR-04）。
 * 幂等键唯一，重复请求返回既有提交。
 */
@Entity
@Table(name = "submission",
        uniqueConstraints = @UniqueConstraint(name = "uk_submission_idempotency", columnNames = "idempotency_key"))
@Getter
@Setter
@NoArgsConstructor
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_record_id", nullable = false)
    private Long examRecordId;

    @Column(name = "problem_version_id", nullable = false)
    private Long problemVersionId;

    @Column(nullable = false, length = 32)
    private String language;

    @Lob
    @Column(name = "source_code", nullable = false, columnDefinition = "LONGTEXT")
    private String sourceCode;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "submit_no", nullable = false)
    private int submitNo;
}
