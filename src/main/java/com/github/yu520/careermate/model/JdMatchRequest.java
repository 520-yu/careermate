package com.github.yu520.careermate.model;

/**
 * JD 匹配请求。
 *
 * <p>为什么简历要以完整的 {@link ResumeProfile} 传入，而不是只传一个简历 id：
 * 当前系统还没有做简历持久化（不存在简历表），上传接口把解析结果直接返回给调用方，
 * 由调用方持有、在需要时回传。这样这两个接口是无状态的，不需要额外的存储和会话管理。
 * 将来若加了简历存储，这里换成 resumeId 即可，Agent 侧一行都不用改。
 *
 * @param resume 阶段 2 解析出的简历画像
 * @param jd     目标岗位 JD 原文
 */
public record JdMatchRequest(ResumeProfile resume, String jd) {
}
