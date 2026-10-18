package com.github.yu520.careermate.model;

/**
 * 面试评估报告生成任务的消息体。
 *
 * <p>只传 sessionId 不传对话内容：对话流水已经在 Redis 里，
 * 消息体保持小而稳定，避免大消息触发 RocketMQ 的 4MB 限制
 * （一场长面试的完整流水很容易超过这个量级）。
 *
 * @param sessionId 面试会话 ID
 */
public record InterviewReportMessage(String sessionId) {
}
