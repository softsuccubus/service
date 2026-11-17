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

/** 试卷题目项（paper_item）：固定题目顺序、分值与题目版本引用。 */
@Entity
@Table(name = "paper_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_paper_item_order", columnNames = {"paper_version_id", "item_order"}))
@Getter
@Setter
@NoArgsConstructor
public class PaperItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_version_id", nullable = false)
    private Long paperVersionId;

    @Column(name = "problem_version_id", nullable = false)
    private Long problemVersionId;

    @Column(name = "item_order", nullable = false)
    private int itemOrder;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal score = BigDecimal.ZERO;
}
