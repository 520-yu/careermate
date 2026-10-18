package com.github.yu520.careermate.service;

import com.github.yu520.careermate.model.ResumeProfile;
import com.github.yu520.careermate.model.ResumeVectorizeMessage;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 简历向量化消费者：把上传的简历异步灌入向量库。
 *
 * <p>为什么要异步：向量化要调用 Embedding 模型，是纯等待型耗时。
 * 放在上传请求里会让用户为了「归档一份简历」多等几百毫秒到数秒，
 * 而这份向量当下并不阻塞上传结果的返回。
 *
 * <p>注意这里<b>必须</b>显式标注 {@code @Component}：
 * {@code @RocketMQMessageListener} 本身不带 {@code @Component}（已从字节码确认），
 * 少了它这个 Bean 根本不会被注册，消费者永远不触发，而且不会有任何报错。
 */
@Component
@ConditionalOnProperty(name = "careermate.async.mq-enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = AsyncTopics.TOPIC_RESUME_VECTORIZE,
        consumerGroup = AsyncTopics.GROUP_RESUME_VECTORIZE,
        // 限制重试次数：默认 16 次对「调模型」这类任务来说太久了，
        // 失败大概率是模型或向量库不可用，重试 3 次仍失败就该放弃、留日志人工看。
        maxReconsumeTimes = 3)
public class ResumeVectorizeConsumer implements RocketMQListener<ResumeVectorizeMessage> {

    private static final Logger log = LoggerFactory.getLogger(ResumeVectorizeConsumer.class);

    private final RagService ragService;

    public ResumeVectorizeConsumer(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public void onMessage(ResumeVectorizeMessage message) {
        ResumeProfile resume = message.resume();
        if (resume == null || message.objectName() == null) {
            // 消息本身不完整，重试多少次都一样，直接丢弃并留痕。
            log.warn("简历向量化消息内容不完整，已丢弃：objectName={}", message.objectName());
            return;
        }

        // 用 objectName 派生确定性 id：RocketMQ 是「至少一次」投递，
        // 同一条消息可能被消费多次，确定性 id 让重复消费退化成覆盖写而不是堆重复数据。
        String documentId = UUID.nameUUIDFromBytes(
                message.objectName().getBytes(StandardCharsets.UTF_8)).toString();

        Document document = new Document(documentId, resume.toPromptText(), Map.of(
                "source", RagService.SOURCE_RESUME,
                "objectName", message.objectName()));

        // 这里不吞异常：抛出去才能让 RocketMQ 按 maxReconsumeTimes 重试。
        ragService.add(List.of(document));
        log.info("简历已向量化：objectName={}", message.objectName());
    }
}
