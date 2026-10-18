package com.github.yu520.careermate.model;

import java.util.List;

/**
 * 模拟面试的评估报告。
 *
 * <p>评分维度用嵌套 Record 列表，而不是 {@code Map<String,Integer>}：
 * Map 的键是自由文本，模型每次可能给出不同的键名（「Java 基础」/「Java基础」/「Java 基础掌握度」），
 * 落库和前端渲染都会很难受；用列表则每项都是结构化的三元组，
 * 而且维度名字还能写进 schema 里约束模型。
 *
 * @param overallScore 综合评分，0-100
 * @param dimensions   分维度评分
 * @param highlights   表现突出的地方
 * @param risks        暴露出的问题（面试官视角的减分项）
 * @param suggestions  针对性的提升建议
 * @param summary      一段话总评
 */
public record InterviewReport(
        int overallScore,
        List<DimensionScore> dimensions,
        List<String> highlights,
        List<String> risks,
        List<String> suggestions,
        String summary) {

    public InterviewReport {
        overallScore = Math.max(0, Math.min(100, overallScore));
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
        risks = risks == null ? List.of() : List.copyOf(risks);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    /**
     * 单个评分维度。
     *
     * @param dimension 维度名（如「Java 基础」「项目深度」「沟通表达」）
     * @param score     该维度得分，0-100
     * @param comment   评分理由，必须引用候选人的具体回答，不能只给结论
     */
    public record DimensionScore(String dimension, int score, String comment) {

        public DimensionScore {
            score = Math.max(0, Math.min(100, score));
        }
    }
}
