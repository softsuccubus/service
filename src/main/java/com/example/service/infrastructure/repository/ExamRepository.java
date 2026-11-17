package com.example.service.infrastructure.repository;

import com.example.service.domain.Exam;
import com.example.service.domain.ExamStatus;
import com.example.service.infrastructure.Repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExamRepository extends Repository<Exam, Long> {

    List<Exam> findAllByOrderByIdDesc();

    List<Exam> findByOwnerIdOrderByIdDesc(Long ownerId);

    List<Exam> findByStatusInOrderByStartAtAsc(Collection<ExamStatus> statuses);

    List<Exam> findByStatusIn(Collection<ExamStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Exam e where e.id = :id")
    Optional<Exam> findByIdForUpdate(@Param("id") Long id);

    @Query("select e from Exam e where e.status = :status and e.startAt <= :now")
    List<Exam> findDueToStart(@Param("status") ExamStatus status, @Param("now") Instant now);

    @Query("select e from Exam e where e.status = :status and e.endAt <= :now")
    List<Exam> findDueToEnd(@Param("status") ExamStatus status, @Param("now") Instant now);
}
