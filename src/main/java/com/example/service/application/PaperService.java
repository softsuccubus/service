package com.example.service.application;

import com.example.service.api.dto.PaperDtos.CreatePaperRequest;
import com.example.service.api.dto.PaperDtos.PaperItemRequest;
import com.example.service.api.dto.PaperDtos.PaperItemView;
import com.example.service.api.dto.PaperDtos.PaperVersionView;
import com.example.service.api.dto.PaperDtos.PaperView;
import com.example.service.api.support.ApiException;
import com.example.service.domain.Paper;
import com.example.service.domain.PaperItem;
import com.example.service.domain.PaperVersion;
import com.example.service.domain.PaperVersionStatus;
import com.example.service.domain.ProblemVersion;
import com.example.service.domain.VersionStatus;
import com.example.service.infrastructure.repository.PaperItemRepository;
import com.example.service.infrastructure.repository.PaperRepository;
import com.example.service.infrastructure.repository.PaperVersionRepository;
import com.example.service.infrastructure.repository.ProblemVersionRepository;
import com.example.service.infrastructure.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 组卷（UC-06）：试卷版本保存题目版本引用、顺序与分值；
 * 只有已发布的试卷版本可以被考试引用，发布后不可修改。
 */
@Service
public class PaperService {

    private final PaperRepository paperRepository;
    private final PaperVersionRepository paperVersionRepository;
    private final PaperItemRepository paperItemRepository;
    private final ProblemVersionRepository problemVersionRepository;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    public PaperService(PaperRepository paperRepository,
                        PaperVersionRepository paperVersionRepository,
                        PaperItemRepository paperItemRepository,
                        ProblemVersionRepository problemVersionRepository,
                        AccessGuard accessGuard,
                        AuditService auditService,
                        Clock clock) {
        this.paperRepository = paperRepository;
        this.paperVersionRepository = paperVersionRepository;
        this.paperItemRepository = paperItemRepository;
        this.problemVersionRepository = problemVersionRepository;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public PaperView createPaper(CreatePaperRequest request, AuthPrincipal principal) {
        accessGuard.requireStaff(principal);
        Paper paper = new Paper();
        paper.setName(request.name().trim());
        paper.setOwnerId(principal.userId());
        paper.setCreatedAt(clock.instant());
        paperRepository.save(paper);

        PaperVersion version = new PaperVersion();
        version.setPaperId(paper.getId());
        version.setVersionNo(1);
        version.setStatus(PaperVersionStatus.DRAFT);
        version.setCreatedAt(clock.instant());
        paperVersionRepository.save(version);

        if (request.items() != null && !request.items().isEmpty()) {
            replaceItems(version.getId(), request.items());
        }
        paper.setCurrentVersionId(version.getId());
        paperRepository.save(paper);
        auditService.record(principal.userId(), "PAPER_CREATE", "paper", paper.getId(), null);
        return view(paper);
    }

    /** 更新试卷：草稿版本直接改；已发布则创建新草稿版本（复制原题目列表）。 */
    @Transactional
    public PaperView updatePaper(Long paperId, String name, List<PaperItemRequest> items, AuthPrincipal principal) {
        Paper paper = requirePaper(paperId);
        accessGuard.requireOwnerOrAdmin(principal, paper.getOwnerId(), "试卷");
        List<PaperVersion> versions = paperVersionRepository.findByPaperIdOrderByVersionNoDesc(paperId);
        PaperVersion latest = versions.isEmpty() ? null : versions.getFirst();
        PaperVersion target;
        if (latest != null && latest.getStatus() == PaperVersionStatus.DRAFT) {
            target = latest;
        } else {
            target = new PaperVersion();
            target.setPaperId(paperId);
            target.setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1);
            target.setStatus(PaperVersionStatus.DRAFT);
            target.setCreatedAt(clock.instant());
            paperVersionRepository.save(target);
            if (latest != null) {
                List<PaperItemRequest> copies = paperItemRepository
                        .findByPaperVersionIdOrderByItemOrderAsc(latest.getId()).stream()
                        .map(i -> new PaperItemRequest(i.getProblemVersionId(), i.getScore(), i.getItemOrder()))
                        .toList();
                replaceItems(target.getId(), copies);
            }
        }
        if (name != null && !name.isBlank()) {
            paper.setName(name.trim());
            paperRepository.save(paper);
        }
        if (items != null) {
            replaceItems(target.getId(), items);
        }
        return view(paper);
    }

