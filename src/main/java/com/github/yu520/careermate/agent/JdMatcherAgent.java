package com.github.yu520.careermate.agent;

import com.github.yu520.careermate.model.JdMatchResult;
import com.github.yu520.careermate.model.ResumeProfile;
import com.github.yu520.careermate.service.RagService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * JD 匹配 Agent：把简历与目标岗位要求逐条比对，输出匹配度与差距。
 */
@Component
public class JdMatcherAgent {

    private static final String SYSTEM_PROMPT = """
            你是一位资深技术招聘顾问，擅长把候选人简历与目标岗位要求做逐条比对。

            要求：
            1. matchedSkills 只列「JD 明确要求、且简历确有体现」的技能，两边缺一不可；
            2. missingSkills 列「JD 要求但简历没体现」的技能，这是候选人面试最可能被追问的点；
            3. suggestions 要针对 missingSkills 给出可执行的补齐建议，不要写「多学习」这类空话；
            4. matchScore 给 0-100 的整数，评分依据是技能覆盖度与经验年限的匹配程度，
               不要因为措辞客气就给高分；
            5. 所有内容用中文输出。
            """;

    /**
     * 提示词模板。注意只有此处会出现占位符，简历与 JD 是作为「参数值」注入的 ——
     * 参数值不会再被当成模板二次解析，所以即使 JD 正文里含有花括号（比如贴了一段 JSON）
     * 也不会破坏模板渲染。
     */
    private static final String USER_TEMPLATE = """
            候选人的结构化简历：
            {resume}

            目标岗位 JD：
            {jd}

            检索到的相似行业岗位要求（用于判断该岗位的普遍期望，可能为空）：
            {jdContext}

            请给出匹配分析。
            """;

    private final ChatClient chatClient;
    private final RagService ragService;

    public JdMatcherAgent(ChatClient chatClient, RagService ragService) {
        this.chatClient = chatClient;
        this.ragService = ragService;
    }

    /**
     * 执行匹配分析。
     *
     * @param resume 简历画像
     * @param jd     目标岗位 JD 原文
     */
    public JdMatchResult match(ResumeProfile resume, String jd) {
        // 用 JD 原文去检索相似岗位，让模型知道「这类岗位通常还要求什么」，
        // 而不是只盯着这一份 JD 的字面要求。
        String retrieved = ragService.searchAsContext(jd, 3);
        // 这里必须「只赋值一次」：下面的 lambda 会捕获这个变量，
        // 一旦像这样写成 if 里再赋值，它就不再是 effectively final，编译不过。
        String jdContext = retrieved.isBlank()
                ? "（知识库中未检索到相似岗位，请仅基于上面的 JD 原文判断）"
                : retrieved;

        JdMatchResult result = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(spec -> spec.text(USER_TEMPLATE)
                        .param("resume", resume.toPromptText())
                        .param("jd", jd)
                        .param("jdContext", jdContext))
                .call()
                .entity(JdMatchResult.class);

        if (result == null) {
            throw new IllegalStateException("模型未返回可解析的匹配结果，请稍后重试");
        }
        return result;
    }

}
