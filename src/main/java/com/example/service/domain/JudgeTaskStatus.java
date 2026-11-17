package com.example.service.domain;

/** 评测任务状态（BR-08）。终态变化必须保留时间和失败原因。 */
public enum JudgeTaskStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    RETRYING,
    MANUAL_REVIEW
}
