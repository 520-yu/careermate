package com.github.yu520.careermate.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * 全局异常出口：把任何异常都收敛成同一种 {@link ApiError} 结构。
 *
 * <p>为什么要做这件事：在此之前每个 Controller 各自 {@code throw new ResponseStatusException(...)}，
 * 响应体是 Spring 默认的 {@code {timestamp,status,error,path}}，靠
 * {@code server.error.include-message=always} 把异常信息透出来。这有两个问题：
 * <ul>
 *   <li>前端只能按 HTTP 状态码猜错误类型（400 可能是缺 resume，也可能是缺 jd）</li>
 *   <li>{@code include-message=always} 在生产环境会把内部异常细节（甚至堆栈摘要）暴露给调用方</li>
 * </ul>
 * 现在由这一层决定「对外说什么」，内部细节只进日志，并且带 traceId 便于对照。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：这是预期内的失败，按它自带的错误码返回。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest request) {
        String traceId = newTraceId();
        log.warn("[traceId={}] 业务异常 {} -> {}（{}）",
                traceId, request.getRequestURI(), ex.errorCode().name(), ex.getMessage());
        return build(ex.errorCode(), ex.getMessage(), request, traceId);
    }

    /** 缺少必填查询参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        String traceId = newTraceId();
        String message = "缺少必填参数：" + ex.getParameterName();
        log.warn("[traceId={}] {}", traceId, message);
        return build(ErrorCode.INVALID_REQUEST, message, request, traceId);
    }

    /** 参数类型不对，例如把 duration 传成了字母。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String traceId = newTraceId();
        String message = "参数 " + ex.getName() + " 的类型不正确";
        log.warn("[traceId={}] {}", traceId, message);
        return build(ErrorCode.INVALID_REQUEST, message, request, traceId);
    }

    /** 请求体不是合法 JSON，或是必填字段缺失导致反序列化失败。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        String traceId = newTraceId();
        log.warn("[traceId={}] 请求体无法解析：{}", traceId, ex.getMessage());
        return build(ErrorCode.INVALID_REQUEST, "请求体不是合法的 JSON，或缺少必填字段", request, traceId);
    }

    /** 上传文件超过 spring.servlet.multipart 的配置上限。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        String traceId = newTraceId();
        log.warn("[traceId={}] 上传超限：{}", traceId, ex.getMessage());
        return build(ErrorCode.FILE_TOO_LARGE,
                "上传文件超过上限（服务端限制 10MB，请压缩后重试）", request, traceId);
    }

    /**
     * 兜底的 {@link ResponseStatusException}：改造过程中可能还有地方在抛它，
     * 这里保持兼容 —— 按它的状态码映射成最接近的业务码，但**保留原始状态码**，
     * 不能因为映射而把 404 变成 400（那会让调用方的重试逻辑判断错）。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(
            ResponseStatusException ex, HttpServletRequest request) {
        String traceId = newTraceId();
        int status = ex.getStatusCode().value();
        ErrorCode code = mapStatus(status);
        String message = (ex.getReason() == null || ex.getReason().isBlank())
                ? code.defaultMessage()
                : ex.getReason();
        log.warn("[traceId={}] ResponseStatusException {} -> {}（映射为 {}）",
                traceId, request.getRequestURI(), status, code.name());

        ApiError body = new ApiError(code.name(), message, status,
                request == null ? null : request.getRequestURI(), traceId, java.time.Instant.now());
        return ResponseEntity.status(status).body(body);
    }

    /** 状态码 → 业务码的最小映射，只覆盖本项目实际会出现的几种。 */
    private static ErrorCode mapStatus(int status) {
        return switch (status) {
            case 404 -> ErrorCode.SESSION_NOT_FOUND;
            case 413 -> ErrorCode.FILE_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_FILE_TYPE;
            case 503 -> ErrorCode.REPORT_DISPATCH_FAILED;
            default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
        };
    }

    /**
     * 其他所有异常：对外只说「服务内部错误」，细节连同 traceId 写进日志。
     *
     * <p>这是和之前相比最关键的行为变化 —— 不再把内部异常信息直接回给调用方。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = newTraceId();
        log.error("[traceId={}] 未预期异常 {} ", traceId, request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, null, request, traceId);
    }

    private static ResponseEntity<ApiError> build(
            ErrorCode code, String message, HttpServletRequest request, String traceId) {
        ApiError body = ApiError.of(code, message, request == null ? null : request.getRequestURI(), traceId);
        return ResponseEntity.status(code.status()).body(body);
    }

    /** 八位足够在日志里定位，又不至于让响应体变得难念。 */
    private static String newTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
