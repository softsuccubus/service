package com.example.service.infrastructure.judge;

import com.example.service.domain.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 真实沙箱评测引擎（Python）：把被测代码与各测试点输入打包成 JSON，
 * 通过 {@code docker run} 在隔离容器中执行，逐测试点回传判定。
 *
 * <p>隔离由镜像（非 root、精简）与运行时参数共同保证：{@code --network none}、
 * {@code --read-only}、{@code --cap-drop ALL}、{@code --pids-limit}、
 * {@code --memory} 与 {@code --security-opt no-new-privileges}。</p>
 *
 * <p>隐藏数据最小化（BR-06）：期望输出**不进入容器**，只在应用侧比对；
 * 单测试点摘要不包含隐藏输入或期望输出。</p>
 *
 * <p>启用：{@code app.judge.engine=sandbox}，并预先构建镜像
 * （{@code docker build -t judge-python-sandbox:latest ./sandbox}）。</p>
 */
@Component
public class SandboxJudgeEngine implements JudgeEngine {

    private static final Logger log = LoggerFactory.getLogger(SandboxJudgeEngine.class);

    private static final String SANDBOX_USER = "65534:65534";
    private static final long PER_CASE_SLACK_MS = 2000L;
    private static final long STARTUP_SLACK_MS = 15000L;

    private final TestDataStorage testDataStorage;
    private final JsonMapper mapper;
    private final String dockerCommand;
    private final String image;
    private final String cpus;
    private final String network;
    private final int pidsLimit;
    private final int tmpfsSizeMb;
    private final int memoryHeadroomMb;
    private final String extraArgs;

    public SandboxJudgeEngine(TestDataStorage testDataStorage,
                              JsonMapper mapper,
                              @Value("${app.judge.sandbox.docker-command:docker}") String dockerCommand,
                              @Value("${app.judge.sandbox.image:judge-python-sandbox:latest}") String image,
                              @Value("${app.judge.sandbox.cpus:1}") String cpus,
                              @Value("${app.judge.sandbox.network:none}") String network,
                              @Value("${app.judge.sandbox.pids-limit:128}") int pidsLimit,
                              @Value("${app.judge.sandbox.tmpfs-size-mb:64}") int tmpfsSizeMb,
                              @Value("${app.judge.sandbox.memory-headroom-mb:64}") int memoryHeadroomMb,
                              @Value("${app.judge.sandbox.extra-args:}") String extraArgs) {
        this.testDataStorage = testDataStorage;
        this.mapper = mapper;
        this.dockerCommand = dockerCommand;
        this.image = image;
        this.cpus = cpus;
        this.network = network;
        this.pidsLimit = pidsLimit;
        this.tmpfsSizeMb = tmpfsSizeMb;
        this.memoryHeadroomMb = memoryHeadroomMb;
        this.extraArgs = extraArgs;
    }

    @Override
    public String name() {
        return "sandbox";
    }

    @Override
    public JudgeModel.JudgeOutcome judge(JudgeModel.JudgeRequest request) {
        String language = request.language() == null ? "" : request.language().trim().toUpperCase(Locale.ROOT);
        if (!"PYTHON".equals(language)) {
            throw new IllegalStateException("沙箱评测引擎当前仅支持 PYTHON，收到: " + request.language());
        }
        List<JudgeModel.JudgeTestCase> testCases = request.testCases();
        if (testCases.isEmpty()) {
            return new JudgeModel.JudgeOutcome(Verdict.AC, List.of(), name());
        }

        // 期望输出只在应用侧持有，绝不进入沙箱。
        List<String> inputs = new ArrayList<>(testCases.size());
        List<String> expectedOutputs = new ArrayList<>(testCases.size());
        for (JudgeModel.JudgeTestCase testCase : testCases) {
            inputs.add(testDataStorage.read(testCase.inputRef()));
            expectedOutputs.add(testDataStorage.read(testCase.expectedRef()));
        }

        String job = buildJobJson(request.sourceCode(), inputs, request.timeLimitMs(), request.memoryLimitMb());
        RunnerResult result = execute(job, overallTimeoutMs(request, testCases.size()), request.memoryLimitMb());

        List<JudgeModel.JudgeCaseOutcome> outcomes = new ArrayList<>(testCases.size());
        for (int i = 0; i < testCases.size(); i++) {
            outcomes.add(toCaseOutcome(testCases.get(i), i, result, expectedOutputs.get(i), request));
        }
        Verdict overall = outcomes.stream()
                .map(JudgeModel.JudgeCaseOutcome::verdict)
                .filter(v -> v != Verdict.AC)
                .findFirst()
                .orElse(Verdict.AC);
        return new JudgeModel.JudgeOutcome(overall, outcomes, name());
    }

