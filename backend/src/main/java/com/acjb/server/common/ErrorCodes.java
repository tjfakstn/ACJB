package com.acjb.server.common;

/**
 * ErrorResponse.error.code 값(18개, 2026-11-05 확정·11-06 SUGGESTION_FAILED·11-07 연결·실행 3개·11-10 CAPTURE_FAILED·11-11 RATE_LIMITED 추가). openapi Error.code 설명에 상태 코드와 함께 적혀 있다 —
 * 값을 추가하면 그 설명도 같이 고친다. enum이 아닌 이유: 프레임워크 단계 오류는 HTTP 상태 이름이 온다.
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
    /** 재검증 범위 제안(AC20): 임베딩·LLM 호출 실패·스키마 위반 */
    public static final String SUGGESTION_FAILED = "SUGGESTION_FAILED";
    /** 연결하려는 Figma 파일·GitHub 저장소에 접근 권한이 없음 — GitHub 404(비공개·없는 저장소 구분 불가) 포함(AC21, 422) */
    public static final String CONNECTION_FORBIDDEN = "CONNECTION_FORBIDDEN";
    /** 연결 확인 실패 — 없는 Figma 파일·응답 지연·외부 서버 오류(AC21, 502) */
    public static final String CONNECTION_FAILED = "CONNECTION_FAILED";
    /** 지금은 할 수 없는 요청 — 연결·분석 전 실행, 실행 중 중복, 이미 성공한 실행, 분석 후 링크 변경(AC21~AC23, 409) */
    public static final String NOT_READY = "NOT_READY";
    /** 서버 브라우저가 배포 화면을 열거나 캡처하지 못함(AC30, 502) */
    public static final String CAPTURE_FAILED = "CAPTURE_FAILED";
    /** 사용량 한도를 넘음 — 프로젝트 수, 하루 실행·AI 호출·초대 횟수, 동시 화면 캡처(2026-11-11, 429) */
    public static final String RATE_LIMITED = "RATE_LIMITED";
    /** Figma 연동이 설정되지 않음(FIGMA_ACCESS_TOKEN 없음) */
    public static final String FIGMA_UNAVAILABLE = "FIGMA_UNAVAILABLE";

    private ErrorCodes() {
    }
}
