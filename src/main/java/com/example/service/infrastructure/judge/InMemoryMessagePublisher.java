package com.example.service.infrastructure.judge;

import com.example.service.infrastructure.MessagePublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 应用内消息队列。仅作为数据库队列的加速通道：
 * 队列丢失不会丢失任务，消费者会周期性扫描数据库中的可领取任务。
 */
@Component
public class InMemoryMessagePublisher implements MessagePublisher {

    private static final int CAPACITY = 10_000;

    private final LinkedBlockingQueue<JudgeTaskMessage> queue = new LinkedBlockingQueue<>(CAPACITY);

    @Override
    public void publish(JudgeTaskMessage message) {
        if (message == null || message.judgeTaskId() == null) {
            return;
        }
        // 队列满时直接丢弃：数据库扫描会兜底，保证不阻塞提交流程。
        queue.offer(message);
    }

    public JudgeTaskMessage poll() {
        return queue.poll();
    }

    public JudgeTaskMessage poll(Duration timeout) throws InterruptedException {
        return queue.poll(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public int size() {
        return queue.size();
    }
}
