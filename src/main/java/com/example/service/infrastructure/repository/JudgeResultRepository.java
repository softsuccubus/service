package com.example.service.infrastructure.repository;

import com.example.service.domain.JudgeResult;
import com.example.service.domain.JudgeTaskStatus;
import com.example.service.infrastructure.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface JudgeResultRepository extends Repository<JudgeResult, Long> {

    List<JudgeResult> findByJudgeTaskIdOrderByIdAsc(Long judgeTaskId);

    void deleteByJudgeTaskId(Long judgeTaskId);

    /**
     * 返回某考试记录下某题所有成功提交的（提交ID，测试点得分）。
     * 用于计算该题最好成绩。
     */
    @Query("""
            select s.id, r.earnedScore
              from JudgeResult r, JudgeTask t, Submission s
             where r.judgeTaskId = t.id
               and t.submissionId = s.id
               and s.examRecordId = :examRecordId
               and s.problemVersionId = :problemVersionId
               and t.status = :status
            """)
    List<Object[]> findEarnedPointsBySubmission(@Param("examRecordId") Long examRecordId,
                                                @Param("problemVersionId") Long problemVersionId,
                                                @Param("status") JudgeTaskStatus status);
}
