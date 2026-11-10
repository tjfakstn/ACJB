package com.acjb.server.qa;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.acjb.server.domain.StyleProperty;

/**
 * 첫 QA 검증 범위(시안 QA-01, SPEC 4절·AC23). 각 범위가 비교할 StyleMismatch.property — 2026-11-10 스파이크로 확정(N4).
 * 측정할 수 없는 범위(레이아웃, UI 요소 존재 여부)는 뺐다 — 고를 수 있으면 "검사했는데 차이 없음"으로 오해된다
 */
public enum QaScope {
    /** 간격·여백 — padding·gap */
    SIZE_SPACING(EnumSet.of(StyleProperty.SPACING)),
    /** 색상·타이포그래피 — 글자·배경·테두리 색, 굵기, 크기 */
    COLOR_TYPOGRAPHY(EnumSet.of(StyleProperty.COLOR, StyleProperty.FONT_WEIGHT, StyleProperty.FONT_SIZE)),
    /** 모서리 */
    BORDER_RADIUS(EnumSet.of(StyleProperty.RADIUS)),
    /** 고정 문구 — 코드에 문자열로 적힌 문구만(데이터로 그리는 문구는 비교하지 않는다) */
    TEXT(EnumSet.of(StyleProperty.TEXT));

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

    /** 저장된 값 → 범위. 이제 없는 값(2026-11-10 이전의 layout·presence)은 빈 값 */
    public static Optional<QaScope> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (QaScope s : values()) {
            if (s.value().equals(raw.trim())) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** 고른 범위 전체의 property — 정해진 순서(enum 순서) */
    public static List<StyleProperty> propertiesOf(Set<QaScope> scopes) {
        EnumSet<StyleProperty> all = EnumSet.noneOf(StyleProperty.class);
        scopes.forEach(s -> all.addAll(s.properties));
        return List.copyOf(all);
    }
}
