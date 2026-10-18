package com.github.yu520.careermate.model;

/**
 * 面试对话中的一轮。
 *
 * <p>为什么不复用 Spring AI 的 {@code Message} 来存对话记录：
 * 面试记录要落 Redis 并在最后生成评估报告，需要的是「完整、可长期保存」的流水；
 * 而 ChatMemory 是带窗口截断的运行时上下文，用途不同 ——
 * 用它当记录会导致早期回答在轮次多了之后被裁掉，报告就评不全。
 *
 * @param role      角色，取值 {@code user}（候选人）或 {@code assistant}（面试官）
 * @param content   发言内容
 * @param createdAt 记录时间（便于报告里体现节奏，也方便排查）
 */
public record InterviewTurn(String role, String content, String createdAt) {

    public static final String ROLE_CANDIDATE = "user";
    public static final String ROLE_INTERVIEWER = "assistant";

    public static InterviewTurn candidate(String content, String createdAt) {
        return new InterviewTurn(ROLE_CANDIDATE, content, createdAt);
    }

    public static InterviewTurn interviewer(String content, String createdAt) {
        return new InterviewTurn(ROLE_INTERVIEWER, content, createdAt);
    }
}
