package com.example.service.infrastructure.judge;

import com.example.service.domain.OutboxEvent;
import com.example.service.infrastructure.JsonSupport;
import com.example.service.infrastructure.MessagePublisher;
import com.example.service.infrastructure.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Outbox 投递器：轮询未投递事件，以至少一次语义发送到消息通道。
 * 发送成功后记录 published_at；失败保留事件并记录原因，下次继续重试。
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final MessagePublisher messagePublisher;
    private final JsonSupport jsonSupport;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final boolean enabled;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           MessagePublisher messagePublisher,
                           JsonSupport jsonSupport,
                           TransactionTemplate transactionTemplate,
                           Clock clock,
                           @Value("${app.judge.worker.enabled:true}") boolean enabled) {
        this.outboxEventRepository = outboxEventRepository;
        this.messagePublisher = messagePublisher;
        this.jsonSupport = jsonSupport;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.judge.outbox.poll-interval-ms:1000}")
    public void relay() {
        if (!enabled) {
            return;
        }
        List<Long> ids = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByIdAsc()
                .stream().map(OutboxEvent::getId).toList();
        for (Long id : ids) {
            transactionTemplate.executeWithoutResult(status -> publishOne(id));
        }
    }

    private void publishOne(Long id) {
        OutboxEvent event = outboxEventRepository.findById(id).orElse(null);
        if (event == null || event.getPublishedAt() != null) {
            return;
        }
        try {
            if ("JUDGE_TASK_QUEUED".equals(event.getEventType())) {
                Map<String, Object> payload = jsonSupport.readMap(event.getPayloadJson());
                Object taskId = payload.get("taskId");
                if (taskId instanceof Number number) {
                    messagePublisher.publish(new MessagePublisher.JudgeTaskMessage(number.longValue()));
                } else {
                    throw new IllegalStateException("评测事件缺少 taskId");
                }
            }
            event.setPublishedAt(clock.instant());
            event.setLastError(null);
        } catch (RuntimeException ex) {
            event.setAttemptCount(event.getAttemptCount() + 1);
            event.setLastError(truncate("投递失败: " + ex.getMessage()));
            log.warn("Outbox 事件 {} 投递失败（第 {} 次）", event.getId(), event.getAttemptCount());
        }
        outboxEventRepository.save(event);
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }
}
