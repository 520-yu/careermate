package com.github.yu520.careermate.model;

/**
 * 开启一场模拟面试的请求。
 *
 * @param resume      候选人简历画像（出题依据）
 * @param jd          目标岗位 JD（出题依据）
 * @param matchResult 可选的 JD 匹配结果。传了它，面试官会优先追问 missingSkills；
 *                    不传也能正常面试，只是针对性弱一些
 */
public record InterviewStartRequest(ResumeProfile resume, String jd, JdMatchResult matchResult) {
}
