package com.example.service.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * 仓储基接口（设计文档 infrastructure.Repository）。
 * 所有聚合的 Spring Data 仓储均继承该接口，统一获得标准 CRUD 能力。
 */
@NoRepositoryBean
public interface Repository<T, ID> extends JpaRepository<T, ID> {
}
