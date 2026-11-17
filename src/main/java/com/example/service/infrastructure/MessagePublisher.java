package com.example.service.infrastructure;

/**
 * 评测任务消息发布抽象。应用内实现为有界队列（无外部中间件时的可用方案），
 * 生产可替换为 RabbitMQ/Kafka 实现而不影响业务层。
 */
public interface MessagePublisher {

    void publish(JudgeTaskMessage message);

    record JudgeTaskMessage(Long judgeTaskId) {
    }
}