    private JudgeModel.JudgeCaseOutcome toCaseOutcome(JudgeModel.JudgeTestCase testCase,
                                                      int index,
                                                      RunnerResult result,
                                                      String expected,
                                                      JudgeModel.JudgeRequest request) {
        if (result.compileError()) {
            return new JudgeModel.JudgeCaseOutcome(testCase.testCaseVersionId(), Verdict.CE, BigDecimal.ZERO,
                    null, null, "编译失败: " + safe(result.compileMessage()));
        }
        CaseOutcome outcome = index < result.cases().size() ? result.cases().get(index) : null;
        if (outcome == null) {
            return new JudgeModel.JudgeCaseOutcome(testCase.testCaseVersionId(), Verdict.RE, BigDecimal.ZERO,
                    null, null, "沙箱未返回该测试点结果");
        }
        Integer timeUsed = outcome.timeMs();
        Long memoryUsed = outcome.memoryKb() > 0 ? outcome.memoryKb() : null;

        if (outcome.timedOut()) {
            return new JudgeModel.JudgeCaseOutcome(testCase.testCaseVersionId(), Verdict.TLE, BigDecimal.ZERO,
                    timeUsed, memoryUsed, "超出时间限制 (> " + request.timeLimitMs() + " ms)");
        }
        if (outcome.memoryExceeded()) {
            return new JudgeModel.JudgeCaseOutcome(testCase.testCaseVersionId(), Verdict.MLE, BigDecimal.ZERO,
                    timeUsed, memoryUsed, "超出内存限制 (> " + request.memoryLimitMb() + " MB)");
        }
        if (outcome.exitCode() != 0) {
            return new JudgeModel.JudgeCaseOutcome(testCase.testCaseVersionId(), Verdict.RE, BigDecimal.ZERO,
                    timeUsed, memoryUsed, "运行时错误 (退出码 " + outcome.exitCode() + ")");
        }
        if (JudgeOutputMatcher.matches(outcome.output(), expected)) {
            BigDecimal points = testCase.points() == null ? BigDecimal.ZERO : testCase.points();
            return new JudgeModel.JudgeCaseOutcome(testCase.testCaseVersionId(), Verdict.AC, points,
                    timeUsed, memoryUsed, "通过");
        }
        return new JudgeModel.JudgeCaseOutcome(testCase.testCaseVersionId(), Verdict.WA, BigDecimal.ZERO,
                timeUsed, memoryUsed, "输出与预期不一致");
    }

    private String buildJobJson(String source, List<String> inputs, int timeLimitMs, int memoryLimitMb) {
        List<Map<String, Object>> cases = new ArrayList<>(inputs.size());
        for (String input : inputs) {
            cases.add(Map.of("input", input == null ? "" : input));
        }
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("source", source == null ? "" : source);
        job.put("cases", cases);
        job.put("timeLimitMs", Math.max(1, timeLimitMs));
        job.put("memoryLimitMb", Math.max(16, memoryLimitMb));
        return mapper.writeValueAsString(job);
    }

