package com.github.yu520.careermate.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.yu520.careermate.model.InterviewReport;
import com.github.yu520.careermate.model.InterviewSession;
import com.github.yu520.careermate.model.InterviewTurn;
import com.github.yu520.careermate.model.JdMatchResult;
import com.github.yu520.careermate.model.ResumeProfile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 模拟面试的会话与对话流水存储（Redis）。
 *
 * <p>为什么用 Redis 而不是 MySQL：这些数据是「会话级临时状态」，
 * 生命周期就是一场面试（几十分钟），不需要事务、不需要关联查询、也不需要长期留存。
 * 用 Redis 天然带过期，省掉一套清理任务；真要做历史归档，阶段 6 之后另说。
 *
 * <p>会话状态与对话流水分开存两个 key：
 * 前者每场只写一次、读多次；后者是不断追加的列表。
 * 用 Redis List 存流水（而不是「读出来-改-JSON-写回去」）是为了避免并发追加时互相覆盖。
 */
@Service
public class InterviewSessionService {

    private static final String SESSION_KEY_PREFIX = "careermate:interview:session:";
    private static final String TRANSCRIPT_KEY_PREFIX = "careermate:interview:transcript:";
    private static final String REPORT_KEY_PREFIX = "careermate:interview:report:";
    private static final String REPORT_PENDING_KEY_PREFIX = "careermate:interview:report:pending:";

    /** 会话有效期。取 6 小时：足够一场面试 + 事后回看报告，又不会长期占内存。 */
    private static final Duration TTL = Duration.ofHours(6);

    /**
     * 「报告生成中」标记的有效期，比会话短得多。
     * 它只是防重复投递的临时锁：若消费者进程崩了没来得及清标记，
     * 10 分钟后自动失效，用户还能重新触发，不会永久卡在 GENERATING。
     */
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public InterviewSessionService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 创建一场面试，返回新的会话。referenceQuestions 由调用方在开场时一次性检索后传入。 */
    public InterviewSession create(ResumeProfile resume, String jd, JdMatchResult matchResult,
                                   String referenceQuestions) {
        InterviewSession session = new InterviewSession(
                UUID.randomUUID().toString(),
                resume,
                jd,
                matchResult,
                referenceQuestions,
                now());
        redisTemplate.opsForValue().set(sessionKey(session.sessionId()), toJson(session), TTL);
        return session;
    }

    /** 读取会话状态；不存在（或已过期）时返回 null，由调用方决定怎么报错。 */
    public InterviewSession getSession(String sessionId) {
        String json = redisTemplate.opsForValue().get(sessionKey(sessionId));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, InterviewSession.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("面试会话反序列化失败：" + sessionId, e);
        }
    }

    /** 读取完整对话流水。这里不做任何截断 —— 评估报告需要看到全部回答。 */
    public List<InterviewTurn> getTranscript(String sessionId) {
        List<String> raw = redisTemplate.opsForList().range(transcriptKey(sessionId), 0, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<InterviewTurn> turns = new ArrayList<>(raw.size());
        for (String json : raw) {
            try {
                turns.add(objectMapper.readValue(json, InterviewTurn.class));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("面试流水反序列化失败：" + sessionId, e);
            }
        }
        return turns;
    }

    /** 追加一轮「候选人」发言。时间戳由服务统一生成，避免各调用方写出不同格式。 */
    public void appendCandidateTurn(String sessionId, String content) {
        appendTurn(sessionId, InterviewTurn.candidate(content, now()));
    }

    /** 追加一轮「面试官」发言。 */
    public void appendInterviewerTurn(String sessionId, String content) {
        appendTurn(sessionId, InterviewTurn.interviewer(content, now()));
    }

    /** 追加一轮对话。每次追加都续一次过期时间，避免面试进行到一半 key 先过期。 */
    private void appendTurn(String sessionId, InterviewTurn turn) {
        String key = transcriptKey(sessionId);
        redisTemplate.opsForList().rightPush(key, toJson(turn));
        redisTemplate.expire(key, TTL);
    }

    // ==================== 评估报告（异步生成，结果缓存在 Redis） ====================

    /** 读取已生成的报告；未生成时返回 null。 */
    public InterviewReport getReport(String sessionId) {
        String json = redisTemplate.opsForValue().get(reportKey(sessionId));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, InterviewReport.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("评估报告反序列化失败：" + sessionId, e);
        }
    }

    /** 保存生成好的报告，并顺手清掉「生成中」标记。 */
    public void saveReport(String sessionId, InterviewReport report) {
        redisTemplate.opsForValue().set(reportKey(sessionId), toJson(report), TTL);
        clearReportPending(sessionId);
    }

    /**
     * 抢占「生成中」标记，返回是否抢到。
     *
     * <p>为什么用 SETNX 而不是「先查后写」：报告接口是被轮询的，
     * 多个并发请求会同时发现「没有报告」并各自投递一条消息，
     * 结果是同一次面试被重复生成好几份报告、白烧好几倍 token。
     * SETNX 是原子的，天然避免这个竞态。
     */
    public boolean tryMarkReportPending(String sessionId) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(pendingKey(sessionId), "1", PENDING_TTL));
    }

    /** 清除「生成中」标记，让用户可以重新触发。生成失败时必须调用。 */
    public void clearReportPending(String sessionId) {
        redisTemplate.delete(pendingKey(sessionId));
    }

    private String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private String reportKey(String sessionId) {
        return REPORT_KEY_PREFIX + sessionId;
    }

    private String pendingKey(String sessionId) {
        return REPORT_PENDING_KEY_PREFIX + sessionId;
    }

    private String transcriptKey(String sessionId) {
        return TRANSCRIPT_KEY_PREFIX + sessionId;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("面试数据序列化失败", e);
        }
    }

    private String now() {
        return LocalDateTime.now().format(TIMESTAMP);
    }
}
