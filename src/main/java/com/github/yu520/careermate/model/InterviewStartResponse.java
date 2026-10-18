package com.github.yu520.careermate.model;

/**
 * 开启面试的响应。
 *
 * <p>把 sessionId 和第一个问题一起返回，前端拿到就能直接渲染，
 * 不需要再单独发一次「取题目」的请求。
 *
 * @param sessionId     会话 ID，后续 /chat 与 /report 都要带上
 * @param firstQuestion 面试官的开场白 + 第一道题
 */
public record InterviewStartResponse(String sessionId, String firstQuestion) {
}
