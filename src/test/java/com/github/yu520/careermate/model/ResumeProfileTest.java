package com.github.yu520.careermate.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResumeProfile} 的纯逻辑测试。
 *
 * <p>为什么值得测：这个 Record 是「模型结构化输出」的落点，字段来自大模型的 JSON，
 * 缺字段是常态。如果紧凑构造器的 null 兜底失效，下游三个 Agent 全部会 NPE 或把
 * "null" 字样拼进提示词 —— 而这类问题在毫秒级就发生了，看日志很难定位。
 */
class ResumeProfileTest {

    @Test
    @DisplayName("四个集合字段为 null 时，归一成空集合而不是留 null")
    void nullCollectionsBecomeEmpty() {
        ResumeProfile profile = new ResumeProfile("张三", "某大学", null, null, null, null);

        assertEquals(List.of(), profile.skills());
        assertEquals(List.of(), profile.projects());
        assertEquals(List.of(), profile.strengths());
        assertEquals(List.of(), profile.weaknesses());
    }

    @Test
    @DisplayName("传入的 List 会被复制，外部修改不影响已构造的对象")
    void collectionsAreDefensivelyCopied() {
        List<String> skills = new ArrayList<>(List.of("Java"));
        ResumeProfile profile = new ResumeProfile("张三", "某大学", skills, List.of(), List.of(), List.of());

        skills.add("Python");

        assertEquals(List.of("Java"), profile.skills(), "外部 List 后续修改不应反映到 Record 里");
    }

    @Test
    @DisplayName("复制后的集合是不可变的，直接改会抛异常")
    void collectionsAreImmutable() {
        ResumeProfile profile = new ResumeProfile("张三", "某大学",
                List.of("Java"), List.of(), List.of(), List.of());

        assertThrows(UnsupportedOperationException.class, () -> profile.skills().add("Python"));
    }

    @Test
    @DisplayName("toPromptText：姓名/学历缺失时渲染成「（未提供）」而不是 null")
    void promptTextNormalizesNullStrings() {
        ResumeProfile profile = new ResumeProfile(null, null, List.of(), List.of(), List.of(), List.of());

        String text = profile.toPromptText();

        assertTrue(text.contains("（未提供）"), "缺失字段应渲染成占位文案，实际输出：\n" + text);
        assertTrue(!text.contains("null"), "绝不能把 null 字样拼进提示词，实际输出：\n" + text);
    }

    @Test
    @DisplayName("toPromptText：空白字符串也当作缺失处理")
    void promptTextTreatsBlankAsMissing() {
        ResumeProfile profile = new ResumeProfile("   ", "", List.of(), List.of(), List.of(), List.of());

        String text = profile.toPromptText();

        // 这里只针对姓名/学历两行做精确断言。
        // 不要断言「占位文案总共出现几次」—— 空集合也会渲染占位文案，
        // 六个字段全空时是 6 次，把列表为空的行为耦合进来会让这条测试变脆。
        assertTrue(text.contains("姓名：（未提供）"), "空白姓名应渲染成占位文案，实际输出：\n" + text);
        assertTrue(text.contains("学历：（未提供）"), "空字符串学历应渲染成占位文案，实际输出：\n" + text);
    }

    @Test
    @DisplayName("toPromptText：有值时保留原值，列表用「；」连接")
    void promptTextKeepsValuesAndJoinsLists() {
        ResumeProfile profile = new ResumeProfile(
                "张三", "某大学 软件工程 本科",
                List.of("Java", "Redis"), List.of("医院系统"), List.of("基础扎实"), List.of("缺并发经验"));

        String text = profile.toPromptText();

        assertTrue(text.contains("张三"), text);
        assertTrue(text.contains("某大学 软件工程 本科"), text);
        assertTrue(text.contains("Java；Redis"), "列表应该用全角分号连接，实际输出：\n" + text);
        assertTrue(text.contains("缺并发经验"), text);
    }

    @Test
    @DisplayName("六个字段名都出现在提示词里（少一个都会让模型判断依据不全）")
    void promptTextContainsAllFieldLabels() {
        ResumeProfile profile = new ResumeProfile("张三", "某大学",
                List.of("Java"), List.of("项目"), List.of("优势"), List.of("短板"));

        String text = profile.toPromptText();

        for (String label : List.of("姓名", "学历", "技能", "项目经历", "优势", "短板")) {
            assertTrue(text.contains(label), "缺少字段标签：" + label + "，实际输出：\n" + text);
        }
    }

    @Test
    @DisplayName("hasCoreIdentity：姓名为 null 或空白都判定为「这次解析不可用」")
    void coreIdentityRequiresName() {
        ResumeProfile ok = new ResumeProfile("张三", "某大学", List.of(), List.of(), List.of(), List.of());
        ResumeProfile nullName = new ResumeProfile(null, "某大学", List.of(), List.of(), List.of(), List.of());
        ResumeProfile blankName = new ResumeProfile("   ", "某大学", List.of(), List.of(), List.of(), List.of());

        assertTrue(ok.hasCoreIdentity());
        assertFalse(nullName.hasCoreIdentity(), "姓名为 null 说明文本提取没成功");
        assertFalse(blankName.hasCoreIdentity(), "姓名只有空白同样不可用");
    }

    @Test
    @DisplayName("hasCoreIdentity：学历缺失不算不可用（有人简历只写学校不写学历）")
    void coreIdentityDoesNotRequireEducation() {
        ResumeProfile profile = new ResumeProfile("张三", null, List.of(), List.of(), List.of(), List.of());

        assertTrue(profile.hasCoreIdentity(), "只有姓名是硬性条件，学历不能当判据");
    }
}
