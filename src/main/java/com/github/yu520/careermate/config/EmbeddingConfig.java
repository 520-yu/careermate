package com.github.yu520.careermate.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 解决多模型冲突：
 * - ChatModel 用 DeepSeek（OpenAiChatModel）
 * - EmbeddingModel 用 Ollama（OllamaEmbeddingModel）
 */
@Configuration
public class EmbeddingConfig {

    /** Embedding 用 Ollama */
    @Bean
    @Primary
    public EmbeddingModel primaryEmbeddingModel(OllamaEmbeddingModel ollamaEmbeddingModel) {
        return ollamaEmbeddingModel;
    }

    /** ChatModel 用 DeepSeek（即 OpenAiChatModel） */
    @Bean
    @Primary
    public ChatModel primaryChatModel(OpenAiChatModel openAiChatModel) {
        return openAiChatModel;
    }
}