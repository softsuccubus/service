package com.example.service.infrastructure.repository;

import com.example.service.domain.TestCaseVersion;
import com.example.service.infrastructure.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TestCaseVersionRepository extends Repository<TestCaseVersion, Long> {

    Optional<TestCaseVersion> findFirstByTestCaseIdOrderByVersionNoDesc(Long testCaseId);

    List<TestCaseVersion> findByTestCaseIdOrderByVersionNoDesc(Long testCaseId);

    List<TestCaseVersion> findByIdInOrderByIdAsc(Collection<Long> ids);
}
