package com.github.yu520.careermate.service;

/**
 * 异步任务的 topic 与消费者组名。
 *
 * <p>单独放一个类是因为它们是「生产者与消费者之间的契约」：
 * 两边都引用同一组常量，改名时一处生效。若各自写字符串字面量，
 * 一旦拼写不一致，消息会被投递到一个永远没人消费的 topic ——
 * RocketMQ 不会报错，只会静静地堆在那里。
 */
public final class AsyncTopics {

    /** 简历向量化：上传成功后异步把简历灌入向量库。 */
    public static final String TOPIC_RESUME_VECTORIZE = "careermate-resume-vectorize";
    public static final String GROUP_RESUME_VECTORIZE = "careermate-resume-vectorize-group";

    /** 面试评估报告生成：报告接口首次调用时投递，生成结果回写 Redis。 */
    public static final String TOPIC_INTERVIEW_REPORT = "careermate-interview-report";
    public static final String GROUP_INTERVIEW_REPORT = "careermate-interview-report-group";

    private AsyncTopics() {
    }
}
