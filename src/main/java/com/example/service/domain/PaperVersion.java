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

/** 试卷版本（paper_version）。发布后固定题目顺序、分值和所引用题目版本。 */
@Entity
@Table(name = "paper_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_paper_version", columnNames = {"paper_id", "version_no"}))
@Getter
@Setter
@NoArgsConstructor
public class PaperVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false)
    private Long paperId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "total_score", nullable = false, precision = 8, scale = 2)
    private BigDecimal totalScore = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaperVersionStatus status = PaperVersionStatus.DRAFT;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
