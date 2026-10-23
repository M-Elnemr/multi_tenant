package com.platform.shared;

import org.springframework.http.HttpStatus;

/** Business error with a stable code the frontend maps to a localized message (spec section 53). */
public class BusinessException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private java.util.Map<String, Object> params = java.util.Map.of();

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }

    /** Numbers or names the message needs ("max 5 MB"): sent as `params` so every language can put them in its own sentence. */
    public java.util.Map<String, Object> getParams() { return params; }
    public BusinessException with(String key, Object value) {
        params = new java.util.LinkedHashMap<>(params);
        params.put(key, value);
        return this;
    }

    public static BusinessException notFound(String code, String msg) { return new BusinessException(HttpStatus.NOT_FOUND, code, msg); }
    public static BusinessException forbidden(String code, String msg) { return new BusinessException(HttpStatus.FORBIDDEN, code, msg); }
    public static BusinessException conflict(String code, String msg) { return new BusinessException(HttpStatus.CONFLICT, code, msg); }
    public static BusinessException badRequest(String code, String msg) { return new BusinessException(HttpStatus.BAD_REQUEST, code, msg); }
    public static BusinessException unauthorized(String code, String msg) { return new BusinessException(HttpStatus.UNAUTHORIZED, code, msg); }
}
