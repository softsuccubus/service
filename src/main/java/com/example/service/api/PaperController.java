package com.example.service.api;

import com.example.service.api.dto.PaperDtos.CreatePaperRequest;
import com.example.service.api.dto.PaperDtos.PaperItemRequest;
import com.example.service.api.dto.PaperDtos.PaperView;
import com.example.service.api.support.ApiResponse;
import com.example.service.application.PaperService;
import com.example.service.infrastructure.security.AuthPrincipal;
import com.example.service.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 组卷接口（UC-06）。 */
@RestController
@RequestMapping("/api/papers")
public class PaperController {

    private final PaperService paperService;

    public PaperController(PaperService paperService) {
        this.paperService = paperService;
    }

    public record UpdatePaperRequest(String name, List<PaperItemRequest> items) {
    }

    @GetMapping
    public ApiResponse<List<PaperView>> list(@CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(paperService.listPapers(principal));
    }

    @GetMapping("/{paperId}")
    public ApiResponse<PaperView> detail(@PathVariable Long paperId, @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(paperService.getPaper(paperId, principal));
    }

    @PostMapping
    public ApiResponse<PaperView> create(@Valid @RequestBody CreatePaperRequest request,
                                         @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(paperService.createPaper(request, principal));
    }

    @PutMapping("/{paperId}")
    public ApiResponse<PaperView> update(@PathVariable Long paperId,
                                         @RequestBody UpdatePaperRequest request,
                                         @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(paperService.updatePaper(paperId, request.name(), request.items(), principal));
    }

    @PostMapping("/{paperId}/publish")
    public ApiResponse<PaperView> publish(@PathVariable Long paperId,
                                          @RequestParam(required = false) Long versionId,
                                          @CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(paperService.publish(paperId, versionId, principal));
    }
}
