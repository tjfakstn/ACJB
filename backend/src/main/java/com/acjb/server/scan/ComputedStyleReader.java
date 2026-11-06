package com.acjb.server.scan;

/** 배포 화면에서 selector 요소의 computed style을 읽는다. 실패하면 사용자에게 보여도 되는 이유로 ScanFailedException */
public interface ComputedStyleReader {

    ComputedStyle read(String targetUrl, String selector);

    /**
     * 요소에서 읽은 값. color는 브라우저 캔버스로 sRGB로 바꾼 값(Tailwind v4의 oklch 등도 처리 — 스파이크에서 발견).
     * 투명도가 있거나 단일 px 반경이 아니면 해당 값은 null.
     *
     * @param backgroundColor Figma 프레임 fill과 짝(노드가 TEXT가 아닐 때)
     * @param textColor       Figma TEXT 노드 fill과 짝
     */
    record ComputedStyle(StyleValues.Rgb backgroundColor, StyleValues.Rgb textColor, Integer fontWeight, Double radiusPx) {

        /** Figma 노드 종류에 맞춰 비교할 값을 고른다 */
        public StyleValues forFigmaNode(boolean textNode) {
            return new StyleValues(textNode ? textColor : backgroundColor, fontWeight, radiusPx);
        }
    }
}
