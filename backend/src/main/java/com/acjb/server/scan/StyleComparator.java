package com.acjb.server.scan;

import java.util.ArrayList;
import java.util.List;

import com.acjb.server.domain.StyleProperty;

/**
 * 스파이크에서 실행 전에 확정한 건별 판정 규칙(docs/spikes/2026-09-28_style-mismatch-auto-extraction.md).
 * color 채널별 ±2, font_weight 정수 완전 일치, radius ±1px. 양쪽 값이 다 있는 property만 비교한다(N3).
 */
public final class StyleComparator {

    static final int COLOR_TOLERANCE = 2;
    static final double RADIUS_TOLERANCE_PX = 1.0;

    private StyleComparator() {
    }

    /** 한 property의 비교 결과. 값은 저장 형식(#RRGGBB, 600, 8px) */
    public record Result(StyleProperty property, String expected, String actual, boolean matches) {
    }

    public static List<Result> compare(StyleValues figma, StyleValues impl) {
        List<Result> results = new ArrayList<>();
        if (figma.color() != null && impl.color() != null) {
            StyleValues.Rgb e = figma.color();
            StyleValues.Rgb a = impl.color();
            boolean same = Math.abs(e.r() - a.r()) <= COLOR_TOLERANCE
                && Math.abs(e.g() - a.g()) <= COLOR_TOLERANCE
                && Math.abs(e.b() - a.b()) <= COLOR_TOLERANCE;
            results.add(new Result(StyleProperty.COLOR, e.hex(), a.hex(), same));
        }
        if (figma.fontWeight() != null && impl.fontWeight() != null) {
            results.add(new Result(StyleProperty.FONT_WEIGHT, String.valueOf(figma.fontWeight()),
                String.valueOf(impl.fontWeight()), figma.fontWeight().equals(impl.fontWeight())));
        }
        if (figma.radiusPx() != null && impl.radiusPx() != null) {
            results.add(new Result(StyleProperty.RADIUS, px(figma.radiusPx()), px(impl.radiusPx()),
                Math.abs(figma.radiusPx() - impl.radiusPx()) <= RADIUS_TOLERANCE_PX));
        }
        return results;
    }

    /** 8.0 → "8px", 7.5 → "7.5px" (소수 둘째 자리까지) */
    static String px(double v) {
        double rounded = Math.round(v * 100) / 100.0;
        return (rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded)) + "px";
    }
}
