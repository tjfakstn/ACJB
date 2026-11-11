package com.acjb.server.qa;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.acjb.server.qa.FigmaFacts.Fact;

/**
 * CSS 선언 한 줄 → 값(2026-11-11). Tailwind 클래스가 아닌 곳에 적힌 스타일을 읽는다 —
 * 일반 CSS·SCSS·CSS modules 파일의 선언(`border-radius: 8px;`), styled-components·emotion 템플릿 줄,
 * 인라인 style·CSS-in-JS 객체(`borderRadius: 8`, `padding: '12px 16px'`).
 * LLM은 선언을 그대로 베껴 올 뿐이고, 값은 여기서 그 글자만 보고 읽는다(N17). 못 읽는 것은 null — 비교하지 않는다:
 * 템플릿 보간(`${...}`), SCSS·LESS 변수, 저장소 CSS에 없는 var(), em·%·calc(), 투명도 있는 색, 한 변만 적은 padding
 */
final class CssValues {

    private static final Pattern DECLARATION = Pattern.compile("^\\s*([A-Za-z][A-Za-z-]*)\\s*:\\s*(.+?)\\s*[;,]?\\s*$");
    private static final Pattern CAMEL = Pattern.compile("([a-z])([A-Z])");
    private static final Pattern LENGTH = Pattern.compile("(-?[\\d.]+)(px|rem)?");
    private static final Pattern VAR = Pattern.compile("var\\(\\s*--([A-Za-z0-9_-]+)\\s*(?:,[^)]*)?\\)");
    private static final Pattern RGB = Pattern.compile("rgba?\\(\\s*([\\d.]+)[\\s,]+([\\d.]+)[\\s,]+([\\d.]+)\\s*(?:[,/]\\s*([\\d.]+%?)\\s*)?\\)");
    private static final Pattern COLOR_IN_SHORTHAND = Pattern.compile("#[0-9a-fA-F]{3,8}\\b|rgba?\\([^)]*\\)|var\\([^)]*\\)|\\b(?:white|black)\\b");
    private static final Map<String, String> NAMED = Map.of("white", "#ffffff", "black", "#000000");

    /** 선언의 CSS 속성(kebab-case) → 비교할 값의 종류 */
    private static final Map<Fact, Set<String>> PROPERTIES = Map.of(
        Fact.COLOR, Set.of("color"),
        Fact.BACKGROUND, Set.of("background", "background-color"),
        Fact.BORDER, Set.of("border", "border-color", "border-top", "border-right", "border-bottom", "border-left",
            "border-top-color", "border-right-color", "border-bottom-color", "border-left-color"),
        Fact.FONT_WEIGHT, Set.of("font-weight"),
        Fact.FONT_SIZE, Set.of("font-size"),
        Fact.RADIUS, Set.of("border-radius"),
        Fact.GAP, Set.of("gap", "row-gap", "column-gap"),
        // 한 변만 적은 padding-left 등은 나머지 변을 알 수 없어 읽지 않는다 — shorthand만
        Fact.PADDING, Set.of("padding"));

    private CssValues() {
    }

    /** CSS 선언 한 개. property는 kebab-case 소문자, value는 따옴표를 뗀 값 */
    record Declaration(String property, String value) {
    }

    /** 비교하는 CSS 속성의 선언처럼 생겼으면 그 선언 — Tailwind 토큰(`hover:bg-x`, `px-4`)은 여기에 걸리지 않는다 */
    static Optional<Declaration> parse(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Matcher m = DECLARATION.matcher(token);
        if (!m.matches()) {
            return Optional.empty();
        }
        String raw = m.group(1);
        String property = CAMEL.matcher(raw).replaceAll("$1-$2").toLowerCase(Locale.ROOT);
        if (PROPERTIES.values().stream().noneMatch(s -> s.contains(property))) {
            return Optional.empty();
        }
        String value = m.group(2).replaceAll("(?i)\\s*!important$", "").strip();
        if (value.length() >= 2 && "'\"`".indexOf(value.charAt(0)) >= 0 && value.charAt(value.length() - 1) == value.charAt(0)) {
            value = value.substring(1, value.length() - 1).strip();
        } else if (!value.isEmpty() && "'\"`".indexOf(value.charAt(0)) >= 0) {
            return Optional.empty(); // 따옴표가 닫히지 않은 값 — 문자열을 잇거나 계산하는 식이다
        }
        // 템플릿 보간·SCSS/LESS 변수·계산식은 읽지 않는다. JS 변수·함수 호출은 아래 값 읽기에서 걸러진다
        if (value.isEmpty() || value.contains("${") || value.startsWith("$") || value.startsWith("@") || value.contains("calc(")) {
            return Optional.empty();
        }
        return Optional.of(new Declaration(property, value));
    }

