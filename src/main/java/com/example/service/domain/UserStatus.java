package com.example.service.domain;

/** 账户状态：锁定和禁用的账户不可访问任何受保护资源。 */
public enum UserStatus {
    ACTIVE,
    LOCKED,
    DISABLED
}
