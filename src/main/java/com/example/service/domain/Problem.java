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

import java.time.Instant;

/** 试题稳定身份（problem）。题面随版本变化，本表只保存身份与当前版本引用。 */
@Entity
@Table(name = "problem", uniqueConstraints = @UniqueConstraint(name = "uk_problem_code", columnNames = "problem_code"))
@Getter
@Setter
@NoArgsConstructor
public class Problem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "problem_code", nullable = false, length = 64)
    private String problemCode;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "current_version_id")
    private Long currentVersionId;

    @Column(nullable = false)
    private boolean archived = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
