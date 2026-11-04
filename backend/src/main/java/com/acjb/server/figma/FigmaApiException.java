package com.acjb.server.figma;

/** Figma 호출 실패·응답 형식 오류. 메시지에 토큰을 넣지 않는다(AGENTS.md 4절 7번) */
public class FigmaApiException extends RuntimeException {

    public FigmaApiException(String message) {
        super(message);
    }

    public FigmaApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
