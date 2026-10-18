package com.github.yu520.careermate.model;

/**
 * 评估报告接口的响应。报告改为异步生成后，这个接口的状态就有两种了。
 *
 * <p>为什么用一个带状态的包装而不是「报告还没好就返回 404」：
 * 404 会被调用方和各类网关当成「资源不存在」，而这里的语义是「还在生成，请稍后再来」，
 * 两者必须能区分开，否则前端只能靠轮询超时来猜。
 *
 * @param status {@link #STATUS_GENERATING} 表示已受理、正在生成；
 *               {@link #STATUS_READY} 表示报告已就绪
 * @param report 就绪时的报告内容；生成中时为 {@code null}
 */
public record InterviewReportResponse(String status, InterviewReport report) {

    public static final String STATUS_GENERATING = "GENERATING";
    public static final String STATUS_READY = "READY";

    public static InterviewReportResponse generating() {
        return new InterviewReportResponse(STATUS_GENERATING, null);
    }

    public static InterviewReportResponse ready(InterviewReport report) {
        return new InterviewReportResponse(STATUS_READY, report);
    }
}
