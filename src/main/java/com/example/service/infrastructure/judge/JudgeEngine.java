package com.example.service.infrastructure.judge;

/**
 * 评测引擎抽象。实现必须满足：
 * 1. 不在应用进程内执行用户代码（生产环境交由隔离沙箱容器）；
 * 2. 返回逐测试点结果，且 detailSummary 不包含隐藏输入或期望输出。
 */
public interface JudgeEngine {

    String name();

    JudgeModel.JudgeOutcome judge(JudgeModel.JudgeRequest request);
}
