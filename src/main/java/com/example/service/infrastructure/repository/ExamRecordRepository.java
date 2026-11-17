package com.example.service.infrastructure.repository;

import com.example.service.domain.ExamRecord;
import com.example.service.domain.ExamRecordStatus;
import com.example.service.infrastructure.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExamRecordRepository extends Repository<ExamRecord, Long> {

    Optional<ExamRecord> findByExamIdAndStudentId(Long examId, Long studentId);

    List<ExamRecord> findByExamIdOrderByStartedAtAsc(Long examId);

    List<ExamRecord> findByStudentIdOrderByStartedAtDesc(Long studentId);

    long countByExamId(Long examId);

    long countByExamIdAndStatus(Long examId, ExamRecordStatus status);

    List<ExamRecord> findByStatusAndDeadlineAtBefore(ExamRecordStatus status, Instant now);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "update ExamRecord r set r.status = :to where r.status = :from and r.deadlineAt < :now")
    int expireOverdue(@org.springframework.data.repository.query.Param("from") ExamRecordStatus from,
                      @org.springframework.data.repository.query.Param("to") ExamRecordStatus to,
                      @org.springframework.data.repository.query.Param("now") Instant now);
}
