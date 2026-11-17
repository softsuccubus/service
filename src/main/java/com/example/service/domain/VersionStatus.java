package com.example.service.domain;

/** 题目版本状态：只有 PUBLISHED 版本可被组卷引用。 */
public enum VersionStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
