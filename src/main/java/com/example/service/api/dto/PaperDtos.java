package com.example.service.api.dto;

import com.example.service.domain.PaperVersionStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 试卷相关 DTO。试卷版本发布后固定题目版本、顺序与分值。 */
public final class PaperDtos {

    private PaperDtos() {
    }

    public record CreatePaperRequest(@NotBlank(message = "试卷名称不能为空") @Size(max = 200) String name,
                                     List<PaperItemRequest> items) {
    }

    public record PaperItemRequest(@NotNull(message = "题目版本不能为空") Long problemVersionId,
                                   @NotNull @DecimalMin(value = "0.00", message = "题目分值不能为负") BigDecimal score,
                                   @Min(1) Integer itemOrder) {
    }

    public record PaperView(Long id, String name, Long ownerId, Long currentVersionId, Instant createdAt,
                            List<PaperVersionView> versions) {
    }

    public record PaperVersionView(Long id, Long paperId, int versionNo, BigDecimal totalScore,
                                   PaperVersionStatus status, Instant createdAt, List<PaperItemView> items) {
    }

    public record PaperItemView(Long id, int itemOrder, Long problemVersionId, String problemTitle,
                                BigDecimal score) {
    }
}
