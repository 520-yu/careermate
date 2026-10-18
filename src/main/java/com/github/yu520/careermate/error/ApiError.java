package com.github.yu520.careermate.error;

import java.time.Instant;

/**
 * 统一的错误响应体。
 *
 * <p>字段设计上刻意把「机器用的」和「人用的」分开：
 * <ul>
 *   <li>{@code code} —— 稳定的业务码，前端按它分支，永远不会因为改文案而变化</li>
 *   <li>{@code message} —— 给人看的中文说明，可以随时调整</li>
 *   <li>{@code traceId} —— 关联日志。用户报错时把这个八位串贴过来，
 *       就能在日志里一行搜到那次请求的完整堆栈</li>
 * </ul>
 *
 * @param code      业务错误码（{@link ErrorCode#name()}）
 * @param message   面向用户的说明
 * @param status    HTTP 状态码，冗余一份便于前端不读 header 也能判断
 * @param path      出错的请求路径
 * @param traceId   本次请求的追踪串，同时出现在日志里
 * @param timestamp 发生时间
 */
public record ApiError(
        String code,
        String message,
        int status,
        String path,
        String traceId,
        Instant timestamp) {

    public static ApiError of(ErrorCode errorCode, String message, String path, String traceId) {
        String text = (message == null || message.isBlank()) ? errorCode.defaultMessage() : message;
        return new ApiError(
                errorCode.name(),
                text,
                errorCode.status().value(),
                path,
                traceId,
                Instant.now());
    }
}
