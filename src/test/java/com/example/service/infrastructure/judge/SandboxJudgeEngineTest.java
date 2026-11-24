package com.example.service.infrastructure.judge;

import com.example.service.domain.Verdict;
import com.example.service.domain.Visibility;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 真实沙箱引擎的端到端用例：依赖本机 Docker 与已构建的 {@code judge-python-sandbox} 镜像。
 * 环境不具备时整体跳过（{@link Assumptions}），不影响无 Docker 环境下的 {@code ./mvnw test}。
 */
class SandboxJudgeEngineTest {

    private static final String IMAGE = "judge-python-sandbox:latest";
    private static final Path TEST_DATA_ROOT = Paths.get("target", "sandbox-test-data");

    private TestDataStorage storage;
    private SandboxJudgeEngine engine;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(dockerReady(), "跳过：本地无 Docker 或未构建镜像 " + IMAGE);
        storage = new TestDataStorage(TEST_DATA_ROOT.toString());
        engine = new SandboxJudgeEngine(storage, JsonMapper.builder().build(),
                "docker", IMAGE, "1", "none", 128, 64, 64, "");
    }

    @Test
    void acceptsCorrectSolution() {
        JudgeModel.JudgeOutcome outcome = judge(
                "a, b = map(int, input().split())\nprint(a + b)\n", "1 2\n", "3\n", 1000, 256);

        assertEquals(Verdict.AC, outcome.overallVerdict());
        assertEquals("sandbox", outcome.engineName());
        assertEquals(0, BigDecimal.TEN.compareTo(outcome.caseOutcomes().get(0).earnedScore()));
    }

    @Test
    void rejectsWrongAnswer() {
        JudgeModel.JudgeOutcome outcome = judge(
                "a, b = map(int, input().split())\nprint(a * b)\n", "1 2\n", "3\n", 1000, 256);

        assertEquals(Verdict.WA, outcome.overallVerdict());
        assertEquals(0, BigDecimal.ZERO.compareTo(outcome.caseOutcomes().get(0).earnedScore()));
    }

    @Test
    void reportsCompileError() {
        JudgeModel.JudgeOutcome outcome = judge("def f(:\n  pass\n", "", "3\n", 1000, 256);

        assertEquals(Verdict.CE, outcome.overallVerdict());
        assertNull(outcome.caseOutcomes().get(0).timeUsedMs());
    }

    @Test
    void reportsTimeLimitExceeded() {
        JudgeModel.JudgeOutcome outcome = judge("while True:\n    pass\n", "", "", 1000, 256);

        assertEquals(Verdict.TLE, outcome.overallVerdict());
    }

    @Test
    void reportsMemoryLimitExceeded() {
        JudgeModel.JudgeOutcome outcome = judge("x = [0] * (10 ** 9)\n", "", "", 2000, 64);

        assertEquals(Verdict.MLE, outcome.overallVerdict());
    }

    @Test
    void reportsRuntimeError() {
        JudgeModel.JudgeOutcome outcome = judge("print(1 // 0)\n", "", "", 1000, 256);

        assertEquals(Verdict.RE, outcome.overallVerdict());
    }

    private JudgeModel.JudgeOutcome judge(String source, String input, String expected,
                                          int timeLimitMs, int memoryLimitMb) {
        String suffix = Long.toHexString(System.nanoTime());
        String inputRef = storage.write("case-" + suffix + ".in", input);
        String expectedRef = storage.write("case-" + suffix + ".out", expected);
        JudgeModel.JudgeRequest request = new JudgeModel.JudgeRequest(
                1L, 1L, "PYTHON", source, timeLimitMs, memoryLimitMb,
                List.of(new JudgeModel.JudgeTestCase(1L, "case-1", BigDecimal.TEN,
                        Visibility.PUBLIC, inputRef, expectedRef)));
        return engine.judge(request);
    }

    private static boolean dockerReady() {
        try {
            Process process = new ProcessBuilder("docker", "image", "inspect", IMAGE)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            return false;
        }
    }
}
