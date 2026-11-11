package com.acjb.server.qa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.acjb.server.qa.FigmaFacts.Fact;

/**
 * Flutter(Dart) 코드의 스타일 값 읽기(2026-11-11, 앱 지원). LLM이 고른 토큰은 위젯 생성자의 이름 있는 인자 하나다
 * (`color: Color(0xFF1A1A1A)`, `fontWeight: FontWeight.w600`, `borderRadius: BorderRadius.circular(8)`,
 * `padding: EdgeInsets.symmetric(horizontal: 16, vertical: 12)`, `spacing: 8`).
 * 코드에 적힌 리터럴과, 저장소에 `static const`·최상위 `const`로 적힌 상수(`AppColors.primary`)만 읽는다.
 * Theme.of(context)·변수·계산식·상태별 값·투명도 있는 색은 읽지 않는다(null) — 근거가 없는 값을 지어내지 않는다(N17).
 * 값의 형은 FigmaFacts.Element.values와 같다(색 "#rrggbb", 길이·굵기 Double, padding 위·오른쪽·아래·왼쪽 double[4]).
 * Flutter의 논리 픽셀은 Figma 픽셀과 같은 단위다
 */
final class DartValues {

    private static final Pattern ARGUMENT = Pattern.compile("^\\s*([A-Za-z_]\\w*)\\s*:\\s*(.+?)\\s*,?\\s*$");
    private static final Pattern NUMBER = Pattern.compile("-?(?:\\d+\\.?\\d*|\\.\\d+)");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)?");
    private static final Pattern COLOR_HEX = Pattern.compile("Color\\(\\s*0x([0-9a-fA-F]{8})\\s*\\)");
    private static final Pattern COLOR_ARGB = Pattern.compile("Color\\.fromARGB\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
    private static final Pattern COLOR_RGBO = Pattern.compile("Color\\.fromRGBO\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*([\\d.]+)\\s*\\)");
    private static final Pattern RADIUS_CIRCULAR = Pattern.compile("BorderRadius\\.circular\\(\\s*(.+?)\\s*\\)");
    private static final Pattern RADIUS_ALL = Pattern.compile("BorderRadius\\.all\\(\\s*Radius\\.circular\\(\\s*(.+?)\\s*\\)\\s*\\)");
    private static final Pattern EDGE = Pattern.compile("EdgeInsets(?:Directional)?\\.(all|symmetric|fromLTRB|only|fromSTEB)\\((.*)\\)");
    private static final Pattern FONT_WEIGHT = Pattern.compile("FontWeight\\.(w[1-9]00|bold|normal)");
    /** 상수 선언 — `static const Color primary = Color(0xFF...);`, `const kGap = 8.0;`, `static final` 포함(값은 한 줄에 끝나는 것만) */
    private static final Pattern CONST = Pattern.compile("^(\\s*)(?:static\\s+)?(?:const|final)\\s+(?:[A-Za-z_][\\w<>?]*\\s+)?([A-Za-z_$][\\w$]*)\\s*=\\s*([^;]+);");
    private static final Pattern CLASS = Pattern.compile("^\\s*(?:abstract\\s+|final\\s+|sealed\\s+)*class\\s+([A-Za-z_$][\\w$]*)");

    /** fact마다 받는 인자 이름 — 다른 속성의 인자를 근거로 쓰지 않는다 */
    private static final Map<Fact, Set<String>> ARGUMENTS = Map.of(
        Fact.COLOR, Set.of("color"),
        Fact.BACKGROUND, Set.of("color", "backgroundColor"),
        Fact.BORDER, Set.of("color", "border", "side"),
        Fact.FONT_WEIGHT, Set.of("fontWeight"),
        Fact.FONT_SIZE, Set.of("fontSize"),
        Fact.RADIUS, Set.of("borderRadius"),
        Fact.PADDING, Set.of("padding"),
        Fact.GAP, Set.of("spacing"));

    private DartValues() {
    }

    /** 토큰의 값. 읽을 수 없으면 null */
    static Object value(Fact fact, String token, Map<String, String> consts) {
        if (fact == Fact.TEXT) {
            return token;
        }
        if (token == null) {
            return null;
        }
        Matcher m = ARGUMENT.matcher(token);
        if (!m.matches() || !ARGUMENTS.getOrDefault(fact, Set.of()).contains(m.group(1))) {
            return null;
        }
        String expr = resolve(m.group(2), consts, 0);
        if (expr == null) {
            return null;
        }
        return switch (fact) {
            case COLOR, BACKGROUND -> color(expr, consts);
            case BORDER -> m.group(1).equals("color") ? color(expr, consts) : borderColor(expr, consts);
            case FONT_WEIGHT -> fontWeight(expr);
            case FONT_SIZE, GAP -> number(expr, consts);
            case RADIUS -> radius(expr, consts);
            case PADDING -> padding(expr, consts);
            case TEXT -> null;
        };
    }

    /** `const ` 접두를 떼고, 이름 하나(`AppColors.primary`, `kGap`)면 저장소 상수로 바꾼다. 모르는 이름이면 null */
    private static String resolve(String raw, Map<String, String> consts, int depth) {
        String v = raw.strip();
        if (v.startsWith("const ")) {
            v = v.substring(6).strip();
        }
        if (NUMBER.matcher(v).matches() || !IDENTIFIER.matcher(v).matches() || v.startsWith("Colors.") || v.startsWith("FontWeight.")) {
            return v;
        }
        if (depth > 3 || !consts.containsKey(v)) {
            return null;
        }
        return resolve(consts.get(v), consts, depth + 1);
    }

    static String color(String expr, Map<String, String> consts) {
        String v = resolve(expr, consts, 0);
        if (v == null) {
            return null;
        }
        switch (v) {
            case "Colors.white":
                return "#ffffff";
            case "Colors.black":
                return "#000000";
            default:
                break;
        }
        Matcher m = COLOR_HEX.matcher(v);
        if (m.matches()) {
            String argb = m.group(1).toLowerCase(Locale.ROOT);
            return argb.startsWith("ff") ? "#" + argb.substring(2) : null; // 투명도 있는 색은 비교하지 않는다
        }
        m = COLOR_ARGB.matcher(v);
        if (m.matches()) {
            return Integer.parseInt(m.group(1)) == 255 ? rgb(m.group(2), m.group(3), m.group(4)) : null;
        }
        m = COLOR_RGBO.matcher(v);
        if (m.matches()) {
            return Double.parseDouble(m.group(4)) == 1.0 ? rgb(m.group(1), m.group(2), m.group(3)) : null;
        }
        return null; // Colors.blue 같은 팔레트, withOpacity·withValues가 붙은 색, Theme 값
    }

    private static String rgb(String r, String g, String b) {
        int[] c = {Integer.parseInt(r), Integer.parseInt(g), Integer.parseInt(b)};
        for (int x : c) {
            if (x > 255) {
                return null;
            }
        }
        return String.format("#%02x%02x%02x", c[0], c[1], c[2]);
    }

    /** `Border.all(color: X)`, `BorderSide(color: X)` — 안에 색이 정확히 하나 적혀 있을 때만 */
    private static String borderColor(String expr, Map<String, String> consts) {
        List<String> found = new ArrayList<>();
        Map<String, String> args = arguments(inner(expr));
        if (args == null) {
            return null;
        }
        for (Map.Entry<String, String> arg : args.entrySet()) {
            if (arg.getKey().equals("color")) {
                found.add(arg.getValue());
            }
        }
        return found.size() == 1 ? color(found.get(0), consts) : null;
    }

    private static Double fontWeight(String expr) {
        Matcher m = FONT_WEIGHT.matcher(expr);
        if (!m.matches()) {
            return null;
        }
        return switch (m.group(1)) {
            case "bold" -> 700.0;
            case "normal" -> 400.0;
            default -> Double.valueOf(m.group(1).substring(1));
        };
    }

    static Double number(String expr, Map<String, String> consts) {
        String v = resolve(expr, consts, 0);
        return v != null && NUMBER.matcher(v).matches() ? Double.valueOf(v) : null;
    }

    private static Double radius(String expr, Map<String, String> consts) {
        Matcher m = RADIUS_ALL.matcher(expr);
        if (!m.matches()) {
            m = RADIUS_CIRCULAR.matcher(expr);
            if (!m.matches()) {
                return null; // BorderRadius.only·vertical처럼 모서리마다 다른 값은 읽지 않는다
            }
        }
        return number(m.group(1), consts);
    }

    /** EdgeInsets — 적지 않은 변은 Flutter와 같이 0이다 */
    private static double[] padding(String expr, Map<String, String> consts) {
        Matcher m = EDGE.matcher(expr);
        if (!m.matches()) {
            return null;
        }
        String kind = m.group(1);
        String body = m.group(2);
        if (kind.equals("all")) {
            Double v = number(body, consts);
            return v == null ? null : new double[] {v, v, v, v};
        }
        if (kind.equals("fromLTRB") || kind.equals("fromSTEB")) {
            String[] parts = body.split(",");
            if (parts.length != 4) {
                return null;
            }
            Double[] n = new Double[4];
            for (int i = 0; i < 4; i++) {
                n[i] = number(parts[i], consts);
                if (n[i] == null) {
                    return null;
                }
            }
            return new double[] {n[1], n[2], n[3], n[0]};
        }
        Map<String, String> args = arguments(body);
        if (args == null) {
            return null;
        }
        Map<String, Double> values = new HashMap<>();
        for (Map.Entry<String, String> a : args.entrySet()) {
            Double v = number(a.getValue(), consts);
            if (v == null) {
                return null;
            }
            values.put(a.getKey(), v);
        }
        if (kind.equals("symmetric")) {
            if (!Set.of("horizontal", "vertical").containsAll(values.keySet())) {
                return null;
            }
            double h = values.getOrDefault("horizontal", 0.0);
            double vv = values.getOrDefault("vertical", 0.0);
            return new double[] {vv, h, vv, h};
        }
        // only — EdgeInsets는 left/right, EdgeInsetsDirectional은 start/end(왼쪽→오른쪽 화면 기준)
        if (!Set.of("top", "right", "bottom", "left", "start", "end").containsAll(values.keySet())) {
            return null;
        }
        return new double[] {values.getOrDefault("top", 0.0), values.getOrDefault("right", values.getOrDefault("end", 0.0)),
            values.getOrDefault("bottom", 0.0), values.getOrDefault("left", values.getOrDefault("start", 0.0))};
    }

    /** `Border.all(color: X, width: 1)` → 괄호 안. 괄호가 없으면 빈 문자열 */
    private static String inner(String expr) {
        int open = expr.indexOf('(');
        return open < 0 || !expr.endsWith(")") ? "" : expr.substring(open + 1, expr.length() - 1);
    }

    /** 최상위 쉼표로 나눈 이름 있는 인자 — 이름 없는 인자가 섞이면 null */
    private static Map<String, String> arguments(String body) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        int depth = 0;
        int start = 0;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(body.substring(start));
        for (String p : parts) {
            if (p.isBlank()) {
                continue; // 끝 쉼표
            }
            int colon = p.indexOf(':');
            if (colon < 0) {
                return null;
            }
            out.put(p.substring(0, colon).strip(), p.substring(colon + 1).strip());
        }
        return out;
    }

    /**
     * 저장소 Dart 파일의 상수 — 클래스 안 `static const`는 `클래스.이름`, 최상위 `const`는 `이름`으로 모은다.
     * 같은 이름이 여러 번 나오면 어느 값인지 알 수 없어 뺀다
     */
    static Map<String, String> constants(List<String> sources) {
        Map<String, String> out = new HashMap<>();
        Set<String> ambiguous = new java.util.HashSet<>();
        for (String src : sources) {
            String currentClass = null;
            for (String line : src.split("\n", -1)) {
                Matcher c = CLASS.matcher(line);
                if (c.find() && !line.startsWith(" ") && !line.startsWith("\t")) {
                    currentClass = c.group(1);
                    continue;
                }
                if (line.startsWith("}")) {
                    currentClass = null;
                    continue;
                }
                Matcher m = CONST.matcher(line);
                if (!m.find()) {
                    continue;
                }
                boolean topLevel = m.group(1).isEmpty();
                boolean isStatic = line.strip().startsWith("static ");
                String key;
                if (topLevel && !isStatic) {
                    key = m.group(2);
                } else if (isStatic && currentClass != null) {
                    key = currentClass + "." + m.group(2);
                } else {
                    continue;
                }
                if (out.containsKey(key) && !out.get(key).equals(m.group(3).strip())) {
                    ambiguous.add(key);
                }
                out.put(key, m.group(3).strip());
            }
        }
        ambiguous.forEach(out::remove);
        return out;
    }

}
