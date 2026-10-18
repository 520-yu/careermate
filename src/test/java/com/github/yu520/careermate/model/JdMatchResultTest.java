package com.github.yu520.careermate.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link JdMatchResult} 的越界收敛测试。
 *
 * <p>为什么值得测：{@code matchScore} 是大模型给出的整数。模型偶尔会返回 120、-5、
 * 甚至 0.85 这类越界值。紧凑构造器里的 clamp 就是为了把这些挡在边界内 ——
 * 一旦有人觉得「模型不会乱给」而把它删掉，前端就会显示 120/100 这种荒谬分数，
 * 而且不会有任何报错。
 */
class JdMatchResultTest {

    @Test
    @DisplayName("matchScore 超过 100 时收敛到 100")
    void scoreAboveHundredIsClamped() {
        JdMatchResult result = new JdMatchResult(120, List.of(), List.of(), List.of());

        assertEquals(100, result.matchScore());
    }

    @Test
    @DisplayName("matchScore 小于 0 时收敛到 0")
    void negativeScoreIsClamped() {
        JdMatchResult result = new JdMatchResult(-5, List.of(), List.of(), List.of());

        assertEquals(0, result.matchScore());
    }

    @Test
    @DisplayName("边界值 0 和 100 原样保留")
    void boundaryValuesAreKept() {
        assertEquals(0, new JdMatchResult(0, List.of(), List.of(), List.of()).matchScore());
        assertEquals(100, new JdMatchResult(100, List.of(), List.of(), List.of()).matchScore());
    }

    @Test
    @DisplayName("区间内的正常分数不被改动")
    void normalScoreIsUnchanged() {
        assertEquals(72, new JdMatchResult(72, List.of(), List.of(), List.of()).matchScore());
    }

    @Test
    @DisplayName("三个列表字段为 null 时归一成空集合")
    void nullListsBecomeEmpty() {
        JdMatchResult result = new JdMatchResult(60, null, null, null);

        assertEquals(List.of(), result.matchedSkills());
        assertEquals(List.of(), result.missingSkills());
        assertEquals(List.of(), result.suggestions());
    }

    @Test
    @DisplayName("列表内容原样保留")
    void listContentsArePreserved() {
        JdMatchResult result = new JdMatchResult(
                65, List.of("Java", "MySQL"), List.of("RocketMQ"), List.of("补一下消息队列"));

        assertEquals(List.of("Java", "MySQL"), result.matchedSkills());
        assertEquals(List.of("RocketMQ"), result.missingSkills());
        assertEquals(List.of("补一下消息队列"), result.suggestions());
    }
}
