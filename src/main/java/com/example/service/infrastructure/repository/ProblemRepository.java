package com.example.service.infrastructure.repository;

import com.example.service.domain.Problem;
import com.example.service.infrastructure.Repository;

import java.util.List;

public interface ProblemRepository extends Repository<Problem, Long> {

    boolean existsByProblemCode(String problemCode);

    List<Problem> findByArchivedFalseOrderByIdDesc();

    List<Problem> findByOwnerIdAndArchivedFalseOrderByIdDesc(Long ownerId);

    /** 归档箱列表。 */
    List<Problem> findByArchivedTrueOrderByIdDesc();

    List<Problem> findByOwnerIdAndArchivedTrueOrderByIdDesc(Long ownerId);
}
