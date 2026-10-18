package com.github.yu520.careermate.agent;

import com.github.yu520.careermate.error.BusinessException;
import com.github.yu520.careermate.error.ErrorCode;

import com.github.yu520.careermate.model.InterviewReport;
import com.github.yu520.careermate.model.InterviewSession;
import com.github.yu520.careermate.model.InterviewTurn;
import com.github.yu520.careermate.model.JdMatchResult;
import com.github.yu520.careermate.model.ResumeProfile;
import com.github.yu520.careermate.service.RagService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟面试 Agent：基于简历与目标 JD 动态出题、追问、最后给出评估报告。
 */
@Component
public class InterviewAgent {

    /**
     * 每轮最多带多少轮历史给模型。
     * 完整流水另行保留在 Redis，供最后生成报告用 —— 这是本类和
     * {@code MessageChatMemoryAdvisor} 的根本区别：那个是「运行时上下文」，这个是「记录」。
     */
    private static final int MAX_CONTEXT_TURNS = 20;

    /** 开场时检索多少条题库作为出题参考。 */
    private static final int RAG_TOP_K = 4;

    /** 生成报告时塞进 prompt 的流水字符上限，防止异常长的面试把单次请求撑爆。 */
    private static final int MAX_TRANSCRIPT_CHARS = 40_000;

    private static final String INTERVIEWER_RULES = """
            你是一位资深技术面试官，正在对候选人进行模拟面试。
            你的目标是尽可能真实地还原技术面试、帮他暴露问题，而不是让他自我感觉良好。

            面试规则：
            1. 一次只问一个问题，问完等候选人回答，绝不自问自答；
            2. 候选人回答后，先用一两句话点出回答里的关键点或漏洞，再决定追问还是换题；
            3. 如果答得含糊、只给结论不给过程，必须追问「为什么」和「具体怎么做的」；
            4. 如果答错了，当场指出错在哪里并给出正确方向，不要放过；
            5. 难度对齐目标岗位的真实要求，不要问与该岗位无关的偏题；
            6. 全程用中文，语气专业但不刻薄。
            """;

    private static final String REPORT_RULES = """
            你是一位资深技术面试官，现在要基于一场模拟面试的完整记录给出评估报告。

            要求：
            1. overallScore 是 0-100 的整数，必须有区分度：
               回答扎实、能讲清原理与取舍给 75 以上；有明显硬伤（答错关键概念、全程答不出细节）给 60 以下；
               不要一律给 80 分这种「安全分」；
            2. dimensions 给 3-5 个维度，每个维度的 comment 必须引用候选人的原话或具体表现，不能空泛；
            3. highlights 写真实亮点，没有就给空数组，不要硬夸；
            4. risks 写面试官视角的减分项，具体到「哪一问答漏了什么」；
            5. suggestions 要可执行，指明补什么、怎么补；
            6. 全程用中文。
            """;

    private final ChatClient interviewChatClient;
    private final RagService ragService;

    public InterviewAgent(ChatClient.Builder builder, RagService ragService) {
        // 这里刻意不挂 MessageChatMemoryAdvisor：
        // 面试上下文由本类按「完整流水」自行裁剪并显式构造成多轮消息。
        // 若再叠加窗口记忆 Advisor，同一段历史会被注入两次 ——
        // 既浪费 token，也可能让模型把重复出现的轮次误判成「候选人重复回答了同一题」。
        //
        // 注意 ChatClient.Builder 是 prototype 作用域的，这里拿到的与 AiConfig 里那个是不同实例，
        // 所以此处不挂 Advisor 不会影响全局那个带记忆的 ChatClient。
        this.interviewChatClient = builder.build();
        this.ragService = ragService;
    }

    /**
     * 开场前检索一次参考题库，整场面试复用。
     *
     * <p>检索词由「JD + 候选人技能 + 已知短板」拼成：只用 JD 会偏向岗位通用要求，
     * 带上技能与短板才能检索到真正该问他的题。
     */
    public String prepareReference(ResumeProfile resume, String jd, JdMatchResult matchResult) {
        StringBuilder query = new StringBuilder(jd == null ? "" : jd);
        if (resume != null && !resume.skills().isEmpty()) {
            query.append(' ').append(String.join(" ", resume.skills()));
        }
        if (matchResult != null && !matchResult.missingSkills().isEmpty()) {
            query.append(' ').append(String.join(" ", matchResult.missingSkills()));
        }
        return ragService.searchAsContextBySource(
                query.toString(), RagService.SOURCE_INTERVIEW_QUESTIONS, RAG_TOP_K);
    }

