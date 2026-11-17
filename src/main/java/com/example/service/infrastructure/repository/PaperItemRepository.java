package com.example.service.infrastructure.repository;

import com.example.service.domain.PaperItem;
import com.example.service.infrastructure.Repository;

import java.util.List;
import java.util.Optional;

public interface PaperItemRepository extends Repository<PaperItem, Long> {

    List<PaperItem> findByPaperVersionIdOrderByItemOrderAsc(Long paperVersionId);

    Optional<PaperItem> findByPaperVersionIdAndProblemVersionId(Long paperVersionId, Long problemVersionId);

    void deleteByPaperVersionId(Long paperVersionId);
}
