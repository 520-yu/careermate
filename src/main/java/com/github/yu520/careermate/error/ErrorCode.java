package com.github.yu520.careermate.error;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。
 *
 * <p>为什么要有它：HTTP 状态码太粗 —— 400 可能是「文件为空」「缺 resume」「缺 jd」，
 * 前端只能靠匹配 message 文本猜。有了稳定的 {@code code}，前端可以按码分支，
 * 也可以按码给出针对性的修复建议，而不用去解析中文字符串。
 *
 * <p>{@code code} 用枚举名（大写下划线），是给机器看的、永不变的契约；
 * {@code Chinese message} 是给人看的，可以随时改。
 *
 * <p>状态码的选择有意区分了两类失败：
 * <ul>
 *   <li><b>4xx</b>：调用方的问题（参数缺失、文件类型不对、会话不存在）</li>
 *   <li><b>5xx</b>：我们的问题。其中 <b>502</b> 专门表示「上游模型没给出可用结果」，
 *       和 <b>500</b>「我们自己的代码崩了」区分开 —— 这两类的排查方向完全不同：
 *       前者去看模型输出/提示词，后者去看堆栈。</li>
 * </ul>
 */
public enum ErrorCode {

    /** 请求参数不合法：缺字段、类型不对、内容为空。 */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "请求参数不合法"),

    /** 访问了不存在的接口路径。 */
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "请求的接口不存在"),

    /** 用错了 HTTP 方法，例如对只接受 POST 的接口发了 GET。 */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不被支持"),

    /** 上传的文件类型不支持。 */
    UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的文件类型"),

    /** 上传文件超过大小上限。 */
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过大小上限"),

    /** 面试会话不存在或已过期（Redis TTL 到期）。 */
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "面试会话不存在或已过期"),

    /** 报告还没生成完，需要继续轮询。 */
    REPORT_NOT_READY(HttpStatus.CONFLICT, "评估报告尚未生成完成"),

    /** 简历解析失败：模型没返回可解析的结构（JSON 坏了或字段缺失）。 */
    RESUME_PARSE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "简历解析失败"),

    /** JD 匹配失败：上游模型没给出可用结果。 */
    JD_MATCH_FAILED(HttpStatus.BAD_GATEWAY, "JD 匹配失败"),

    /** 面试开场问题生成失败。 */
    INTERVIEW_START_FAILED(HttpStatus.BAD_GATEWAY, "面试开场生成失败"),

    /** 面试追问/回答生成失败。 */
    INTERVIEW_REPLY_FAILED(HttpStatus.BAD_GATEWAY, "面试问答生成失败"),

    /** 评估报告生成失败：上游模型没给出可用结果。 */
    REPORT_GENERATE_FAILED(HttpStatus.BAD_GATEWAY, "评估报告生成失败"),

    /** 报告生成任务投递失败：消息队列不可用，属于可重试的暂时性故障。 */
    REPORT_DISPATCH_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "报告生成任务投递失败"),

    /** 存储层失败：Redis 读写、MinIO 上传等。 */
    STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "存储操作失败"),

    /** 未预期的内部错误。对外只给一句通用文案，细节写进日志。 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
