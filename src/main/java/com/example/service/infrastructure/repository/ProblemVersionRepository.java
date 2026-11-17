package com.example.service.infrastructure.repository;

import com.example.service.domain.ProblemVersion;
import com.example.service.domain.VersionStatus;
import com.example.service.infrastructure.Repository;

import java.util.List;
import java.util.Optional;

public interface ProblemVersionRepository extends Repository<ProblemVersion, Long> {

    List<ProblemVersion> findByProblemIdOrderByVersionNoDesc(Long problemId);

    Optional<ProblemVersion> findFirstByProblemIdOrderByVersionNoDesc(Long problemId);

    Optional<ProblemVersion> findByProblemIdAndVersionNo(Long problemId, int versionNo);

    Optional<ProblemVersion> findFirstByProblemIdAndStatusOrderByVersionNoDesc(Long problemId, VersionStatus status);
}
