package com.acjb.server.liveqa;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

import com.acjb.server.common.ApiException;

/** 실시간 QA 요청 값 검사 — 통과하지 못하면 400(VALIDATION_FAILED), 아무것도 저장하기 전에 부른다(N2) */
final class LiveQaValidation {

    static final int PATH_MAX = 500;
    static final int VIEWPORT_MIN = 320;
    static final int VIEWPORT_MAX_WIDTH = 3840;
    static final int VIEWPORT_MAX_HEIGHT = 2160;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    record Viewport(int width, int height) {
    }

    private LiveQaValidation() {
    }

    static String pagePath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("필수 항목이 비어 있습니다: page_path");
        }
        String path = raw.trim();
        if (!path.startsWith("/") || path.startsWith("//") || path.length() > PATH_MAX || path.chars().anyMatch(Character::isWhitespace)) {
            throw ApiException.badRequest("page_path는 /로 시작하는 " + PATH_MAX + "자 이하 경로여야 합니다(공백 없이).");
        }
        return path;
    }

    static Viewport viewport(LiveQaRequests.ViewportInput v) {
        if (v == null || v.width() == null || v.height() == null) {
            throw ApiException.badRequest("필수 항목이 비어 있습니다: viewport");
        }
        if (v.width() < VIEWPORT_MIN || v.width() > VIEWPORT_MAX_WIDTH || v.height() < VIEWPORT_MIN || v.height() > VIEWPORT_MAX_HEIGHT) {
            throw ApiException.badRequest("viewport는 너비 " + VIEWPORT_MIN + "~" + VIEWPORT_MAX_WIDTH + ", 높이 " + VIEWPORT_MIN + "~"
                + VIEWPORT_MAX_HEIGHT + " 사이여야 합니다.");
        }
        return new Viewport(v.width(), v.height());
    }

    static String requestId(String raw) {
        if (raw == null || !REQUEST_ID.matcher(raw).matches()) {
            throw ApiException.badRequest("client_request_id는 영문·숫자·-·_ 1~64자여야 합니다.");
        }
        return raw;
    }

    /** 공백만 있는 값도 빈 값이다(N1) */
    static String required(String raw, String field, int max) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("필수 항목이 비어 있습니다: " + field);
        }
        String v = raw.trim();
        if (v.length() > max) {
            throw ApiException.badRequest(field + "은(는) " + max + "자 이하여야 합니다.");
        }
        return v;
    }

    static String optional(String raw, String field, int max) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return required(raw, field, max);
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest(field + " 값이 올바르지 않습니다: " + raw);
        }
    }

    static Long idOrNull(String raw) {
        try {
            return raw == null ? null : Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 배포 주소 뒤에 경로를 붙인다 — 하위 경로에 배포된 사이트(예 GitHub Pages /repo/)도 그 아래 경로로 연다. 쿼리·조각은 버린다 */
    static String join(String base, String path) {
        URI u = URI.create(base.trim());
        String basePath = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
        String authority = u.getRawAuthority() == null ? "" : u.getRawAuthority();
        return u.getScheme() + "://" + authority + basePath + path;
    }
}
