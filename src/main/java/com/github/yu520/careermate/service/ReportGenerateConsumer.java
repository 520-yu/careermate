package com.github.yu520.careermate.service;

import com.github.yu520.careermate.agent.InterviewAgent;
import com.github.yu520.careermate.model.InterviewReport;
import com.github.yu520.careermate.model.InterviewReportMessage;
import com.github.yu520.careermate.model.InterviewSession;
import com.github.yu520.careermate.model.InterviewTurn;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 评估报告生成消费者。
 *
 * <p>报告要喂进整场面试的完整流水，是典型的长耗时、纯等待型任务：
 * 同步做会让 HTTP 连接挂十几秒甚至更久，网关很可能先超时断开，
 * 用户看到的是「请求失败」，而服务器其实还在跑、白花一次模型调用。
 * 改成异步后接口立即返回「生成中」，结果回写 Redis，前端轮询即可。
 */
@Component
@ConditionalOnProperty(name = "careermate.async.mq-enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = AsyncTopics.TOPIC_INTERVIEW_REPORT,
        consumerGroup = AsyncTopics.GROUP_INTERVIEW_REPORT,
        maxReconsumeTimes = 3)
public class ReportGenerateConsumer implements RocketMQListener<InterviewReportMessage> {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerateConsumer.class);

    private final InterviewSessionService interviewSessionService;
    private final InterviewAgent interviewAgent;

    public ReportGenerateConsumer(InterviewSessionService interviewSessionService,
                                  InterviewAgent interviewAgent) {
        this.interviewSessionService = interviewSessionService;
        this.interviewAgent = interviewAgent;
    }

    @Override
    public void onMessage(InterviewReportMessage message) {
        String sessionId = message.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("报告生成消息缺少 sessionId，已丢弃");
            return;
        }

        try {
            InterviewSession session = interviewSessionService.getSession(sessionId);
            if (session == null) {
                // 会话已过期（默认 6 小时）。重试也不会有结果，直接放弃并留痕。
                log.warn("会话不存在或已过期，放弃生成报告：sessionId={}", sessionId);
                interviewSessionService.clearReportPending(sessionId);
                return;
            }

            List<InterviewTurn> transcript = interviewSessionService.getTranscript(sessionId);
            InterviewReport report = interviewAgent.evaluate(session, transcript);
            interviewSessionService.saveReport(sessionId, report);

            log.info("评估报告生成完成：sessionId={}, overallScore={}", sessionId, report.overallScore());
        } catch (Exception e) {
            // 关键：必须清掉「生成中」标记。
            // 否则用户再查会一直拿到 GENERATING，却永远等不到结果，
            // 而且因为标记还在，也不会再触发投递 —— 直接卡死。
            interviewSessionService.clearReportPending(sessionId);
            // 抛出去触发 MQ 重试；重试次数由 maxReconsumeTimes 限制。
            throw new IllegalStateException("评估报告生成失败：sessionId=" + sessionId, e);
        }
    }
}
