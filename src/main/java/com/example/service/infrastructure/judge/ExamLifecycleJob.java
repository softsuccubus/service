package com.example.service.infrastructure.judge;

import com.example.service.application.ExamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 考试生命周期定时任务：到点自动开始/结束考试，并把超时的进行中记录标记为过期。
 * 公开的考试状态以服务端时间与状态机为准，不依赖前端触发。
 */
@Component
public class ExamLifecycleJob {

    private static final Logger log = LoggerFactory.getLogger(ExamLifecycleJob.class);

    private final ExamService examService;
    private final boolean enabled;

    public ExamLifecycleJob(ExamService examService,
                            @Value("${app.exam.lifecycle.enabled:true}") boolean enabled) {
        this.examService = examService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.exam.lifecycle.interval-ms:5000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            int changed = examService.runLifecycle();
            if (changed > 0) {
                log.info("考试生命周期任务处理 {} 条状态变更", changed);
            }
        } catch (RuntimeException ex) {
            log.warn("考试生命周期任务异常: {}", ex.getMessage());
        }
    }
}
