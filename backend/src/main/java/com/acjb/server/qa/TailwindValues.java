package com.acjb.server.qa;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.acjb.server.qa.FigmaFacts.Fact;

/**
 * Tailwind 4 클래스 → 값(2026-11-10 스파이크의 tw.py). 값은 저장소 CSS의 `@theme` 변수와 Tailwind 기본 테마에서만 읽는다 — LLM이 값을 계산하지 않는다(N17).
 * 못 읽는 것(투명도 있는 색, hover:·md: 같은 변형뿐인 값, 테마에 없는 이름)은 null — 비교하지 않는다
 */
final class TailwindValues {

    private static final Pattern VAR = Pattern.compile("--([a-z0-9-]+):\\s*([^;]+);");
    private static final Pattern OKLCH = Pattern.compile("oklch\\(([\\d.]+)%\\s+([\\d.]+)\\s+([\\d.]+)\\)");
    private static final String DEFAULT_THEME = loadDefault();

    private static final Pattern STYLESHEET = Pattern.compile("(?i).*\\.(css|scss|sass|less)$");

    private final Map<String, String> vars = new HashMap<>();
    /** 저장소 CSS에 적힌 사용자 정의 속성 — CSS 선언의 var()를 풀 때만 쓴다(Tailwind 기본 테마는 넣지 않는다) */
    private final Map<String, String> projectVars = new HashMap<>();

    /** 기본 테마 위에 저장소 CSS(여러 개면 순서대로 덮어씀)를 얹는다 */
    TailwindValues(List<String> projectCss) {
        this(projectCss, projectCss);
    }

    /** themeCss: Tailwind `@theme`이 있는 CSS, allCss: var()를 풀 저장소 CSS 전체(2026-11-12) */
    TailwindValues(List<String> themeCss, List<String> allCss) {
        read(DEFAULT_THEME, vars);
        themeCss.forEach(css -> read(css, vars));
        allCss.forEach(css -> read(css, projectVars));
    }

    private static void read(String css, Map<String, String> into) {
        Matcher m = VAR.matcher(css);
        while (m.find()) {
            into.put(m.group(1), m.group(2).strip());
        }
    }

    /**
     * LLM이 고른 토큰의 값(2026-11-12) — CSS 선언(`border-radius: 8px`, `borderRadius: 8`)이면 CssValues로, 아니면 Tailwind 클래스로 읽는다.
     * 문구는 토큰 그대로. file·line은 단위 없는 숫자를 px로 읽을지(JS 객체 값) 가르는 데만 쓴다
     */
    Object value(Fact fact, String token, String file, String line) {
        if (fact == Fact.TEXT) {
            return token;
        }
        Optional<CssValues.Declaration> d = CssValues.parse(token);
        if (d.isPresent()) {
            return CssValues.resolve(fact, d.get(), projectVars, jsValue(file, line, token));
        }
        return resolve(fact, token);
    }

    /** 스타일시트 파일이 아니고, 줄에서 선언 뒤가 `;`로 끝나지 않으면(styled-components 템플릿이 아니면) JS 객체 값이다 */
    static boolean jsValue(String file, String line, String token) {
        if (file != null && STYLESHEET.matcher(file).matches()) {
            return false;
        }
        int at = line == null ? -1 : line.indexOf(token);
        return at < 0 || !line.substring(at + token.length()).stripLeading().startsWith(";");
    }

    /** token(클래스 하나 또는 공백으로 나뉜 여러 개) → 값. 형은 FigmaFacts.Element.values와 같다 */
    Object resolve(Fact fact, String token) {
        List<String> classes = new ArrayList<>();
        for (String c : token.trim().split("\\s+")) {
            String head = c.contains("[") ? c.substring(0, c.indexOf('[')) : c;
            if (!c.isEmpty() && !head.contains(":")) { // hover:·md: 같은 변형은 기본 값이 아니다
                classes.add(c);
            }
        }
        return switch (fact) {
            case COLOR -> color(classes, "text-");
            case BACKGROUND -> color(classes, "bg-");
            case BORDER -> color(classes, "border-");
            case FONT_WEIGHT -> fontWeight(classes);
            case FONT_SIZE -> fontSize(classes);
            case RADIUS -> radius(classes);
            case GAP -> gap(classes);
            case PADDING -> padding(classes);
            case TEXT -> token;
        };
    }

