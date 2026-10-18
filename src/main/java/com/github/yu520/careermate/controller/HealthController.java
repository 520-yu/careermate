package com.github.yu520.careermate.controller;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final VectorStore vectorStore;
    private final MinioClient minioClient;
    private final ChatClient chatClient;
    private final RocketMQTemplate rocketMQTemplate;

    @Value("${minio.bucket}")
    private String bucket;

    public HealthController(JdbcTemplate jdbcTemplate,
                            StringRedisTemplate redisTemplate,
                            VectorStore vectorStore,
                            MinioClient minioClient,
                            ChatClient.Builder chatClientBuilder,
                            RocketMQTemplate rocketMQTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.vectorStore = vectorStore;
        this.minioClient = minioClient;
        this.chatClient = chatClientBuilder.build();
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @GetMapping
    public Map<String, String> health() {
        Map<String, String> result = new LinkedHashMap<>();

        try {
            Integer v = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            result.put("mysql", v == 1 ? "OK" : "FAIL");
        } catch (Exception e) {
            result.put("mysql", "FAIL: " + e.getMessage());
        }

        try {
            redisTemplate.opsForValue().set("health:test", "ok");
            String v = redisTemplate.opsForValue().get("health:test");
            result.put("redis", "ok".equals(v) ? "OK" : "FAIL");
        } catch (Exception e) {
            result.put("redis", "FAIL: " + e.getMessage());
        }

        try {
            vectorStore.similaritySearch("test");
            result.put("qdrant", "OK");
        } catch (Exception e) {
            result.put("qdrant", "FAIL: " + e.getMessage());
        }

        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            result.put("minio", exists ? "OK (bucket exists)" : "bucket missing");
        } catch (Exception e) {
            result.put("minio", "FAIL: " + e.getMessage());
        }

        try {
            String reply = chatClient.prompt()
                    .user("请只回复两个字：正常")
                    .call()
                    .content();
            result.put("deepseek", reply != null && !reply.isBlank() ? "OK" : "FAIL");
        } catch (Exception e) {
            result.put("deepseek", "FAIL: " + e.getMessage());
        }

        try {
            rocketMQTemplate.convertAndSend("health-test", "ping");
            result.put("rocketmq", "OK (sent)");
        } catch (Exception e) {
            result.put("rocketmq", "FAIL: " + e.getMessage());
        }

        return result;
    }
}