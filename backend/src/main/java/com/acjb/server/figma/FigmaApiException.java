package com.acjb.server.figma;

/** Figma 호출 실패·응답 형식 오류. 메시지에 토큰을 넣지 않는다(AGENTS.md 4절 7번) */
public class FigmaApiException extends RuntimeException {

    /** Figma가 돌려준 HTTP 상태. 응답을 못 받았거나(시간 초과·네트워크) 응답 형식 오류면 0 */
    private final int status;

    public FigmaApiException(String message) {
        this(message, 0, null);
    }

    public FigmaApiException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public FigmaApiException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
