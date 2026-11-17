package com.example.service.api;

import com.example.service.api.dto.ExamDtos.CreateExamRequest;
import com.example.service.api.dto.ExamDtos.DraftView;
import com.example.service.api.dto.ExamDtos.EnterExamResponse;
import com.example.service.api.dto.ExamDtos.ExamMonitorView;
import com.example.service.api.dto.ExamDtos.ExamPaperView;
import com.example.service.api.dto.ExamDtos.ExamView;
import com.example.service.api.dto.ExamDtos.RecordSummaryView;
import com.example.service.api.dto.ExamDtos.SaveDraftRequest;
import com.example.service.api.dto.ExamDtos.StudentExamView;
import com.example.service.api.dto.ExamDtos.SubmitExamResponse;
import com.example.service.api.dto.ExamDtos.UpdateExamRequest;
import com.example.service.api.dto.ScoreDtos.ExamScoreView;
import com.example.service.api.dto.ScoreDtos.ManualScoreRequest;
import com.example.service.api.dto.ScoreDtos.ProblemScore;
import com.example.service.api.dto.SubmissionDtos.SubmitRequest;
import com.example.service.api.dto.SubmissionDtos.SubmitResponse;
import com.example.service.api.dto.SubmissionDtos.SubmissionView;
import com.example.service.api.support.ApiResponse;
import com.example.service.application.ExamEntryService;
import com.example.service.application.ExamService;
import com.example.service.application.ScoreService;
import com.example.service.application.SubmissionService;
import com.example.service.infrastructure.security.AuthPrincipal;
import com.example.service.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 考试接口：教师端生命周期管理（UC-07/UC-08）、学生端作答（UC-09/UC-10/UC-11）、
 * 监控（UC-12）与成绩（UC-13/UC-14）。
 */
@RestController
@RequestMapping("/api/exams")
public class ExamController {

    private final ExamService examService;
    private final ExamEntryService examEntryService;
    private final SubmissionService submissionService;
    private final ScoreService scoreService;

    public ExamController(ExamService examService, ExamEntryService examEntryService,
                          SubmissionService submissionService, ScoreService scoreService) {
        this.examService = examService;
        this.examEntryService = examEntryService;
        this.submissionService = submissionService;
        this.scoreService = scoreService;
    }

    // ---------- 教师端 ----------

    @GetMapping
    public ApiResponse<List<ExamView>> list(@CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examService.listExams(principal));
    }

    @GetMapping("/{examId}")
    public ApiResponse<ExamView> detail(@PathVariable Long examId, @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examService.getExam(examId, principal));
    }

    @PostMapping
    public ApiResponse<ExamView> create(@Valid @RequestBody CreateExamRequest request,
                                        @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examService.createExam(request, principal));
    }

    @PutMapping("/{examId}")
    public ApiResponse<ExamView> update(@PathVariable Long examId,
                                        @Valid @RequestBody UpdateExamRequest request,
                                        @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examService.updateExam(examId, request, principal));
    }

    @PostMapping("/{examId}/publish")
    public ApiResponse<ExamView> publish(@PathVariable Long examId, @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examService.publish(examId, principal));
    }

    @PostMapping("/{examId}/start")
    public ApiResponse<ExamView> start(@PathVariable Long examId, @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examService.start(examId, principal));
    }

    @PostMapping("/{examId}/suspend")
    public ApiResponse<ExamView> suspend(@PathVariable Long examId, @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examService.suspend(examId, principal));
    }

    @PostMapping("/{examId}/resume")
    public ApiResponse<ExamView> resume(@PathVariable Long examId, @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examService.resume(examId, principal));
    }

    @PostMapping("/{examId}/end")
    public ApiResponse<ExamView> end(@PathVariable Long examId, @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examService.end(examId, principal));
    }

    @PostMapping("/{examId}/archive")
    public ApiResponse<ExamView> archive(@PathVariable Long examId, @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examService.archive(examId, principal));
    }

    @GetMapping("/{examId}/monitor")
    public ApiResponse<ExamMonitorView> monitor(@PathVariable Long examId,
                                                @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examService.monitor(examId, principal));
    }

    @GetMapping("/{examId}/participants")
    public ApiResponse<List<RecordSummaryView>> participants(@PathVariable Long examId,
                                                             @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examService.participants(examId, principal));
    }

    // ---------- 学生端 ----------

    @GetMapping("/student")
    public ApiResponse<List<StudentExamView>> studentExams(@CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examService.studentExams(principal));
    }

    @PostMapping("/{examId}/enter")
    public ApiResponse<EnterExamResponse> enter(@PathVariable Long examId,
                                                @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examEntryService.enter(examId, principal));
    }

    @GetMapping("/{examId}/paper")
    public ApiResponse<ExamPaperView> paper(@PathVariable Long examId,
                                            @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examEntryService.paper(examId, principal));
    }

    @PutMapping("/{examId}/paper/{problemVersionId}/draft")
    public ApiResponse<DraftView> saveDraft(@PathVariable Long examId,
                                            @PathVariable Long problemVersionId,
                                            @RequestBody SaveDraftRequest request,
                                            @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examEntryService.saveDraft(examId, problemVersionId, request, principal));
    }

    @GetMapping("/{examId}/drafts")
    public ApiResponse<List<DraftView>> drafts(@PathVariable Long examId,
                                               @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examEntryService.drafts(examId, principal));
    }

    @PostMapping("/{examId}/submissions")
    public ApiResponse<SubmitResponse> submit(@PathVariable Long examId,
                                              @Valid @RequestBody SubmitRequest request,
                                              @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(submissionService.submit(examId, request, principal));
    }

    @GetMapping("/{examId}/submissions")
    public ApiResponse<List<SubmissionView>> mySubmissions(@PathVariable Long examId,
                                                           @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(submissionService.listForStudentExams(examId, principal));
    }

    @PostMapping("/{examId}/submit")
    public ApiResponse<SubmitExamResponse> submitExam(@PathVariable Long examId,
                                                      @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(examEntryService.submitExam(examId, principal));
    }

    // ---------- 成绩 ----------

    @GetMapping("/{examId}/scores")
    public ApiResponse<ExamScoreView> scores(@PathVariable Long examId,
                                             @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(scoreService.examScores(examId, principal));
    }

    @GetMapping(value = "/{examId}/scores/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportScores(@PathVariable Long examId,
                                               @CurrentUser AuthPrincipal principal) {
        byte[] csv = scoreService.exportCsv(examId, principal).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"exam-" + examId + "-scores.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }

    @PutMapping("/{examId}/records/{examRecordId}/scores/{problemVersionId}")
    public ApiResponse<ProblemScore> manualScore(@PathVariable Long examId,
                                                 @PathVariable Long examRecordId,
                                                 @PathVariable Long problemVersionId,
                                                 @Valid @RequestBody ManualScoreRequest request,
                                                 @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(scoreService.manualAdjust(examId, examRecordId, problemVersionId,
                request, principal));
    }
}
