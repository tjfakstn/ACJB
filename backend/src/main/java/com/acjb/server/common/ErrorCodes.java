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
    /** 같은 데이터에 대한 다른 요청과 겹쳐 처리하지 못함 — 다시 시도하면 된다 */
    public static final String CONCURRENT_UPDATE = "CONCURRENT_UPDATE";
    /** 예상하지 못한 서버 오류 */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    /** 이미 그 프로젝트 멤버인 사람을 초대·수락 */
    public static final String ALREADY_MEMBER = "ALREADY_MEMBER";
    /** 만료·취소·이미 수락된 초대(AC18) */
    public static final String INVITATION_CLOSED = "INVITATION_CLOSED";
    /** 소유자는 제거할 수 없다(AC19, N15) */
    public static final String OWNER_NOT_REMOVABLE = "OWNER_NOT_REMOVABLE";
    /** parse: LLM 호출 실패·거절·스키마 위반(N9) */
    public static final String PARSE_FAILED = "PARSE_FAILED";
    /** Figma 연동이 설정되지 않음(FIGMA_ACCESS_TOKEN 없음) */
    public static final String FIGMA_UNAVAILABLE = "FIGMA_UNAVAILABLE";

    private ErrorCodes() {
    }
}