    /** 启动沙箱容器并解析结果；容器整体超时或异常时抛出，交由任务重试/转人工复核。 */
    private RunnerResult execute(String job, long timeoutMs, int memoryLimitMb) {
        Path cidFile = createCidFile();
        Process process;
        try {
            process = new ProcessBuilder(buildCommand(cidFile, memoryLimitMb)).start();
        } catch (IOException ex) {
            deleteQuietly(cidFile);
            throw new IllegalStateException("无法启动沙箱容器（请确认 Docker 可用且镜像 "
                    + image + " 已构建）: " + ex.getMessage(), ex);
        }

        byte[] stdout;
        byte[] stderr;
        try {
            byte[] payload = job.getBytes(StandardCharsets.UTF_8);
            CompletableFuture<byte[]> outFuture = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
            CompletableFuture<byte[]> errFuture = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
            CompletableFuture<Void> inFuture = CompletableFuture.runAsync(() -> writeAll(process.getOutputStream(), payload));

            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                killContainer(cidFile);
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("沙箱容器整体执行超时（" + timeoutMs + " ms）");
            }
            stdout = outFuture.join();
            stderr = errFuture.join();
            inFuture.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("等待沙箱容器时被中断", ex);
        } finally {
            deleteQuietly(cidFile);
        }

        String text = new String(stdout, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            String errText = new String(stderr, StandardCharsets.UTF_8).trim();
            throw new IllegalStateException("沙箱未返回结果 (exit=" + process.exitValue() + ")"
                    + (errText.isEmpty() ? "" : ": " + errText));
        }
        try {
            return mapper.readValue(text, RunnerResult.class);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("沙箱返回无法解析: " + truncate(text), ex);
        }
    }

    private List<String> buildCommand(Path cidFile, int memoryLimitMb) {
        int containerMemoryMb = Math.max(64, memoryLimitMb + memoryHeadroomMb);
        List<String> command = new ArrayList<>(List.of(
                dockerCommand, "run", "--rm", "-i",
                "--network", network,
                "--cpus", cpus,
                "--memory", containerMemoryMb + "m",
                "--memory-swap", containerMemoryMb + "m",
                "--pids-limit", String.valueOf(pidsLimit),
                "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=" + tmpfsSizeMb + "m,mode=1777",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--user", SANDBOX_USER));
        if (cidFile != null) {
            command.add("--cidfile");
            command.add(cidFile.toString());
        }
        if (extraArgs != null && !extraArgs.isBlank()) {
            command.addAll(List.of(extraArgs.trim().split("\\s+")));
        }
        command.add(image);
        return command;
    }

    private long overallTimeoutMs(JudgeModel.JudgeRequest request, int caseCount) {
        long perCase = Math.max(1000, request.timeLimitMs()) + PER_CASE_SLACK_MS;
        return perCase * Math.max(1, caseCount) + STARTUP_SLACK_MS;
    }

    /** 创建占位 cid 文件（docker 要求文件不存在），用于超时时精确终止容器。 */
    private Path createCidFile() {
        try {
            Path path = Files.createTempFile("judge-sandbox-cid-", ".txt");
            Files.deleteIfExists(path);
            return path;
        } catch (IOException ex) {
            log.debug("无法创建 cid 文件，超时清理退化为仅终止 docker 客户端: {}", ex.getMessage());
            return null;
        }
    }

    private void killContainer(Path cidFile) {
        if (cidFile == null) {
            return;
        }
        try {
            String containerId = Files.exists(cidFile)
                    ? Files.readString(cidFile, StandardCharsets.UTF_8).trim()
                    : "";
            if (containerId.isEmpty()) {
                return;
            }
            new ProcessBuilder(dockerCommand, "kill", containerId)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS);
        } catch (IOException ex) {
            log.warn("终止沙箱容器失败: {}", ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException ex) {
            return new byte[0];
        }
    }

    private static void writeAll(OutputStream out, byte[] data) {
        try (out) {
            out.write(data);
            out.flush();
        } catch (IOException ignored) {
            // 容器提前退出导致的 EPIPE：结果以 stdout 为准，忽略。
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响评测结果。
        }
    }

    private static String safe(String text) {
        return text == null || text.isBlank() ? "未知原因" : truncate(text);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }

    /** 沙箱 runner 的返回结构（对应 sandbox/runner.py 的输出协议）。 */
    public record RunnerResult(boolean compileError, String compileMessage, List<CaseOutcome> cases) {
        public RunnerResult {
            cases = cases == null ? List.of() : cases;
        }
    }

    public record CaseOutcome(String output, boolean truncated, String stderr,
                              int timeMs, long memoryKb, int exitCode, String signal,
                              boolean timedOut, boolean memoryExceeded) {
    }
}