    private String color(List<String> classes, String prefix) {
        for (String c : classes) {
            if (!c.startsWith(prefix)) {
                continue;
            }
            String name = c.substring(prefix.length());
            if (name.contains("/")) {
                return null; // 투명도 있는 색은 비교하지 않는다(불투명 단색만)
            }
            Matcher arb = Pattern.compile("\\[(#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6})]").matcher(name);
            if (arb.matches()) {
                return hex(arb.group(1));
            }
            String raw = vars.get("color-" + name);
            if (raw != null) {
                String v = raw.startsWith("oklch") ? oklchToHex(raw) : hex(raw);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private Double fontWeight(List<String> classes) {
        for (String c : classes) {
            Matcher m = Pattern.compile("font-([a-z]+)").matcher(c);
            if (m.matches() && vars.containsKey("font-weight-" + m.group(1))) {
                return Double.valueOf(vars.get("font-weight-" + m.group(1)));
            }
        }
        return null;
    }

    private Double fontSize(List<String> classes) {
        for (String c : classes) {
            Matcher m = Pattern.compile("text-(\\[([\\d.]+)px]|xs|sm|base|lg|xl|[2-9]xl)").matcher(c);
            if (m.matches()) {
                return m.group(2) != null ? Double.valueOf(m.group(2)) : px(vars.get("text-" + m.group(1)));
            }
        }
        return null;
    }

    private Object radius(List<String> classes) {
        for (String c : classes) {
            if (c.equals("rounded-full")) {
                return "full";
            }
            if (c.equals("rounded")) {
                return vars.containsKey("radius") ? px(vars.get("radius")) : Double.valueOf(4);
            }
            Matcher m = Pattern.compile("rounded-(\\[([\\d.]+)px]|xs|sm|md|lg|xl|2xl|3xl|4xl|none)").matcher(c);
            if (m.matches()) {
                if (m.group(2) != null) {
                    return Double.valueOf(m.group(2));
                }
                return m.group(1).equals("none") ? Double.valueOf(0) : px(vars.get("radius-" + m.group(1)));
            }
        }
        return null;
    }

    private Double gap(List<String> classes) {
        for (String c : classes) {
            Matcher m = Pattern.compile("(?:gap|space-[xy])-(.+)").matcher(c);
            if (m.matches()) {
                return spacing(m.group(1));
            }
        }
        return null;
    }

    private double[] padding(List<String> classes) {
        Double t = null;
        Double r = null;
        Double b = null;
        Double l = null;
        for (String c : classes) {
            Matcher m = Pattern.compile("(p|px|py|pt|pr|pb|pl)-(.+)").matcher(c);
            if (!m.matches()) {
                continue;
            }
            Double n = spacing(m.group(2));
            if (n == null) {
                continue;
            }
            switch (m.group(1)) {
                case "p" -> { t = n; r = n; b = n; l = n; }
                case "px" -> { r = n; l = n; }
                case "py" -> { t = n; b = n; }
                case "pt" -> t = n;
                case "pr" -> r = n;
                case "pb" -> b = n;
                default -> l = n;
            }
        }
        if (t == null && r == null && b == null && l == null) {
            return null;
        }
        return new double[] {t == null ? 0 : t, r == null ? 0 : r, b == null ? 0 : b, l == null ? 0 : l};
    }

    private Double spacing(String v) {
        Matcher arb = Pattern.compile("\\[([\\d.]+)(px|rem)]").matcher(v);
        if (arb.matches()) {
            return px(arb.group(1) + arb.group(2));
        }
        if (v.matches("[\\d.]+")) {
            Double unit = px(vars.get("spacing"));
            return unit == null ? null : Double.parseDouble(v) * unit;
        }
        return v.equals("px") ? Double.valueOf(1) : null;
    }

    private static Double px(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = Pattern.compile("([\\d.]+)(px|rem)?").matcher(s.strip());
        if (!m.matches()) {
            return null;
        }
        double n = Double.parseDouble(m.group(1));
        return "rem".equals(m.group(2)) ? n * 16 : n;
    }

    static String hex(String s) {
        String v = s.strip().toLowerCase(Locale.ROOT);
        if (v.matches("#[0-9a-f]{3}")) {
            v = "#" + v.charAt(1) + v.charAt(1) + v.charAt(2) + v.charAt(2) + v.charAt(3) + v.charAt(3);
        }
        return v.matches("#[0-9a-f]{6}") ? v : null;
    }

    /** Tailwind 4 기본 팔레트는 oklch — sRGB로 바꿔 Figma 값과 비교한다 */
    static String oklchToHex(String s) {
        Matcher m = OKLCH.matcher(s);
        if (!m.find()) {
            return null;
        }
        double lightness = Double.parseDouble(m.group(1)) / 100;
        double chroma = Double.parseDouble(m.group(2));
        double hue = Math.toRadians(Double.parseDouble(m.group(3)));
        double a = chroma * Math.cos(hue);
        double b = chroma * Math.sin(hue);
        double l = Math.pow(lightness + 0.3963377774 * a + 0.2158037573 * b, 3);
        double mm = Math.pow(lightness - 0.1055613458 * a - 0.0638541728 * b, 3);
        double ss = Math.pow(lightness - 0.0894841775 * a - 1.2914855480 * b, 3);
        double[] rgb = {
            4.0767416621 * l - 3.3077115913 * mm + 0.2309699292 * ss,
            -1.2684380046 * l + 2.6097574011 * mm - 0.3413193965 * ss,
            -0.0041960863 * l - 0.7034186147 * mm + 1.7076147010 * ss};
        StringBuilder sb = new StringBuilder("#");
        for (double x : rgb) {
            double c = Math.max(0, Math.min(1, x));
            c = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
            sb.append(String.format("%02x", Math.round(c * 255)));
        }
        return sb.toString();
    }

    private static String loadDefault() {
        try (InputStream in = TailwindValues.class.getResourceAsStream("/qa/tailwind-v4-theme.css")) {
            if (in == null) {
                throw new IllegalStateException("qa/tailwind-v4-theme.css가 없습니다.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