    /** 发布试卷版本：固定题目顺序、分值，并要求所引用题目版本均已发布。 */
    @Transactional
    public PaperView publish(Long paperId, Long versionId, AuthPrincipal principal) {
        Paper paper = requirePaper(paperId);
        accessGuard.requireOwnerOrAdmin(principal, paper.getOwnerId(), "试卷");
        PaperVersion version = versionId == null
                ? paperVersionRepository.findFirstByPaperIdOrderByVersionNoDesc(paperId)
                .orElseThrow(() -> ApiException.badRequest("试卷没有可发布的版本"))
                : paperVersionRepository.findById(versionId)
                .orElseThrow(() -> ApiException.notFound("试卷版本不存在"));
        if (!version.getPaperId().equals(paperId)) {
            throw ApiException.badRequest("版本不属于该试卷");
        }
        if (version.getStatus() != PaperVersionStatus.DRAFT) {
            throw ApiException.conflict("只有草稿版本可以发布");
        }
        List<PaperItem> items = paperItemRepository.findByPaperVersionIdOrderByItemOrderAsc(version.getId());
        if (items.isEmpty()) {
            throw ApiException.badRequest("试卷至少需要一道题");
        }
        for (PaperItem item : items) {
            ProblemVersion problemVersion = problemVersionRepository.findById(item.getProblemVersionId())
                    .orElseThrow(() -> ApiException.badRequest("题目版本不存在: " + item.getProblemVersionId()));
            if (problemVersion.getStatus() != VersionStatus.PUBLISHED) {
                throw ApiException.badRequest("题目版本未发布: " + item.getProblemVersionId());
            }
        }
        BigDecimal total = items.stream().map(PaperItem::getScore).reduce(BigDecimal.ZERO, BigDecimal::add);
        version.setTotalScore(total);
        version.setStatus(PaperVersionStatus.PUBLISHED);
        paperVersionRepository.save(version);
        paper.setCurrentVersionId(version.getId());
        paperRepository.save(paper);
        auditService.record(principal.userId(), "PAPER_PUBLISH", "paper_version", version.getId(),
                "发布版本 v" + version.getVersionNo());
        return view(paper);
    }

    @Transactional(readOnly = true)
    public List<PaperView> listPapers(AuthPrincipal principal) {
        accessGuard.requireStaff(principal);
        List<Paper> papers = principal.isAdmin()
                ? paperRepository.findAllByOrderByIdDesc()
                : paperRepository.findByOwnerIdOrderByIdDesc(principal.userId());
        return papers.stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public PaperView getPaper(Long paperId, AuthPrincipal principal) {
        Paper paper = requirePaper(paperId);
        accessGuard.requireOwnerOrAdmin(principal, paper.getOwnerId(), "试卷");
        return view(paper);
    }

    /** 供考试服务校验与展示：获取已发布试卷版本及其题目。 */
    @Transactional(readOnly = true)
    public PaperVersionView publishedVersion(Long paperVersionId) {
        PaperVersion version = paperVersionRepository.findById(paperVersionId)
                .orElseThrow(() -> ApiException.notFound("试卷版本不存在"));
        return versionView(version);
    }

    private void replaceItems(Long paperVersionId, List<PaperItemRequest> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Set<Integer> orders = new HashSet<>();
        int autoOrder = 1;
        List<PaperItem> toSave = new ArrayList<>();
        for (PaperItemRequest request : items) {
            if (request.problemVersionId() == null) {
                throw ApiException.badRequest("题目版本不能为空");
            }
            ProblemVersion problemVersion = problemVersionRepository.findById(request.problemVersionId())
                    .orElseThrow(() -> ApiException.badRequest("题目版本不存在: " + request.problemVersionId()));
            if (problemVersion.getStatus() != VersionStatus.PUBLISHED) {
                throw ApiException.badRequest("只能引用已发布的题目版本: " + request.problemVersionId());
            }
            int order = request.itemOrder() == null ? autoOrder++ : request.itemOrder();
            if (!orders.add(order)) {
                throw ApiException.badRequest("题目顺序重复: " + order);
            }
            PaperItem item = new PaperItem();
            item.setPaperVersionId(paperVersionId);
            item.setProblemVersionId(request.problemVersionId());
            item.setItemOrder(order);
            item.setScore(request.score() == null ? BigDecimal.ZERO : request.score());
            toSave.add(item);
        }
        paperItemRepository.deleteByPaperVersionId(paperVersionId);
        paperItemRepository.flush();
        paperItemRepository.saveAll(toSave);
    }

    private Paper requirePaper(Long paperId) {
        return paperRepository.findById(paperId)
                .orElseThrow(() -> ApiException.notFound("试卷不存在"));
    }

    private PaperView view(Paper paper) {
        List<PaperVersionView> versions = paperVersionRepository
                .findByPaperIdOrderByVersionNoDesc(paper.getId()).stream()
                .map(this::versionView).toList();
        return new PaperView(paper.getId(), paper.getName(), paper.getOwnerId(),
                paper.getCurrentVersionId(), paper.getCreatedAt(), versions);
    }

    private PaperVersionView versionView(PaperVersion version) {
        List<PaperItem> items = paperItemRepository.findByPaperVersionIdOrderByItemOrderAsc(version.getId());
        List<PaperItemView> itemViews = items.stream().map(item -> {
            String title = problemVersionRepository.findById(item.getProblemVersionId())
                    .map(ProblemVersion::getTitle).orElse(null);
            return new PaperItemView(item.getId(), item.getItemOrder(), item.getProblemVersionId(),
                    title, item.getScore());
        }).toList();
        return new PaperVersionView(version.getId(), version.getPaperId(), version.getVersionNo(),
                version.getTotalScore(), version.getStatus(), version.getCreatedAt(), itemViews);
    }
}
