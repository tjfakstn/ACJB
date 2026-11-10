package com.acjb.server.qa;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

import com.acjb.server.qa.FigmaFacts.Element;
import com.acjb.server.qa.FigmaFacts.Fact;

/**
 * Figma 값 ↔ 코드 값 비교 규칙과 이슈에 남길 값 표기(2026-11-10 스파이크 v2 규칙, SPEC 4절).
 *  - 색은 채널마다 ±3(oklch→sRGB 반올림), 길이는 0.5px
 *  - 반지름은 요소 크기의 절반으로 잘라 실제로 그려지는 값으로 비교한다(높이 2px 막대의 4px = 1px)
 *  - 코드 줄에 고정 높이(h-*)가 있고 세로 padding 클래스가 없으면 세로 padding은 비교하지 않는다(높이로 세로를 맞춘 요소)
 *  - 왼쪽 padding은 Figma padding 또는 "padding + 앞쪽 아이콘 너비 + gap"과 같으면 같다(아이콘을 absolute로 둔 입력창)
 */
final class StyleRules {

    private static final Pattern FIXED_HEIGHT = Pattern.compile("(?<![\\w-])h-(\\d|\\[)");
    private static final Pattern VERTICAL_PADDING = Pattern.compile("(?<![\\w-])(p|py|pt|pb)-");

    private StyleRules() {
    }

    /** 같으면 true, 다르면 false, 비교할 수 없으면 빈 값 */
    static Optional<Boolean> same(Fact fact, Element e, Object code, String line) {
        Object figma = e.values().get(fact);
        if (figma == null || code == null) {
            return Optional.empty();
        }
        return Optional.of(switch (fact) {
            case COLOR, BACKGROUND, BORDER -> closeColor((String) figma, (String) code);
            case RADIUS -> Math.abs(effectiveRadius(figma, e) - effectiveRadius(code, e)) < 0.5;
            case PADDING -> samePadding((double[]) figma, (double[]) code, e, fixedHeight(line));
            case GAP, FONT_SIZE, FONT_WEIGHT -> Math.abs((Double) figma - (Double) code) < 0.5;
            case TEXT -> normalize((String) figma).equals(normalize((String) code));
        });
    }

    static boolean fixedHeight(String line) {
        return line != null && FIXED_HEIGHT.matcher(line).find() && !VERTICAL_PADDING.matcher(line).find();
    }

    private static boolean samePadding(double[] f, double[] c, Element e, boolean fixedHeight) {
        boolean right = Math.abs(f[1] - c[1]) < 0.5;
        boolean left = Math.abs(f[3] - c[3]) < 0.5 || (e.lead() != null && Math.abs(f[3] + e.lead() - c[3]) < 0.5);
        boolean vertical = fixedHeight || (Math.abs(f[0] - c[0]) < 0.5 && Math.abs(f[2] - c[2]) < 0.5);
        return right && left && vertical;
    }

    private static double effectiveRadius(Object v, Element e) {
        double limit = Double.MAX_VALUE;
        if (e.width() != null && e.width() > 0) {
            limit = Math.min(limit, e.width() / 2);
        }
        if (e.height() != null && e.height() > 0) {
            limit = Math.min(limit, e.height() / 2);
        }
        double r = "full".equals(v) ? Double.MAX_VALUE : (Double) v;
        if (r >= 100 && limit == Double.MAX_VALUE) {
            return 9999; // 크기를 모르면 "완전히 둥근 것"끼리만 같다
        }
        return Math.min(r, limit);
    }

    private static boolean closeColor(String a, String b) {
        for (int i = 1; i < 7; i += 2) {
            if (Math.abs(Integer.parseInt(a.substring(i, i + 2), 16) - Integer.parseInt(b.substring(i, i + 2), 16)) > 3) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String s) {
        return String.join(" ", s.trim().split("\\s+"));
    }

    /** 이슈·Re-QA에 남길 값. Re-QA는 이 문자열이 같은지로 판정하므로 Figma 값과 코드 값을 같은 규칙으로 적는다 */
    static String format(Fact fact, Object v, boolean fixedHeight) {
        return switch (fact) {
            case COLOR -> "글자 " + v;
            case BACKGROUND -> "배경 " + v;
            case BORDER -> "테두리 " + v;
            case FONT_WEIGHT -> String.valueOf(Math.round((Double) v));
            case FONT_SIZE -> px((Double) v);
            case RADIUS -> "full".equals(v) || (Double) v >= 100 ? "full" : px((Double) v);
            case GAP -> "gap " + px((Double) v);
            case PADDING -> padding((double[]) v, fixedHeight);
            case TEXT -> (String) v;
        };
    }

    private static String padding(double[] p, boolean horizontalOnly) {
        if (horizontalOnly) {
            return "padding 좌우 " + (p[3] == p[1] ? px(p[1]) : px(p[3]) + " " + px(p[1]));
        }
        if (p[0] == p[1] && p[1] == p[2] && p[2] == p[3]) {
            return "padding " + px(p[0]);
        }
        if (p[0] == p[2] && p[1] == p[3]) {
            return "padding " + px(p[0]) + " " + px(p[1]);
        }
        return "padding " + String.join(" ", Arrays.stream(p).mapToObj(StyleRules::px).toList());
    }

    static String px(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)) + "px";
    }

    /** Re-QA — 이슈의 expected 값 표기에서 어떤 종류(글자 색·배경·테두리, padding·gap)였는지 되찾는다 */
    static Optional<Fact> factOf(com.acjb.server.domain.StyleProperty property, String expected) {
        String e = expected == null ? "" : expected;
        return Optional.ofNullable(switch (property) {
            case COLOR -> e.startsWith("배경 ") ? Fact.BACKGROUND : e.startsWith("테두리 ") ? Fact.BORDER : e.startsWith("글자 ") ? Fact.COLOR : null;
            case SPACING -> e.startsWith("gap ") ? Fact.GAP : e.startsWith("padding ") ? Fact.PADDING : null;
            case FONT_WEIGHT -> Fact.FONT_WEIGHT;
            case FONT_SIZE -> Fact.FONT_SIZE;
            case RADIUS -> Fact.RADIUS;
            case TEXT -> Fact.TEXT;
            default -> null;
        });
    }
}
