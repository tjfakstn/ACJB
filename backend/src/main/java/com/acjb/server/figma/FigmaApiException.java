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
    /** 429일 때 Figma가 Retry-After로 알려 준 대기 초. 모르면 -1 */
    private final long retryAfterSeconds;
    /**
     * 파일 소유자가 "뷰어의 복사·공유·내보내기"를 꺼 둔 파일. 링크로 누구나 볼 수 있어도 Figma API는 403 "File not exportable"로 내용을 주지 않는다
     * — 보기 권한 문제로 안내하면 사용자가 고칠 곳을 못 찾는다(2026-11-11)
     */
    private boolean notExportable;

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
        this(message, status, tokenProblem, -1, cause);
    }

    public FigmaApiException(String message, int status, boolean tokenProblem, long retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.tokenProblem = tokenProblem;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public FigmaApiException markNotExportable() {
        this.notExportable = true;
        return this;
    }

    public boolean notExportable() {
        return notExportable;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean tokenProblem() {
        return tokenProblem;
    }

    public int status() {
        return status;
    }
}
