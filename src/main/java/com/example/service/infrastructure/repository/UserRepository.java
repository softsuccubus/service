package com.example.service.infrastructure.repository;

import com.example.service.domain.User;
import com.example.service.infrastructure.Repository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends Repository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    List<User> findAllByOrderByIdAsc();
}
