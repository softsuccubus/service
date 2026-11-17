package com.example.service.presentation;

import com.example.service.api.dto.ExamDtos.ExamView;
import com.example.service.api.dto.PaperDtos.PaperView;
import com.example.service.api.dto.ProblemDtos.ProblemSummary;
import com.example.service.application.ExamService;
import com.example.service.application.PaperService;
import com.example.service.application.ProblemService;
import com.example.service.infrastructure.security.AuthPrincipal;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** 教师端视图组装：题库、试卷与考试的待办/在办概览。 */
@Component
public class TeacherView {

    private final ProblemService problemService;
    private final PaperService paperService;
    private final ExamService examService;

    public TeacherView(ProblemService problemService, PaperService paperService, ExamService examService) {
        this.problemService = problemService;
        this.paperService = paperService;
        this.examService = examService;
    }

    public Dashboard build(AuthPrincipal principal) {
        List<ProblemSummary> problems = problemService.listProblems(principal, true);
        List<PaperView> papers = paperService.listPapers(principal);
        List<ExamView> exams = examService.listExams(principal);
        long running = exams.stream()
                .filter(e -> e.status() == com.example.service.domain.ExamStatus.RUNNING).count();
        long scheduled = exams.stream()
                .filter(e -> e.status() == com.example.service.domain.ExamStatus.SCHEDULED).count();
        long drafts = problems.stream()
                .filter(p -> p.latestVersionStatus() == com.example.service.domain.VersionStatus.DRAFT).count();
        return new Dashboard("TEACHER", principal.displayName(), Instant.now(),
                problems, papers, exams, drafts, scheduled, running);
    }

    public record Dashboard(String role, String displayName, Instant serverTime,
                            List<ProblemSummary> problems, List<PaperView> papers,
                            List<ExamView> exams, long draftProblems,
                            long scheduledExams, long runningExams) {
    }
}
