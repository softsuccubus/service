package com.example.service.infrastructure.repository;

import com.example.service.domain.Score;
import com.example.service.infrastructure.Repository;

import java.util.List;
import java.util.Optional;

public interface ScoreRepository extends Repository<Score, Long> {

    Optional<Score> findByExamRecordIdAndProblemVersionId(Long examRecordId, Long problemVersionId);

    List<Score> findByExamRecordId(Long examRecordId);

    List<Score> findByExamRecordIdIn(List<Long> examRecordIds);
}
