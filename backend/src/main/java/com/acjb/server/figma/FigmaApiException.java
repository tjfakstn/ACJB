package com.acjb.server.figma;

/** Figma 호출 실패·응답 형식 오류. 메시지에 토큰을 넣지 않는다(AGENTS.md 4절 7번) */
public class FigmaApiException extends RuntimeException {

    /** Figma가 돌려준 HTTP 상태. 응답을 못 받았거나(시간 초과·네트워크) 응답 형식 오류면 0 */
    private final int status;
    /**
     * 서버 토큰 문제(만료·잘못된 토큰). Figma는 이 경우도 파일 권한 없음과 같은 403으로 주고 본문 err에 "Token expired"·"Invalid token"을 담는다
     * — 사용자의 파일 권한 문제로 안내하지 않도록 따로 표시한다(2026-11-07)
     */
    private final boolean tokenProblem;

    public FigmaApiException(String message) {
        this(message, 0, null);
    }

    public FigmaApiException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public FigmaApiException(String message, int status, Throwable cause) {
        this(message, status, false, cause);
    }

    public FigmaApiException(String message, int status, boolean tokenProblem, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.tokenProblem = tokenProblem;
    }

    public boolean tokenProblem() {
        return tokenProblem;
    }

    public int status() {
        return status;
    }
}
