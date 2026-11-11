package com.acjb.server.figma;

/**
 * Figma 실패를 사용자에게 보여 줄 문구로(2026-11-11). 우리 서버 계정이 못 읽는 파일이면 연결 관리에서 본인 토큰을 넣으라고 안내한다.
 * 토큰 값은 넣지 않는다
 */
public final class FigmaErrors {

    private FigmaErrors() {
    }

    /** 우리 서버 계정이 이 파일을 읽지 못하는 경우(권한 없음·내보내기 막힘·오래가는 한도) — 소유자 토큰으로 풀 수 있다 */
    public static boolean needsUserToken(FigmaApiException e) {
        return !e.userToken() && !e.tokenProblem() && (e.status() == 403 || e.longRateLimit());
    }

    public static String message(FigmaApiException e) {
        if (e.tokenProblem()) {
            return e.userToken()
                ? "연결 관리에 넣은 Figma 토큰이 만료됐거나 올바르지 않아요. 새 토큰을 넣어 주세요."
                : "서버의 Figma 연동 토큰이 만료됐거나 올바르지 않아요. 관리자에게 알려 주세요.";
        }
        if (e.longRateLimit()) {
            String wait = waitText(e.retryAfterSeconds());
            return e.userToken()
                ? "입력한 Figma 토큰도 Figma 요청 한도에 걸렸어요(" + wait + " 뒤 풀림). Figma 플랜·좌석의 API 한도를 확인해 주세요."
                : "Figma가 이 파일에 대한 QAting 서버 계정의 요청을 막았어요(" + wait + " 뒤 풀림). 우리 계정이 속하지 않은 팀의 파일은 "
                    + "Figma 한도가 매우 낮아요. 연결 관리에서 본인 Figma 토큰을 넣으면 바로 다시 할 수 있어요.";
        }
        if (e.status() == 429) {
            return "Figma 요청 한도를 넘었어요. " + (e.retryAfterSeconds() > 0 ? e.retryAfterSeconds() + "초" : "잠시") + " 뒤 다시 시도해 주세요.";
        }
        if (e.status() == 403) {
            return e.userToken()
                ? "입력한 Figma 토큰으로 이 파일을 읽을 수 없어요. 파일에 접근할 수 있는 계정의 토큰인지 확인해 주세요."
                : "QAting 서버 계정이 이 Figma 파일을 읽을 수 없어요. 연결 관리에서 본인 Figma 토큰을 넣어 주세요.";
        }
        return "Figma 시안을 읽지 못했어요. 연결 상태를 확인한 뒤 다시 시도해 주세요.";
    }

    static String waitText(long seconds) {
        if (seconds >= 86_400) {
            return "약 " + Math.round(seconds / 86_400.0) + "일";
        }
        if (seconds >= 3_600) {
            return "약 " + Math.round(seconds / 3_600.0) + "시간";
        }
        return "약 " + Math.max(1, Math.round(seconds / 60.0)) + "분";
    }
}
