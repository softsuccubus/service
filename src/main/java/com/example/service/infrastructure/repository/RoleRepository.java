package com.example.service.infrastructure.repository;

import com.example.service.domain.Role;
import com.example.service.domain.RoleCode;
import com.example.service.infrastructure.Repository;

import java.util.Optional;

public interface RoleRepository extends Repository<Role, Long> {

    Optional<Role> findByCode(RoleCode code);
}
