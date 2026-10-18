package com.github.yu520.careermate.model;

/**
 * 简历向量化任务的消息体。
 *
 * @param objectName 简历在 MinIO 中的对象名。它同时充当文档 id 的来源 ——
 *                   RocketMQ 是「至少一次」投递，同一条消息可能被消费多次，
 *                   用确定性 id 才能保证重复消费只是覆盖写，而不是在库里堆出重复文档。
 * @param resume     已解析好的简历画像，由上传接口顺带带过来，
 *                   免得消费者再去调一次模型重新解析
 */
public record ResumeVectorizeMessage(String objectName, ResumeProfile resume) {
}
