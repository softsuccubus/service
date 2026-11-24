package com.example.service.infrastructure.repository;

import com.example.service.domain.Submission;
import com.example.service.infrastructure.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubmissionRepository extends Repository<Submission, Long> {

    Optional<Submission> findByIdempotencyKey(String idempotencyKey);

    /** 题目版本是否已有提交记录（永久删除前校验）。 */
    boolean existsByProblemVersionIdIn(Collection<Long> problemVersionIds);

    List<Submission> findByExamRecordIdOrderBySubmittedAtDesc(Long examRecordId);

    List<Submission> findByExamRecordIdAndProblemVersionIdOrderBySubmitNoDesc(Long examRecordId, Long problemVersionId);

    long countByExamRecordIdAndProblemVersionId(Long examRecordId, Long problemVersionId);

    long countByExamRecordIdIn(java.util.Collection<Long> examRecordIds);

    long countByExamRecordId(Long examRecordId);

    Optional<Submission> findFirstByExamRecordIdAndProblemVersionIdOrderBySubmitNoDesc(Long examRecordId,
                                                                                       Long problemVersionId);
}
