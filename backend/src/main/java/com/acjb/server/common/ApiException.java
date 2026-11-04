package com.acjb.server.common;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/** 서비스가 정해진 HTTP 상태로 거부할 때 던진다. GlobalExceptionHandler가 ErrorResponse로 바꾼다 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, message);
    }
}
