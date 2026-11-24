package com.example.service.infrastructure.repository;

import com.example.service.domain.TestCase;
import com.example.service.infrastructure.Repository;

import java.util.List;

public interface TestCaseRepository extends Repository<TestCase, Long> {

    List<TestCase> findByProblemIdAndArchivedFalseOrderByIdAsc(Long problemId);

    /** 含已归档测试点：永久删除题目时用于清理。 */
    List<TestCase> findByProblemIdOrderByIdAsc(Long problemId);
}
