package com.example.service.presentation;

import com.example.service.api.dto.ExamDtos.StudentExamView;
import com.example.service.api.dto.ScoreDtos.MyScoreView;
import com.example.service.application.ExamService;
import com.example.service.application.ScoreService;
import com.example.service.infrastructure.security.AuthPrincipal;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 学生端视图组装：把考试列表、成绩与待办拼装为一个登录首页所需的聚合视图。
 * 学生只能看到与自己相关的数据，隐藏测试数据不会进入视图。
 */
@Component
public class StudentView {

    private final ExamService examService;
    private final ScoreService scoreService;

    public StudentView(ExamService examService, ScoreService scoreService) {
        this.examService = examService;
        this.scoreService = scoreService;
    }

    public Dashboard build(AuthPrincipal principal) {
        List<StudentExamView> exams = examService.studentExams(principal);
        List<MyScoreView> scores = scoreService.myScores(principal);
        long ongoing = exams.stream().filter(e -> e.entered()
                && e.recordStatus() == com.example.service.domain.ExamRecordStatus.IN_PROGRESS).count();
        return new Dashboard("STUDENT", principal.displayName(), Instant.now(), exams, scores,
                ongoing, exams.size());
    }

    public record Dashboard(String role, String displayName, Instant serverTime,
                            List<StudentExamView> exams, List<MyScoreView> scores,
                            long ongoingExams, long totalExams) {
    }
}
