package com.github.yu520.careermate.service;

import com.github.yu520.careermate.model.InterviewReportMessage;
import com.github.yu520.careermate.model.ResumeProfile;
import com.github.yu520.careermate.model.ResumeVectorizeMessage;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 异步任务投递入口。
 *
 * <p>为什么不直接在 Controller 里注入 RocketMQTemplate：
 * 一是需要统一处理「MQ 不可用」这种非致命故障，二是投递失败和投递成功
 * 对调用方意味着不同的后续动作，需要把结果显式返回出去。
 *
 * <p>投递失败<b>不抛异常</b>，而是返回 false 由调用方决定怎么办：
 * 简历向量化失败不该让「简历已经解析成功」这件事一起失败；
 * 而报告投递失败则必须让用户知道还能重试。两种语义不同，不能一刀切。
 */
@Service
public class AsyncTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskPublisher.class);

    private final RocketMQTemplate rocketMQTemplate;

    public AsyncTaskPublisher(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    /** 投递「简历向量化」任务。 */
    public boolean publishResumeVectorize(String objectName, ResumeProfile resume) {
        return send(AsyncTopics.TOPIC_RESUME_VECTORIZE, new ResumeVectorizeMessage(objectName, resume));
    }

    /** 投递「评估报告生成」任务。 */
    public boolean publishReportGenerate(String sessionId) {
        return send(AsyncTopics.TOPIC_INTERVIEW_REPORT, new InterviewReportMessage(sessionId));
    }

    private boolean send(String topic, Object payload) {
        try {
            rocketMQTemplate.convertAndSend(topic, payload);
            log.info("异步任务已投递：topic={}", topic);
            return true;
        } catch (Exception e) {
            log.warn("异步任务投递失败：topic={}, err={}", topic, e.getMessage());
            return false;
        }
    }
}
