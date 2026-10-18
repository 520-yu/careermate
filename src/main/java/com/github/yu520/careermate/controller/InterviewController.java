package com.github.yu520.careermate.controller;

import com.github.yu520.careermate.agent.InterviewAgent;
import com.github.yu520.careermate.model.InterviewChatRequest;
import com.github.yu520.careermate.model.InterviewReport;
import com.github.yu520.careermate.model.InterviewReportResponse;
import com.github.yu520.careermate.model.InterviewSession;
import com.github.yu520.careermate.model.InterviewStartRequest;
import com.github.yu520.careermate.model.InterviewStartResponse;
import com.github.yu520.careermate.model.InterviewTurn;
import com.github.yu520.careermate.service.AsyncTaskPublisher;
import com.github.yu520.careermate.service.InterviewSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 模拟面试入口：开场 / 对话（SSE 流式）/ 评估报告。
 */
@RestController
@RequestMapping("/api/interview")
public class InterviewController {

    /** 少于这个轮数（开场 + 一问一答）就去生成报告，评出来的东西没有参考价值。 */
    private static final int MIN_TURNS_FOR_REPORT = 3;

    private final InterviewAgent interviewAgent;
    private final InterviewSessionService interviewSessionService;
    private final AsyncTaskPublisher asyncTaskPublisher;

    public InterviewController(InterviewAgent interviewAgent,
                               InterviewSessionService interviewSessionService,
                               AsyncTaskPublisher asyncTaskPublisher) {
        this.interviewAgent = interviewAgent;
        this.interviewSessionService = interviewSessionService;
        this.asyncTaskPublisher = asyncTaskPublisher;
    }

    /**
     * 开启一场模拟面试。
     *
     * @return 会话 ID + 面试官的开场问题
     */
    @PostMapping("/start")
    public InterviewStartResponse start(@RequestBody InterviewStartRequest request) {
        if (request.resume() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "缺少 resume：请先调用 /api/resume/upload 获取简历画像");
        }
        if (request.jd() == null || request.jd().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 jd：请提供目标岗位的 JD 原文");
        }

        // 检索放在开场做一次，整场复用（见 InterviewSession.referenceQuestions 的说明）。
        String reference = interviewAgent.prepareReference(request.resume(), request.jd(), request.matchResult());
        InterviewSession session = interviewSessionService.create(
                request.resume(), request.jd(), request.matchResult(), reference);

        String firstQuestion = interviewAgent.open(session);
        interviewSessionService.appendInterviewerTurn(session.sessionId(), firstQuestion);

        return new InterviewStartResponse(session.sessionId(), firstQuestion);
    }

    /**
     * 提交一轮回答，流式返回面试官的回应。
     *
     * <p>返回 {@code text/event-stream}。注意：浏览器的 {@code EventSource} 只支持 GET，
     * 用不了这个 POST 接口；前端需要用 {@code fetch} + 读取响应流，命令行用 {@code curl -N} 即可。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody InterviewChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 message：请提交候选人的回答");
        }
        InterviewSession session = requireSession(request.sessionId());

        // 先把候选人这一轮落库，再开始生成。
        // 这样即使模型这一轮生成失败，候选人说过的话也不会丢。
        List<InterviewTurn> history = interviewSessionService.getTranscript(session.sessionId());
        interviewSessionService.appendCandidateTurn(session.sessionId(), request.message());

        // 流式返回没法「返回后再写库」，所以用 doOnNext 边收边攒、doOnComplete 落库。
        // 用 StringBuffer 而非 StringBuilder：不能假定上游所有信号都在同一个线程上到达。
        // 若流中途出错，doOnComplete 不会被触发 —— 这是有意的：
        // 不把一段半截的、失败的回复当成面试官的正式提问记进流水。
        StringBuffer collected = new StringBuffer();
        return interviewAgent.answer(session, history, request.message())
                .doOnNext(collected::append)
                .doOnComplete(() -> interviewSessionService.appendInterviewerTurn(
                        session.sessionId(), collected.toString()));
    }

    /**
     * 获取评估报告（异步生成）。
     *
     * <p>调用约定：首次调用会触发后台生成并返回 {@code GENERATING}，
     * 隔几秒再调一次即可拿到 {@code READY} + 报告。报告生成后会缓存在 Redis 里，
     * 之后每次调用都直接返回、不会再触发模型调用。
     *
     * @param sessionId 开场时返回的会话 ID
     */
    @GetMapping("/{sessionId}/report")
    public InterviewReportResponse report(@PathVariable("sessionId") String sessionId) {
        requireSession(sessionId);

        // 已生成就直接返回，不重复触发。
        InterviewReport cached = interviewSessionService.getReport(sessionId);
        if (cached != null) {
            return InterviewReportResponse.ready(cached);
        }

        List<InterviewTurn> transcript = interviewSessionService.getTranscript(sessionId);
        if (transcript.size() < MIN_TURNS_FOR_REPORT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "面试记录还太少（当前 %d 轮），至少完成一问一答再生成报告".formatted(transcript.size()));
        }

        // SETNX 抢占「生成中」标记：只有抢到的请求负责投递。
        // 这个接口会被轮询，若不抢占，多个并发请求会各自投递一条消息，
        // 同一次面试被生成好几份报告、白烧几倍 token。
        if (interviewSessionService.tryMarkReportPending(sessionId)
                && !asyncTaskPublisher.publishReportGenerate(sessionId)) {
            // 投递失败必须把标记清掉，否则用户会一直看到 GENERATING 却永远等不到结果。
            interviewSessionService.clearReportPending(sessionId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "报告生成任务投递失败（RocketMQ 可能不可用），请稍后重试");
        }

        return InterviewReportResponse.generating();
    }

    /** 取出会话，不存在（或已过期）时返回 404 而不是让后续逻辑空指针。 */
    private InterviewSession requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 sessionId");
        }
        InterviewSession session = interviewSessionService.getSession(sessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "会话不存在或已过期（有效期 6 小时）：" + sessionId);
        }
        return session;
    }
}
