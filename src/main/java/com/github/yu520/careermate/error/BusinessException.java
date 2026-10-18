package com.github.yu520.careermate.error;

/**
 * 业务异常：携带一个稳定的 {@link ErrorCode}，由 {@link GlobalExceptionHandler} 统一转成响应。
 *
 * <p>为什么不用 {@code ResponseStatusException}：
 * <ol>
 *   <li>它只有 HTTP 状态码，没有业务码 —— 前端无法精确分支</li>
 *   <li>它属于 Web 层（{@code spring-web}），让 Agent / Service 依赖它会把分层搅乱。
 *       这个异常放在 error 包里，任何一层都能抛得干净。</li>
 * </ol>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
