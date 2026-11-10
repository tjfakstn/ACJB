package com.acjb.server.github;

/** GitHub 호출 실패. 메시지에 토큰을 넣지 않는다(AGENTS.md 4절 7번) */
public class GithubApiException extends RuntimeException {

    /** GitHub가 돌려준 HTTP 상태. 응답을 못 받았거나(시간 초과·네트워크) 형식 오류면 0 */
    private final int status;

    public GithubApiException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
