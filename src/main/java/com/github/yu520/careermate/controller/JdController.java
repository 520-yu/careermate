package com.github.yu520.careermate.controller;

import com.github.yu520.careermate.agent.JdMatcherAgent;
import com.github.yu520.careermate.error.BusinessException;
import com.github.yu520.careermate.error.ErrorCode;
import com.github.yu520.careermate.model.JdMatchRequest;
import com.github.yu520.careermate.model.JdMatchResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JD 匹配入口。
 */
@RestController
@RequestMapping("/api/jd")
public class JdController {

    private final JdMatcherAgent jdMatcherAgent;

    public JdController(JdMatcherAgent jdMatcherAgent) {
        this.jdMatcherAgent = jdMatcherAgent;
    }

    /**
     * 把简历与目标 JD 做匹配分析。
     *
     * @param request 简历画像 + JD 原文
     */
    @PostMapping("/match")
    public JdMatchResult match(@RequestBody JdMatchRequest request) {
        if (request.resume() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "缺少 resume：请先调用 /api/resume/upload 获取简历画像");
        }
        if (request.jd() == null || request.jd().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "缺少 jd：请提供目标岗位的 JD 原文");
        }
        return jdMatcherAgent.match(request.resume(), request.jd());
    }
}
