package com.example.service.infrastructure.judge;

import com.example.service.domain.Verdict;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mock 评测引擎：不执行任何用户代码，按源码中的约定标记给出确定性判定，
 * 用于没有沙箱环境时的联调与验收演示。
 *
 * <p>约定标记（需出现在被测语言支持的注释中，大小写不敏感）：
 * COMPILE_ERROR / WRONG_ANSWER / TIME_LIMIT / MEMORY_LIMIT / RUNTIME_ERROR。
 * 没有标记视为正确解答，公开与隐藏测试点均通过。
 * WRONG_ANSWER 仅隐藏测试点失败，用于演示"部分得分"。</p>
 */
public class MockJudgeEngine implements JudgeEngine {

    private static final String MARK_CE = "COMPILE_ERROR";
    private static final String MARK_WA = "WRONG_ANSWER";
    private static final String MARK_TLE = "TIME_LIMIT";
    private static final String MARK_MLE = "MEMORY_LIMIT";
    private static final String MARK_RE = "RUNTIME_ERROR";

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public JudgeModel.JudgeOutcome judge(JudgeModel.JudgeRequest request) {
        String code = request.sourceCode() == null ? "" : request.sourceCode();
        Verdict base = detectBaseVerdict(code);
        List<JudgeModel.JudgeCaseOutcome> outcomes = new ArrayList<>();
        for (JudgeModel.JudgeTestCase testCase : request.testCases()) {
            Verdict verdict = verdictFor(base, testCase);
            BigDecimal earned = verdict == Verdict.AC && testCase.points() != null
                    ? testCase.points()
                    : BigDecimal.ZERO;
            outcomes.add(new JudgeModel.JudgeCaseOutcome(
                    testCase.testCaseVersionId(),
                    verdict,
                    earned,
                    timeUsed(verdict, request, testCase),
                    memoryUsed(verdict, request, testCase),
                    summarize(verdict)));
        }
        Verdict overall = outcomes.stream()
                .map(JudgeModel.JudgeCaseOutcome::verdict)
                .filter(v -> v != Verdict.AC)
                .findFirst()
                .orElse(Verdict.AC);
        return new JudgeModel.JudgeOutcome(overall, outcomes, name());
    }

    private Verdict detectBaseVerdict(String code) {
        if (code.isBlank()) {
            return Verdict.CE;
        }
        String upper = code.toUpperCase(Locale.ROOT);
        if (upper.contains(MARK_RE)) {
            return Verdict.RE;
        }
        if (upper.contains(MARK_CE)) {
            return Verdict.CE;
        }
        if (upper.contains(MARK_TLE)) {
            return Verdict.TLE;
        }
        if (upper.contains(MARK_MLE)) {
            return Verdict.MLE;
        }
        if (upper.contains(MARK_WA)) {
            return Verdict.WA;
        }
        return Verdict.AC;
    }

    private Verdict verdictFor(Verdict base, JudgeModel.JudgeTestCase testCase) {
        if (base == Verdict.WA) {
            return testCase.visibility() == com.example.service.domain.Visibility.HIDDEN ? Verdict.WA : Verdict.AC;
        }
        return base;
    }

    private Integer timeUsed(Verdict verdict, JudgeModel.JudgeRequest request, JudgeModel.JudgeTestCase testCase) {
        if (verdict == Verdict.CE) {
            return null;
        }
        if (verdict == Verdict.TLE) {
            return request.timeLimitMs() + 1;
        }
        long seed = Math.floorMod(request.sourceCode().hashCode() * 31L
                + (testCase.testCaseVersionId() == null ? 0 : testCase.testCaseVersionId()), 100_000L);
        return (int) (seed % Math.max(1, request.timeLimitMs() / 2) + 3);
    }

    private Long memoryUsed(Verdict verdict, JudgeModel.JudgeRequest request, JudgeModel.JudgeTestCase testCase) {
        if (verdict == Verdict.CE) {
            return null;
        }
        long limitKb = (long) request.memoryLimitMb() * 1024;
        if (verdict == Verdict.MLE) {
            return limitKb + 1024;
        }
        long seed = testCase.testCaseVersionId() == null ? 7 : testCase.testCaseVersionId();
        return limitKb / 4 + seed % Math.max(1, limitKb / 4);
    }

    private String summarize(Verdict verdict) {
        return switch (verdict) {
            case AC -> "测试点通过";
            case WA -> "输出与期望不一致";
            case CE -> "编译失败";
            case TLE -> "超出时间限制";
            case MLE -> "超出内存限制";
            case RE -> "运行时异常";
        };
    }
}
