package com.github.yu520.careermate.controller;

import com.github.yu520.careermate.model.ChatReply;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多轮对话入口。
 *
 * <p>同一个 sessionId 的多次请求共享同一份会话记忆；换一个 sessionId 就是另起一段对话。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;

    /** 构造器注入：依赖保持不可变，写单元测试时也能直接 new 出来，不必起容器。 */
    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 发一轮对话，并返回本轮的模型回复。
     *
     * @param msg       本轮用户输入
     * @param sessionId 会话 ID；相同 ID 视为同一段连续对话，不传则落到默认会话
     * @return 模型回复 + 原样回显的 sessionId
     */
    @GetMapping
    public ChatReply chat(
            @RequestParam("msg") String msg,
            @RequestParam(value = "sessionId", defaultValue = ChatMemory.DEFAULT_CONVERSATION_ID) String sessionId) {

        String reply = chatClient.prompt()
                .user(msg)
                // 会话 ID 必须按「每次请求」传入，而不能写在 Advisor 上：
                // Advisor 由 ChatClient 持有、是全局单例，若把 sessionId 固定在它身上，
                // 所有用户就都会读写同一份记忆，多会话之间会互相串味。
                // Spring AI 的做法是通过 Advisor 参数（CONVERSATION_ID）逐请求下发。
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        return new ChatReply(sessionId, reply);
    }
}
