package com.github.yu520.careermate.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link InterviewReport} 的越界收敛与嵌套结构测试。
 *
 * <p>为什么值得测：它比 {@link JdMatchResult} 多一层嵌套 —— {@code dimensionScores} 是
 * {@code List<DimensionScore>}，而 {@code DimensionScore} 自己也有一个紧凑构造器在 clamp。
 * 「外层收敛」和「内层收敛」都各自生效、且互不干扰，这件事不能靠看代码确认，只能测。
 */
class InterviewReportTest {

    @Test
    @DisplayName("overallScore 越界被收敛到 0-100")
    void overallScoreIsClamped() {
        assertEquals(100, report(120).overallScore());
        assertEquals(0, report(-5).overallScore());
        assertEquals(72, report(72).overallScore());
    }

    @Test
    @DisplayName("嵌套的 DimensionScore 分数也会被收敛")
    void dimensionScoreIsClamped() {
        InterviewReport report = new InterviewReport(
                70,
                List.of(new InterviewReport.DimensionScore("Java 基础", 150, "偏高"),
                        new InterviewReport.DimensionScore("项目深度", -20, "偏低"),
                        new InterviewReport.DimensionScore("沟通", 65, "正常")),
                List.of(), List.of(), List.of(), "总评");

        assertEquals(100, report.dimensions().get(0).score());
        assertEquals(0, report.dimensions().get(1).score());
        assertEquals(65, report.dimensions().get(2).score());
    }

    @Test
    @DisplayName("四个列表字段为 null 时归一成空集合")
    void nullListsBecomeEmpty() {
        InterviewReport report = new InterviewReport(60, null, null, null, null, null);

        assertEquals(List.of(), report.dimensions());
        assertEquals(List.of(), report.highlights());
        assertEquals(List.of(), report.risks());
        assertEquals(List.of(), report.suggestions());
    }

    @Test
    @DisplayName("嵌套 Record 的字段能正常读出（序列化/反序列化的前提）")
    void nestedDimensionFieldsAreReadable() {
        InterviewReport report = new InterviewReport(
                70,
                List.of(new InterviewReport.DimensionScore("项目深度", 60, "只讲了做了什么")),
                List.of(), List.of(), List.of(), "总评");

        InterviewReport.DimensionScore dimension = report.dimensions().get(0);
        assertEquals("项目深度", dimension.dimension());
        assertEquals(60, dimension.score());
        assertEquals("只讲了做了什么", dimension.comment());
    }

    @Test
    @DisplayName("正常值不被改动，summary 原样保留")
    void normalValuesAreUnchanged() {
        InterviewReport report = new InterviewReport(
                85,
                List.of(new InterviewReport.DimensionScore("Java 基础", 88, "扎实")),
                List.of("并发基础好"), List.of("第 2 问答漏了 AQS"), List.of("补 JUC 源码"),
                "基础尚可，深度不足。");

        assertEquals(85, report.overallScore());
        assertEquals("基础尚可，深度不足。", report.summary());
        assertEquals(List.of("第 2 问答漏了 AQS"), report.risks());
    }

    private static InterviewReport report(int overallScore) {
        return new InterviewReport(overallScore, List.of(), List.of(), List.of(), List.of(), "t");
    }
}
