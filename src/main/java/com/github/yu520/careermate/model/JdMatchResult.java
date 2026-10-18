package com.github.yu520.careermate.model;

import java.util.List;

/**
 * 简历与目标 JD 的匹配分析结果。
 *
 * <p>{@code matchScore} 用 int（0-100）而不是 double 或百分比字符串：
 * 大模型对「整数打分」的输出比「小数打分」稳定得多，
 * 也能避免出现 {@code "85%"} 这种带符号的字符串导致反序列化失败。
 * 紧凑构造器里还会再做一次区间收敛，防止模型给出 120 或 -5 这类越界值。
 *
 * @param matchScore    匹配度评分，0-100
 * @param matchedSkills 简历已覆盖、且 JD 明确要求的技能
 * @param missingSkills JD 要求但简历里没有体现的技能（面试时最可能被追问）
 * @param suggestions   针对性的改进建议
 */
public record JdMatchResult(
        int matchScore,
        List<String> matchedSkills,
        List<String> missingSkills,
        List<String> suggestions) {

    public JdMatchResult {
        matchScore = Math.max(0, Math.min(100, matchScore));
        matchedSkills = matchedSkills == null ? List.of() : List.copyOf(matchedSkills);
        missingSkills = missingSkills == null ? List.of() : List.copyOf(missingSkills);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }
}
