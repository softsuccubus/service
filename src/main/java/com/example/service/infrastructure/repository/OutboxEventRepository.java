package com.example.service.infrastructure.repository;

import com.example.service.domain.OutboxEvent;
import com.example.service.infrastructure.Repository;

import java.util.List;

public interface OutboxEventRepository extends Repository<OutboxEvent, Long> {

    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByIdAsc();
}