    /**
     * 선언의 값. 형은 FigmaFacts.Element.values와 같다. 선언의 속성이 fact와 맞지 않으면 null(다른 속성을 근거로 쓰지 않는다).
     * vars는 저장소 CSS에 적힌 사용자 정의 속성(--name: value) — var()는 여기서만 푼다.
     * js는 JS 객체(인라인 style·CSS-in-JS 객체)의 값인지 — React처럼 단위 없는 숫자를 px로 읽는다. CSS에서는 0만 읽는다
     */
    static Object resolve(Fact fact, Declaration d, Map<String, String> vars, boolean js) {
        if (!PROPERTIES.getOrDefault(fact, Set.of()).contains(d.property())) {
            return null;
        }
        String value = substitute(d.value(), vars, 0);
        if (value == null) {
            return null;
        }
        return switch (fact) {
            case COLOR -> color(value);
            case BACKGROUND -> color(value); // background shorthand는 값 전체가 색 하나일 때만
            case BORDER -> d.property().endsWith("-color") ? color(value) : borderColor(value);
            case FONT_WEIGHT -> fontWeight(value);
            case FONT_SIZE -> length(value, js);
            case RADIUS -> radius(value, js);
            case GAP -> gap(value, js);
            case PADDING -> padding(value, js);
            case TEXT -> null;
        };
    }

    /** var(--x)를 저장소 CSS의 값으로 바꾼다. 없는 변수가 하나라도 있으면 null(런타임 값을 지어내지 않는다) */
    private static String substitute(String value, Map<String, String> vars, int depth) {
        if (!value.contains("var(")) {
            return value;
        }
        if (depth > 3) {
            return null;
        }
        Matcher m = VAR.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            if (v == null) {
                return null;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v.strip()));
        }
        m.appendTail(sb);
        return substitute(sb.toString(), vars, depth + 1);
    }

    static String color(String value) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (NAMED.containsKey(v)) {
            return NAMED.get(v);
        }
        if (v.startsWith("oklch")) {
            return v.contains("/") ? null : TailwindValues.oklchToHex(v);
        }
        if (v.matches("#[0-9a-f]{8}")) {
            return v.endsWith("ff") ? v.substring(0, 7) : null;
        }
        if (v.matches("#[0-9a-f]{4}")) {
            return v.endsWith("f") ? TailwindValues.hex(v.substring(0, 4)) : null;
        }
        if (v.startsWith("#")) {
            return TailwindValues.hex(v);
        }
        Matcher m = RGB.matcher(v);
        if (m.matches()) {
            String alpha = m.group(4);
            if (alpha != null && !(alpha.equals("1") || alpha.equals("1.0") || alpha.equals("100%"))) {
                return null; // 투명도 있는 색은 비교하지 않는다(Tailwind 쪽과 같은 규칙)
            }
            StringBuilder sb = new StringBuilder("#");
            for (int i = 1; i <= 3; i++) {
                double c = Double.parseDouble(m.group(i));
                if (c > 255) {
                    return null;
                }
                sb.append(String.format("%02x", Math.round(c)));
            }
            return sb.toString();
        }
        return null;
    }

    /** `1px solid #ddd` — 색이 정확히 하나 적혀 있을 때만 */
    private static String borderColor(String value) {
        Matcher m = COLOR_IN_SHORTHAND.matcher(value);
        String found = null;
        while (m.find()) {
            if (found != null) {
                return null;
            }
            found = m.group();
        }
        return found == null ? null : color(found);
    }

    private static Double fontWeight(String value) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (v.equals("normal")) {
            return 400.0;
        }
        if (v.equals("bold")) {
            return 700.0;
        }
        return v.matches("\\d{3}") ? Double.valueOf(v) : null;
    }

    /** px·rem(16px 기준)만. JS 객체의 단위 없는 숫자는 React처럼 px로, CSS의 단위 없는 숫자는 0만 읽는다 */
    static Double length(String value, boolean js) {
        Matcher m = LENGTH.matcher(value.strip());
        if (!m.matches()) {
            return null;
        }
        double n = Double.parseDouble(m.group(1));
        if (m.group(2) == null && !js && n != 0) {
            return null;
        }
        return "rem".equals(m.group(2)) ? n * 16 : n;
    }

    private static List<Double> lengths(String value, boolean js) {
        String[] parts = value.strip().split("\\s+");
        List<Double> out = new java.util.ArrayList<>();
        for (String p : parts) {
            Double d = length(p, js);
            if (d == null) {
                return null;
            }
            out.add(d);
        }
        return out;
    }

    /** 네 모서리가 같을 때만(타원·모서리별 값은 읽지 않는다). 9999px 같은 큰 값은 Tailwind의 rounded-full과 같게 본다 */
    private static Object radius(String value, boolean js) {
        if (value.contains("/")) {
            return null;
        }
        List<Double> l = lengths(value, js);
        if (l == null || l.isEmpty() || l.stream().distinct().count() != 1) {
            return null;
        }
        return l.get(0);
    }

    /** gap은 행·열이 같을 때만(Figma 간격은 하나) */
    private static Double gap(String value, boolean js) {
        List<Double> l = lengths(value, js);
        if (l == null || l.isEmpty() || l.size() > 2 || l.stream().distinct().count() != 1) {
            return null;
        }
        return l.get(0);
    }

    /** padding shorthand 1~4개 값 → 위·오른쪽·아래·왼쪽 */
    private static double[] padding(String value, boolean js) {
        List<Double> l = lengths(value, js);
        if (l == null) {
            return null;
        }
        return switch (l.size()) {
            case 1 -> new double[] {l.get(0), l.get(0), l.get(0), l.get(0)};
            case 2 -> new double[] {l.get(0), l.get(1), l.get(0), l.get(1)};
            case 3 -> new double[] {l.get(0), l.get(1), l.get(2), l.get(1)};
            case 4 -> new double[] {l.get(0), l.get(1), l.get(2), l.get(3)};
            default -> null;
        };
    }
}
