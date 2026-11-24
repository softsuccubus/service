package com.example.service.infrastructure;

import com.example.service.infrastructure.judge.JudgeEngine;
import com.example.service.infrastructure.judge.JudgeModel;
import com.example.service.infrastructure.judge.MockJudgeEngine;
import com.example.service.infrastructure.judge.SandboxJudgeEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 评测引擎适配器：按 app.judge.engine 选择实现。
 * <ul>
 *   <li>{@code mock}：确定性模拟引擎，不执行用户代码（联调/演示）；</li>
 *   <li>{@code sandbox}：真实隔离沙箱（Docker 容器执行，当前支持 PYTHON）。</li>
 * </ul>
 * 接入新引擎时在此注册新的 {@link JudgeEngine} 实现，业务代码无需变化。
 */
@Component
@Primary
public class JudgeEngineAdapter implements JudgeEngine {

    private final JudgeEngine delegate;

    public JudgeEngineAdapter(@Value("${app.judge.engine:mock}") String engineName,
                              SandboxJudgeEngine sandboxJudgeEngine) {
        this.delegate = switch (engineName) {
            case "mock" -> new MockJudgeEngine();
            case "sandbox" -> sandboxJudgeEngine;
            default -> throw new IllegalStateException("未支持的评测引擎: " + engineName
                    + "，可选 mock 或 sandbox");
        };
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public JudgeModel.JudgeOutcome judge(JudgeModel.JudgeRequest request) {
        return delegate.judge(request);
    }
}
