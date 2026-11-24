package com.acjb.server.domain;

/**
 * 서버 브라우저가 캡처 순간 읽은 화면 요소 하나 — 위치는 페이지 맨 위 기준 CSS 픽셀(2026-11-20, AC30·AC31).
 * selector는 그 요소를 다시 찾기 위한 CSS 경로(id가 있으면 #id), text는 보이는 글자 앞부분이다.
 */
public record PageElement(String selector, String text, int x, int y, int width, int height) {

    public static final int SELECTOR_MAX = 500;
    public static final int TEXT_MAX = 200;

    public boolean contains(int px, int py) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }

    public long area() {
        return (long) width * height;
    }
}
