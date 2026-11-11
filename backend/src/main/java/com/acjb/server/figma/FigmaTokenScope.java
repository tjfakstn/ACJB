package com.acjb.server.figma;

import java.util.function.Supplier;

/**
 * 이번 작업(요청·비동기 작업 스레드)에서 서버 토큰 대신 쓸 Figma 토큰(2026-11-11).
 * 우리 서버 계정이 읽지 못하는 파일(다른 팀 무료 플랜 한도·내보내기 막힘·권한 없음)은 프로젝트 소유자가 넣은 본인 토큰으로 읽는다.
 * 값은 FigmaRestClient만 읽고, 로그·예외 메시지에 넣지 않는다
 */
public final class FigmaTokenScope {

    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();

    private FigmaTokenScope() {
    }

    /** token이 null이면 서버 토큰을 그대로 쓴다. 끝나면 이전 값으로 되돌린다 */
    public static <T> T with(String token, Supplier<T> body) {
        String previous = TOKEN.get();
        if (token == null || token.isBlank()) {
            TOKEN.remove();
        } else {
            TOKEN.set(token);
        }
        try {
            return body.get();
        } finally {
            if (previous == null) {
                TOKEN.remove();
            } else {
                TOKEN.set(previous);
            }
        }
    }

    public static void run(String token, Runnable body) {
        with(token, () -> {
            body.run();
            return null;
        });
    }

    static String current() {
        return TOKEN.get();
    }

    public static boolean usingUserToken() {
        return TOKEN.get() != null;
    }
}
