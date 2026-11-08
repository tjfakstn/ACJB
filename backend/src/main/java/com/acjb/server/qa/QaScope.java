package com.acjb.server.qa;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.acjb.server.domain.StyleProperty;

/**
 * 첫 QA 검증 범위(시안 QA-01, SPEC AC23, 2026-11-08). 각 범위가 비교할 StyleMismatch.property.
 * property 범위의 최종 확정은 첫 QA 스파이크 뒤(N4) — 지금은 비교기가 이 표를 따른다
 */
public enum QaScope {
    SIZE_SPACING(EnumSet.of(StyleProperty.SPACING)),
    COLOR_TYPOGRAPHY(EnumSet.of(StyleProperty.COLOR, StyleProperty.FONT_WEIGHT, StyleProperty.FONT_FAMILY)),
    BORDER_RADIUS(EnumSet.of(StyleProperty.RADIUS, StyleProperty.SHADOW)),
    LAYOUT(EnumSet.of(StyleProperty.SPACING)),
    PRESENCE(EnumSet.of(StyleProperty.TEXT));

    private final Set<StyleProperty> properties;

    QaScope(Set<StyleProperty> properties) {
        this.properties = properties;
    }

    public Set<StyleProperty> properties() {
        return properties;
    }

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 고른 범위 전체의 property — 정해진 순서(enum 순서) */
    public static List<StyleProperty> propertiesOf(Set<QaScope> scopes) {
        EnumSet<StyleProperty> all = EnumSet.noneOf(StyleProperty.class);
        scopes.forEach(s -> all.addAll(s.properties));
        return List.copyOf(all);
    }
}
