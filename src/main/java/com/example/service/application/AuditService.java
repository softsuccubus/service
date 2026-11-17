package com.example.service.application;

import com.example.service.domain.AuditEvent;
import com.example.service.infrastructure.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 审计服务：记录发布、暂停/恢复/结束、人工改分、重试和人工复核等关键动作。 */
@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @Transactional
    public void record(Long actorUserId, String action, String targetType, Long targetId, String reason) {
        auditEventRepository.save(new AuditEvent(actorUserId, action, targetType, targetId, reason));
    }

    @Transactional
    public void recordChange(Long actorUserId, String action, String targetType, Long targetId,
                             String reason, String beforeJson, String afterJson) {
        AuditEvent event = new AuditEvent(actorUserId, action, targetType, targetId, reason);
        event.setBeforeJson(beforeJson);
        event.setAfterJson(afterJson);
        auditEventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> latest() {
        return auditEventRepository.findTop200ByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> forTarget(String targetType, Long targetId) {
        return auditEventRepository.findByTargetTypeAndTargetIdOrderByIdDesc(targetType, targetId);
    }
}
