package com.example.service.infrastructure.repository;

import com.example.service.domain.Paper;
import com.example.service.infrastructure.Repository;

import java.util.List;

public interface PaperRepository extends Repository<Paper, Long> {

    List<Paper> findAllByOrderByIdDesc();

    List<Paper> findByOwnerIdOrderByIdDesc(Long ownerId);
}
