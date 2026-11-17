package com.example.service.infrastructure.repository;

import com.example.service.domain.AuditEvent;
import com.example.service.infrastructure.Repository;

import java.util.List;

public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    List<AuditEvent> findTop200ByOrderByIdDesc();

    List<AuditEvent> findByTargetTypeAndTargetIdOrderByIdDesc(String targetType, Long targetId);
}
