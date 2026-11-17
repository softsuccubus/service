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

/**
 * 考试记录（exam_record）：同一学生同一考试唯一（uk_exam_record_student）。
 * 截止时间取考试结束时间与个人时长截止时间的较早者（BR-02）。
 */
@Entity
@Table(name = "exam_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_exam_record_student", columnNames = {"exam_id", "student_id"}))
@Getter
@Setter
@NoArgsConstructor
public class ExamRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExamRecordStatus status = ExamRecordStatus.IN_PROGRESS;

    @Column(name = "total_score", nullable = false, precision = 8, scale = 2)
    private BigDecimal totalScore = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
