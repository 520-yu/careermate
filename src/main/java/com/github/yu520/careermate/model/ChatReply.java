package com.github.yu520.careermate.model;

/**
 * 对话接口的返回体。
 *
 * <p>用 Record 而不是 Map：字段固定、序列化结果稳定，前端可以直接按字段名取值。
 *
 * @param sessionId 本轮所属会话 ID，原样回显，方便前端串联多轮对话
 * @param reply     模型回复内容
 */
public record ChatReply(String sessionId, String reply) {
}
