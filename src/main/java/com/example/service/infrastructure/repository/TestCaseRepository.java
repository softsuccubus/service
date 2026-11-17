package com.example.service.infrastructure.repository;

import com.example.service.domain.TestCase;
import com.example.service.infrastructure.Repository;

import java.util.List;

public interface TestCaseRepository extends Repository<TestCase, Long> {

    List<TestCase> findByProblemIdAndArchivedFalseOrderByIdAsc(Long problemId);
}
