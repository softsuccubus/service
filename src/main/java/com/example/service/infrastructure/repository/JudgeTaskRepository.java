package com.example.service.infrastructure.repository;

import com.example.service.domain.JudgeTask;
import com.example.service.domain.JudgeTaskStatus;
import com.example.service.infrastructure.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JudgeTaskRepository extends Repository<JudgeTask, Long> {

    Optional<JudgeTask> findBySubmissionId(Long submissionId);

    List<JudgeTask> findByStatusOrderByQueuedAtAsc(JudgeTaskStatus status);

    long countByStatusIn(Collection<JudgeTaskStatus> statuses);

    @Query("""
            select t from JudgeTask t
            where t.status in :statuses and (t.leaseUntil is null or t.leaseUntil < :now)
            order by t.queuedAt asc
            """)
    List<JudgeTask> findClaimable(@Param("statuses") Collection<JudgeTaskStatus> statuses,
                                  @Param("now") Instant now);

    @Query("select t from JudgeTask t where t.status = :status and t.leaseUntil < :now")
    List<JudgeTask> findExpiredLeases(@Param("status") JudgeTaskStatus status, @Param("now") Instant now);

    /**
     * 条件更新领取任务：只有仍处于可领取状态且租约已过期时才能抢占，
     * 保证多个消费者并发时同一任务只被一个消费者执行。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update JudgeTask t
               set t.status = :running, t.leaseUntil = :leaseUntil,
                   t.attemptCount = t.attemptCount + 1, t.startedAt = coalesce(t.startedAt, :now)
             where t.id = :id
               and t.status in :claimable
               and (t.leaseUntil is null or t.leaseUntil < :now)
            """)
    int claim(@Param("id") Long id,
              @Param("claimable") Collection<JudgeTaskStatus> claimable,
              @Param("running") JudgeTaskStatus running,
              @Param("leaseUntil") Instant leaseUntil,
              @Param("now") Instant now);
}
