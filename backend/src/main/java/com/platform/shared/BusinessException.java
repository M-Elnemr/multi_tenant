package com.platform.shared;

import org.springframework.http.HttpStatus;

/** Business error with a stable code the frontend maps to a localized message (spec section 53). */
public class BusinessException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final Object details;

    public BusinessException(HttpStatus status, String code, String message) { this(status, code, message, null); }

    /** {@code details} is extra structured data returned with the error (for example which cart lines failed). */
    public BusinessException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }

    public Object getDetails() { return details; }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }

    public static BusinessException notFound(String code, String msg) { return new BusinessException(HttpStatus.NOT_FOUND, code, msg); }
    public static BusinessException forbidden(String code, String msg) { return new BusinessException(HttpStatus.FORBIDDEN, code, msg); }
    public static BusinessException conflict(String code, String msg) { return new BusinessException(HttpStatus.CONFLICT, code, msg); }
    public static BusinessException badRequest(String code, String msg) { return new BusinessException(HttpStatus.BAD_REQUEST, code, msg); }
    public static BusinessException unauthorized(String code, String msg) { return new BusinessException(HttpStatus.UNAUTHORIZED, code, msg); }
}
