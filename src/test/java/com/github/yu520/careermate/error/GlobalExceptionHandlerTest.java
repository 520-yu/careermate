package com.github.yu520.careermate.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GlobalExceptionHandler} 的映射测试。
 *
 * <p>为什么值得测：这一层是「对外说什么」的唯一出口。改错一个映射的代价不是崩溃，
 * 而是**把内部异常信息泄露给调用方**，或者把 404 错报成 400 让调用方的重试逻辑判断错 ——
 * 两者都不会被任何"能跑通"的测试发现。
 *
 * <p>不需要 Spring 上下文：直接 new 出 handler 调方法，
 * 请求对象用 {@link MockHttpServletRequest}（它就在 spring-test 里，不必自己造）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }

    @Test
    @DisplayName("业务异常：状态码、业务码、消息、路径都正确返回")
    void businessExceptionMapsToItsOwnCode() {
        BusinessException ex = new BusinessException(ErrorCode.SESSION_NOT_FOUND, "会话不存在或已过期");

        ResponseEntity<ApiError> response = handler.handleBusiness(ex, request("/api/interview/x/report"));

        assertEquals(404, response.getStatusCode().value());
        ApiError body = response.getBody();
        assertNotNull(body);
        assertEquals("SESSION_NOT_FOUND", body.code());
        assertEquals("会话不存在或已过期", body.message());
        assertEquals(404, body.status());
        assertEquals("/api/interview/x/report", body.path());
        assertNotNull(body.traceId(), "每次错误都要有追踪号");
        assertEquals(8, body.traceId().length());
    }

    @Test
    @DisplayName("业务异常没带 message 时，回退到 ErrorCode 的默认文案")
    void businessExceptionFallsBackToDefaultMessage() {
        BusinessException ex = new BusinessException(ErrorCode.JD_MATCH_FAILED);

        ApiError body = handler.handleBusiness(ex, request("/api/jd/match")).getBody();

        assertNotNull(body);
        assertEquals("JD 匹配失败", body.message());
    }

    @Test
    @DisplayName("未预期异常：对外只给通用文案，绝不泄露内部异常信息")
    void unexpectedExceptionDoesNotLeakInternals() {
        IllegalStateException ex = new IllegalStateException(
                "面试会话反序列化失败：Redis 连接被拒绝 at com.github.yu520.careermate.service.InterviewSessionService");

        ResponseEntity<ApiError> response = handler.handleUnexpected(ex, request("/api/interview/x/report"));

        assertEquals(500, response.getStatusCode().value());
        ApiError body = response.getBody();
        assertNotNull(body);
        assertEquals("INTERNAL_ERROR", body.code());
        assertEquals(ErrorCode.INTERNAL_ERROR.defaultMessage(), body.message());
        // 这两条断言是本次改造的核心安全属性
        assertFalse(body.message().contains("Redis"), "消息里不能出现内部细节，实际：" + body.message());
        assertFalse(body.message().contains("com.github"), "消息里不能出现包名，实际：" + body.message());
    }

    @Test
    @DisplayName("上传超限映射成 413 + FILE_TOO_LARGE")
    void maxUploadSizeMapsToFileTooLarge() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(10 * 1024 * 1024);

        ResponseEntity<ApiError> response = handler.handleTooLarge(ex, request("/api/resume/upload"));

        assertEquals(413, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("FILE_TOO_LARGE", response.getBody().code());
        assertTrue(response.getBody().message().contains("10MB"));
    }

    @Test
    @DisplayName("兼容旧的 ResponseStatusException：保留原状态码，不被压成 400")
    void responseStatusExceptionKeepsOriginalStatus() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或已过期");

        ResponseEntity<ApiError> response = handler.handleResponseStatus(ex, request("/api/interview/x/report"));

        assertEquals(404, response.getStatusCode().value(), "404 不能因为映射变成 400");
        assertNotNull(response.getBody());
        assertEquals("SESSION_NOT_FOUND", response.getBody().code());
        assertEquals(404, response.getBody().status());
    }

    @Test
    @DisplayName("每次错误的 traceId 都不同（否则没法在日志里区分两次失败）")
    void traceIdsAreUnique() {
        BusinessException ex = new BusinessException(ErrorCode.INVALID_REQUEST, "x");

        String first = handler.handleBusiness(ex, request("/a")).getBody().traceId();
        String second = handler.handleBusiness(ex, request("/b")).getBody().traceId();

        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("用错 HTTP 方法映射成 405，不能被兜底成 500")
    void methodNotSupportedMapsTo405() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("GET", java.util.List.of("POST"));

        ResponseEntity<ApiError> response = handler.handleMethodNotAllowed(ex, request("/api/resume/upload"));

        assertEquals(405, response.getStatusCode().value(), "调用方用错方法不是服务端故障");
        assertNotNull(response.getBody());
        assertEquals("METHOD_NOT_ALLOWED", response.getBody().code());
        assertTrue(response.getBody().message().contains("POST"), "应该告诉调用方正确的方法是什么");
    }

    @Test
    @DisplayName("访问不存在的路径映射成 404，不能被兜底成 500")
    void unknownPathMapsTo404() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/api/nope");

        ResponseEntity<ApiError> response = handler.handleNoResource(ex, request("/api/nope"));

        assertEquals(404, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("RESOURCE_NOT_FOUND", response.getBody().code());
    }
}
