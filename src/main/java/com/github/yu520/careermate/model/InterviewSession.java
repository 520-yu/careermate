package com.github.yu520.careermate.model;

/**
 * 一场模拟面试的会话状态。
 *
 * <p>它和对话流水（{@link InterviewTurn} 列表）分开存：
 * 会话状态每场只写一次、读多次，而流水是不断追加的。
 * 分开存可以让「开一场面试」和「追加一轮对话」互不干扰，也便于单独给两者设过期时间。
 *
 * @param sessionId          会话 ID，由服务端生成
 * @param resume             候选人简历（出题依据）
 * @param jd                 目标岗位 JD（出题依据）
 * @param matchResult        可选的 JD 匹配结果；有它就能针对 missingSkills 重点追问
 * @param referenceQuestions 开场时一次性检索到的参考题库，整场复用。
 *                           放在会话里而不是每轮现查，是为了省掉每一轮的 Embedding 调用 ——
 *                           面试每轮的延迟本来就主要花在模型生成上，不该再叠加一次向量检索。
 * @param createdAt          创建时间
 */
public record InterviewSession(
        String sessionId,
        ResumeProfile resume,
        String jd,
        JdMatchResult matchResult,
        String referenceQuestions,
        String createdAt) {
}
