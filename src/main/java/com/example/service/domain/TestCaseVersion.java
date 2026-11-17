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
 * 测试点版本（test_case_version）。
 * 输入与期望输出保存在受控存储中，本表只保存引用键，避免隐藏数据泄露。
 */
@Entity
@Table(name = "test_case_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_test_case_version", columnNames = {"test_case_id", "version_no"}))
@Getter
@Setter
@NoArgsConstructor
public class TestCaseVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_case_id", nullable = false)
    private Long testCaseId;

    @Column(name = "version_no", nullable = false)
    private int versionNo = 1;

    @Column(name = "input_ref", nullable = false, length = 512)
    private String inputRef;

    @Column(name = "expected_ref", nullable = false, length = 512)
    private String expectedRef;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal points = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Visibility visibility = Visibility.HIDDEN;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
