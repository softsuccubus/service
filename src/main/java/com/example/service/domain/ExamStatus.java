package com.example.service.domain;

/** 考试状态机（BR-01）。ENDED 之后禁止恢复作答。 */
public enum ExamStatus {
    DRAFT,
    SCHEDULED,
    RUNNING,
    SUSPENDED,
    ENDED,
    ARCHIVED
}
