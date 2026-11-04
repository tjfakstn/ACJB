package com.acjb.server.common;

/**
 * ErrorResponse.error.code 값. openapi에는 code가 string으로만 정의돼 있어 값 목록은 여기서 관리한다.
 * TODO: 이 목록을 openapi.yaml Error.code에 반영(팀 확정 필요)
 */
public final class ErrorCodes {
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    /** 허용되지 않은 상태 전이(AC6) */
    public static final String INVALID_TRANSITION = "INVALID_TRANSITION";

    private ErrorCodes() {
    }
}
