package com.example.service.api;

import com.example.service.api.dto.ScoreDtos.MyScoreView;
import com.example.service.api.dto.SubmissionDtos.SubmissionView;
import com.example.service.api.support.ApiResponse;
import com.example.service.application.ScoreService;
import com.example.service.application.SubmissionService;
import com.example.service.infrastructure.security.AuthPrincipal;
import com.example.service.infrastructure.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提交与成绩查询接口（UC-11/UC-13）。 */
@RestController
@RequestMapping("/api")
public class SubmissionController {

    private final SubmissionService submissionService;
    private final ScoreService scoreService;

    public SubmissionController(SubmissionService submissionService, ScoreService scoreService) {
        this.submissionService = submissionService;
        this.scoreService = scoreService;
    }

    @GetMapping("/submissions/{submissionId}")
    public ApiResponse<SubmissionView> detail(@PathVariable Long submissionId,
                                              @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(submissionService.detail(submissionId, principal));
    }

    @GetMapping("/exam-records/{examRecordId}/submissions")
    public ApiResponse<List<SubmissionView>> recordSubmissions(@PathVariable Long examRecordId,
                                                               @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(submissionService.listForRecord(examRecordId, principal));
    }

    @GetMapping("/me/scores")
    public ApiResponse<List<MyScoreView>> myScores(@CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(scoreService.myScores(principal));
    }
}
