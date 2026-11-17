package com.example.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 审计事件（audit_event）：发布、授权、改分、重试、人工复核等关键动作留痕。 */
@Entity
@Table(name = "audit_event")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 系统操作可为空。 */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "target_type", nullable = false, length = 64)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(length = 1000)
    private String reason;

    @Lob
    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    @Lob
    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public AuditEvent(Long actorUserId, String action, String targetType, Long targetId, String reason) {
        this.actorUserId = actorUserId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reason = reason;
    }
}
