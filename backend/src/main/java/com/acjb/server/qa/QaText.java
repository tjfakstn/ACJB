package com.acjb.server.qa;

import java.util.EnumMap;
import java.util.Map;

import com.acjb.server.domain.StyleProperty;

/** 첫 QA 이슈 설명 문구 */
final class QaText {

    private static final Map<StyleProperty, String> LABELS = new EnumMap<>(StyleProperty.class);

    static {
        LABELS.put(StyleProperty.COLOR, "색상");
        LABELS.put(StyleProperty.FONT_WEIGHT, "글자 굵기");
        LABELS.put(StyleProperty.FONT_SIZE, "글자 크기");
        LABELS.put(StyleProperty.FONT_FAMILY, "글꼴");
        LABELS.put(StyleProperty.RADIUS, "모서리");
        LABELS.put(StyleProperty.SHADOW, "그림자");
        LABELS.put(StyleProperty.SPACING, "간격");
        LABELS.put(StyleProperty.TEXT, "문구");
    }

    private QaText() {
    }

    static String description(String nodeName, StyleProperty property, String expected, String actual, boolean demo) {
        String text = "'" + nodeName + "'의 " + LABELS.get(property) + "이(가) 시안과 달라요 — 시안 " + expected + ", 구현 " + actual;
        return demo ? "[시연용] " + text : text;
    }
}
