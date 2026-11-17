package com.example.service.infrastructure.repository;

import com.example.service.domain.AnswerDraft;
import com.example.service.infrastructure.Repository;

import java.util.List;
import java.util.Optional;

public interface AnswerDraftRepository extends Repository<AnswerDraft, Long> {

    Optional<AnswerDraft> findByExamRecordIdAndProblemVersionId(Long examRecordId, Long problemVersionId);

    List<AnswerDraft> findByExamRecordIdOrderByProblemVersionIdAsc(Long examRecordId);
}
