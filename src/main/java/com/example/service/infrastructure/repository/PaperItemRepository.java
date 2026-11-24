package com.example.service.infrastructure.repository;

import com.example.service.domain.PaperItem;
import com.example.service.infrastructure.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaperItemRepository extends Repository<PaperItem, Long> {

    List<PaperItem> findByPaperVersionIdOrderByItemOrderAsc(Long paperVersionId);

    Optional<PaperItem> findByPaperVersionIdAndProblemVersionId(Long paperVersionId, Long problemVersionId);

    /** 题目版本是否已被任何试卷引用（永久删除前校验）。 */
    boolean existsByProblemVersionIdIn(Collection<Long> problemVersionIds);

    void deleteByPaperVersionId(Long paperVersionId);
}
