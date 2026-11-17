package com.example.service.infrastructure.repository;

import com.example.service.domain.PaperVersion;
import com.example.service.infrastructure.Repository;

import java.util.List;
import java.util.Optional;

public interface PaperVersionRepository extends Repository<PaperVersion, Long> {

    List<PaperVersion> findByPaperIdOrderByVersionNoDesc(Long paperId);

    Optional<PaperVersion> findFirstByPaperIdOrderByVersionNoDesc(Long paperId);
}
