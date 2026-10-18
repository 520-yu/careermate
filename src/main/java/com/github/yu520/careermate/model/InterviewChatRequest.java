package com.github.yu520.careermate.model;

/**
 * 面试对话的一轮请求：候选人回答上一个问题。
 *
 * @param sessionId 会话 ID
 * @param message   候选人的回答
 */
public record InterviewChatRequest(String sessionId, String message) {
}