    /** 生成开场白与第一道题。 */
    public String open(InterviewSession session) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(buildSystemPrompt(session)),
                new UserMessage("请开始这场面试：先用一两句话说明本场会围绕什么展开，然后直接提出第一个问题。")));

        String question = interviewChatClient.prompt(prompt).call().content();
        if (question == null || question.isBlank()) {
            throw new BusinessException(ErrorCode.INTERVIEW_START_FAILED, "面试官未能生成开场问题，请稍后重试");
        }
        return question;
    }

    /**
     * 候选人回答后，流式返回面试官的回应（点评 + 追问/换题）。
     *
     * <p>历史以真正的多轮消息（带角色）传入，而不是拼成一段文本：
     * 角色混在一起会让模型分不清哪句是自己说的，容易出现「自己回答自己」的问题。
     *
     * @param history 该会话已有的完整流水；本方法内部只取最近 {@value #MAX_CONTEXT_TURNS} 轮
     */
    public Flux<String> answer(InterviewSession session, List<InterviewTurn> history, String candidateAnswer) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(session)));
        for (InterviewTurn turn : tail(history)) {
            messages.add(InterviewTurn.ROLE_CANDIDATE.equals(turn.role())
                    ? new UserMessage(turn.content())
                    : new AssistantMessage(turn.content()));
        }
        messages.add(new UserMessage(candidateAnswer));

        return interviewChatClient.prompt(new Prompt(messages)).stream().content();
    }

    /**
     * 基于完整流水生成评估报告。
     *
     * @param transcript 完整对话流水（不截断，报告要看到全部回答）
     */
    public InterviewReport evaluate(InterviewSession session, List<InterviewTurn> transcript) {
        String userText = """
                这场模拟面试的目标岗位 JD：
                %s

                完整对话记录（按时间顺序）：
                %s

                请输出评估报告。
                """.formatted(session.jd(), formatTranscript(transcript));

        InterviewReport report = interviewChatClient
                .prompt(new Prompt(List.of(
                        new SystemMessage(REPORT_RULES),
                        new UserMessage(userText))))
                .call()
                .entity(InterviewReport.class);

        if (report == null) {
            throw new BusinessException(ErrorCode.REPORT_GENERATE_FAILED, "模型未返回可解析的评估报告，请稍后重试");
        }
        return report;
    }

    private String buildSystemPrompt(InterviewSession session) {
        StringBuilder sb = new StringBuilder(INTERVIEWER_RULES);
        sb.append("\n候选人的简历：\n").append(session.resume().toPromptText()).append('\n');
        sb.append("\n目标岗位 JD：\n").append(session.jd()).append('\n');

        JdMatchResult match = session.matchResult();
        if (match != null && !match.missingSkills().isEmpty()) {
            sb.append("\n他已确认相对该岗位的短板（请优先在这些方向追问）：\n")
                    .append(String.join("；", match.missingSkills())).append('\n');
        }
        if (session.referenceQuestions() != null && !session.referenceQuestions().isBlank()) {
            sb.append("\n可供参考的面试题（用于把握考察方向，不要照读原文）：\n")
                    .append(session.referenceQuestions()).append('\n');
        }
        return sb.toString();
    }

    /** 只取最近 max 轮。历史太长既推高成本，也会稀释当前问题的注意力。 */
    private List<InterviewTurn> tail(List<InterviewTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.size() <= MAX_CONTEXT_TURNS
                ? history
                : history.subList(history.size() - MAX_CONTEXT_TURNS, history.size());
    }

    private String formatTranscript(List<InterviewTurn> transcript) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (InterviewTurn turn : transcript) {
            String speaker = InterviewTurn.ROLE_CANDIDATE.equals(turn.role()) ? "候选人" : "面试官";
            sb.append('[').append(index++).append("] ").append(speaker).append('：')
                    .append(turn.content()).append('\n');
            if (sb.length() > MAX_TRANSCRIPT_CHARS) {
                sb.append("...(记录过长，后续内容已截断)\n");
                break;
            }
        }
        return sb.toString();
    }
}
