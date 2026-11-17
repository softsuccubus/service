package com.example.service.infrastructure;

import com.example.service.infrastructure.judge.JudgeEngine;
import com.example.service.infrastructure.judge.JudgeModel;
import com.example.service.infrastructure.judge.MockJudgeEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 评测引擎适配器：按 app.judge.engine 选择实现。
 * 目前仅内置 mock 引擎；接入真实沙箱时在此注册新的 {@link JudgeEngine} 实现，
 * 业务代码无需变化。
 */
@Component
public class JudgeEngineAdapter implements JudgeEngine {

    private final JudgeEngine delegate;

    public JudgeEngineAdapter(@Value("${app.judge.engine:mock}") String engineName) {
        this.delegate = switch (engineName) {
            case "mock" -> new MockJudgeEngine();
            default -> throw new IllegalStateException("未支持的评测引擎: " + engineName
                    + "，当前仅支持 mock（生产接入沙箱后在此注册）");
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
