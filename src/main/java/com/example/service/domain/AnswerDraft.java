package com.example.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 答案草稿（answer_draft）：使用 draft_version 乐观锁避免旧请求覆盖新内容。 */
@Entity
@Table(name = "answer_draft",
        uniqueConstraints = @UniqueConstraint(name = "uk_answer_draft",
                columnNames = {"exam_record_id", "problem_version_id"}))
@Getter
@Setter
@NoArgsConstructor
public class AnswerDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_record_id", nullable = false)
    private Long examRecordId;

    @Column(name = "problem_version_id", nullable = false)
    private Long problemVersionId;

    @Column(length = 32)
    private String language;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Version
    @Column(name = "draft_version", nullable = false)
    private int draftVersion;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt = Instant.now();
}
