package com.github.yu520.careermate.model;

import java.util.List;

/**
 * 从简历中抽取出的结构化画像。
 *
 * <p>为什么用 Record：这是「模型结构化输出」的目标类型，字段固定且只读。
 * 用 Record 既省掉一堆 getter/setter，也把「这是一份快照、不该被就地修改」写进了类型里。
 *
 * <p>为什么在紧凑构造器里兜底 null：字段来自大模型的 JSON 输出，
 * 缺字段是常态而不是异常。这里统一归一成空集合，下游各个 Agent 就不必到处判空了。
 *
 * @param name       姓名
 * @param education  学历背景（学校 / 专业 / 学历层次，单条文本）
 * @param skills     技能标签
 * @param projects   项目经历（每条一句话概括）
 * @param strengths  优势
 * @param weaknesses 短板 —— 专门留给后续模拟面试挑刺用，所以要求模型如实抽取
 */
public record ResumeProfile(
        String name,
        String education,
        List<String> skills,
        List<String> projects,
        List<String> strengths,
        List<String> weaknesses) {

    public ResumeProfile {
        skills = skills == null ? List.of() : List.copyOf(skills);
        projects = projects == null ? List.of() : List.copyOf(projects);
        strengths = strengths == null ? List.of() : List.copyOf(strengths);
        weaknesses = weaknesses == null ? List.of() : List.copyOf(weaknesses);
    }

    /**
     * 渲染成可直接塞进 prompt 的文本。
     *
     * <p>放在这里而不是各个 Agent 里各写一份：JD 匹配、模拟面试、后续的岗位推荐
     * 都需要把简历喂给模型，渲染方式必须一致 —— 分散实现迟早会出现
     * 「某个 Agent 少喂了一个字段、导致模型判断依据不全」这种很难发现的偏差。
     *
     * <p>用带小标题的段落而不是 JSON：模型对「字段名: 值」的段落更容易对齐，
     * 而且不会因为 JSON 转义问题在某些输入下出错。
     */
    public String toPromptText() {
        return """
                姓名：%s
                学历：%s
                技能：%s
                项目经历：%s
                优势：%s
                短板：%s"""
                .formatted(
                        blankToDash(name),
                        blankToDash(education),
                        joinOrDash(skills),
                        joinOrDash(projects),
                        joinOrDash(strengths),
                        joinOrDash(weaknesses));
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "（未提供）" : value;
    }

    private static String joinOrDash(List<String> values) {
        return values == null || values.isEmpty() ? "（未提供）" : String.join("；", values);
    }
}
