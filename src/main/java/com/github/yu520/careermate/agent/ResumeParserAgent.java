package com.github.yu520.careermate.agent;

import com.github.yu520.careermate.error.BusinessException;
import com.github.yu520.careermate.error.ErrorCode;

import com.github.yu520.careermate.model.ResumeProfile;
import com.github.yu520.careermate.tools.PdfParseTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 简历解析 Agent：把一份 PDF 简历变成结构化的 {@link ResumeProfile}。
 */
@Component
public class ResumeParserAgent {

    private static final String SYSTEM_PROMPT = """
            你是一位资深 HR，擅长快速、准确地阅读简历。
            请先调用工具读取简历原文，再抽取结构化信息。

            要求：
            1. 严格依据简历原文，不要臆测，也不要替候选人补全简历里没写的信息；
            2. 缺失的字段留空字符串或空数组，不要编造；
            3. weaknesses 是留给后续模拟面试挑刺用的，必须如实列出真实短板
               （例如经验年限不足、技术栈单一、项目深度不够），不要写成客套话；
            4. 所有内容用中文输出。
            """;

    private final ChatClient chatClient;
    private final PdfParseTool pdfParseTool;

    /** 构造器注入：本 Agent 依赖哪个 Tool 是显式写明的，便于阅读，也便于单测时替换。 */
    public ResumeParserAgent(ChatClient chatClient, PdfParseTool pdfParseTool) {
        this.chatClient = chatClient;
        this.pdfParseTool = pdfParseTool;
    }

    /**
     * 解析指定路径的简历 PDF。
     *
     * @param filePath 本地 PDF 绝对路径
     * @return 结构化简历画像
     */
    public ResumeProfile parse(String filePath) {
        ResumeProfile profile = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                // 路径用 param 传参，而不是字符串拼接：
                // 拼接会让路径被当成模板正文，一旦路径里出现花括号就会被模板引擎当成占位符。
                .user(spec -> spec.text("请读取并解析这份简历文件：{filePath}").param("filePath", filePath))
                // 把解析工具交给模型，由它自己决定何时调用、传什么路径。
                .tools(pdfParseTool)
                .call()
                // entity() 会自动附加 JSON Schema 指令并把返回内容反序列化成目标类型。
                // 用它的意义在于：输出的「形状」由 ResumeProfile 这个类型强制约束，
                // 而不是靠提示词祈求模型自觉 —— 少写一段自然语言，多一层编译期保证。
                .entity(ResumeProfile.class);

        if (profile == null) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "模型未返回可解析的简历结构，请检查提示词或稍后重试");
        }
        return profile;
    }
}
