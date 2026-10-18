package com.github.yu520.careermate.config;

import com.alibaba.cloud.ai.memory.redis.LettuceRedisChatMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 对话侧的装配。
 *
 * <p>职责边界：本类只装配「对话模型 + 会话记忆」，不涉及 Embedding 与向量库
 * —— 那部分由 {@link EmbeddingConfig} 和 Spring AI 的自动配置负责。
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /**
     * Redis 中会话记忆的 key 前缀。
     * 加前缀是为了与其它业务 key 隔离，也方便开发时直接用 redis-cli 翻看某段会话的历史。
     */
    private static final String MEMORY_KEY_PREFIX = "careermate:chat:memory:";

    /** 滑动窗口保留的最大消息条数（一问一答算 2 条，即约 10 轮）。 */
    private static final int MAX_MESSAGES = 20;

    /**
     * 会话记忆的存储层，落在 Redis。
     *
     * <p>为什么这么写：Spring AI 1.1.x 把「记忆」拆成了两层 ——
     * {@link MessageWindowChatMemory} 只管窗口截断策略，真正的持久化交给
     * {@link ChatMemoryRepository}。Spring AI Alibaba 已经提供了 Redis 版仓库实现，
     * 直接拿来用即可，不必自己写消息序列化和 Redis 读写。
     *
     * <p>需要知道的一点：该实现会自行创建一条独立的 Lettuce 连接，<b>不复用</b>
     * Spring Boot 为 {@code spring.data.redis.*} 自动配置的那个 RedisConnectionFactory，
     * 所以运行时会多占一条连接。当前量级无所谓，将来连接数吃紧时要想到这一点。
     */
    @Bean
    public ChatMemoryRepository chatMemoryRepository(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {

        // 字符集护栏：这个仓库内部用 RedisTemplate 的默认序列化器，而它的字符集取的正是
        // JVM 默认字符集。Java 17 在中文 Windows 上 file.encoding 仍是 GBK（UTF-8 默认值
        // 要 Java 18 起），于是「UTF-8 写入、GBK 读回」—— 中文会话记忆全部变成乱码，
        // 表现出来就是「多轮对话失忆」，而且不报任何错。真实踩过，所以在这里显式拦一道。
        Charset defaultCharset = Charset.defaultCharset();
        if (!StandardCharsets.UTF_8.name().equalsIgnoreCase(defaultCharset.name())) {
            log.error("""
                    当前 JVM 默认字符集是 {}（不是 UTF-8）。
                    LettuceRedisChatMemoryRepository 会用它做字符串序列化，导致中文会话记忆读回乱码，
                    现象是「多轮对话失忆」且没有任何报错。请用下面的方式启动：
                      java -Dfile.encoding=UTF-8 -jar target/careermate-0.0.1-SNAPSHOT.jar
                    IDEA 用户在 Run Configuration 的 VM options 里加：-Dfile.encoding=UTF-8
                    """, defaultCharset.name());
        }

        return LettuceRedisChatMemoryRepository.builder()
                .host(host)
                .port(port)
                .keyPrefix(MEMORY_KEY_PREFIX)
                .build();
    }

    /**
     * 会话记忆本体：滑动窗口，最多保留 {@value #MAX_MESSAGES} 条消息。
     *
     * <p>为什么要限制条数：多轮对话会不断往后追加历史，不设上限的话 prompt 会越来越长，
     * 既推高 token 成本，最终也必然撞上模型的上下文上限。超出窗口时丢弃最旧的消息，
     * 保证「记得住最近、放得下最早」。
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(MAX_MESSAGES)
                .build();
    }

    /**
     * 全局共用的 ChatClient，默认挂上记忆 Advisor。
     *
     * <p>把记忆 Advisor 挂在默认配置上，后续各个 Agent 只要注入 ChatClient
     * 就自动获得多轮记忆能力，不必每个 Agent 重复装配一遍 —— 也避免了
     * 「某个 Agent 忘了挂 Advisor 导致对话失忆」这类低级失误。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
