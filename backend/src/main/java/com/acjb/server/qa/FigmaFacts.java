package com.acjb.server.qa;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.acjb.server.domain.StyleProperty;

import tools.jackson.databind.JsonNode;

/**
 * 첫 QA의 Figma 쪽 값(2026-11-10 스파이크) — 매핑 노드 하위 요소의 스타일 값을 Figma API 노드 JSON에서 그대로 읽는다(LLM 없음, N17).
 * 위→아래 깊이 우선으로 값이 있는 요소를 최대 {@link #MAX_ELEMENTS}개. 보이지 않는 요소, 벡터·선·도형은 뺀다
 */
final class FigmaFacts {

    static final int MAX_ELEMENTS = 40;
    private static final Set<String> SHAPES = Set.of("VECTOR", "BOOLEAN_OPERATION", "LINE", "ELLIPSE", "STAR", "POLYGON");

    /** 요소 하나에서 비교하는 값의 종류. StyleProperty보다 잘다 — 색은 글자·배경·테두리, 간격은 padding·gap */
    enum Fact {
        TEXT("text", StyleProperty.TEXT),
        COLOR("color", StyleProperty.COLOR),
        FONT_WEIGHT("font_weight", StyleProperty.FONT_WEIGHT),
        FONT_SIZE("font_size", StyleProperty.FONT_SIZE),
        BACKGROUND("background", StyleProperty.COLOR),
        RADIUS("radius", StyleProperty.RADIUS),
        PADDING("padding", StyleProperty.SPACING),
        GAP("gap", StyleProperty.SPACING),
        BORDER("border", StyleProperty.COLOR);

        final String key;
        final StyleProperty property;

        Fact(String key, StyleProperty property) {
            this.key = key;
            this.property = property;
        }

        static Optional<Fact> of(String key) {
            for (Fact f : values()) {
                if (f.key.equals(key)) {
                    return Optional.of(f);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Figma 요소 하나. values 값의 형: 색은 "#rrggbb", 길이·굵기는 Double, padding은 double[4](위·오른쪽·아래·왼쪽), 문구는 String.
     * width·height는 반지름을 실제로 그려지는 값으로 자를 때, lead는 앞쪽 아이콘(첫 자식 너비 + gap)을 더한 왼쪽 여백을 볼 때 쓴다
     */
    record Element(String id, String name, String type, String path, Map<Fact, Object> values, Double width, Double height, Double lead) {
    }

    private FigmaFacts() {
    }

    static Optional<JsonNode> find(JsonNode root, String id) {
        if (root == null) {
            return Optional.empty();
        }
        if (id.equals(text(root, "id"))) {
            return Optional.of(root);
        }
        JsonNode kids = root.get("children");
        if (kids != null && kids.isArray()) {
            for (JsonNode c : kids) {
                Optional<JsonNode> r = find(c, id);
                if (r.isPresent()) {
                    return r;
                }
            }
        }
        return Optional.empty();
    }

    /** allowed에 든 종류만 모은다 — 고른 검증 범위 밖 값은 LLM에 묻지도 않는다 */
    static List<Element> collect(JsonNode root, Set<Fact> allowed) {
        List<Element> out = new ArrayList<>();
        walk(root, text(root, "name"), allowed, out);
        return out;
    }

    private static void walk(JsonNode n, String path, Set<Fact> allowed, List<Element> out) {
        if (out.size() >= MAX_ELEMENTS || hidden(n)) {
            return;
        }
        element(n, path, allowed).ifPresent(out::add);
        JsonNode kids = n.get("children");
        if (kids != null && kids.isArray()) {
            for (JsonNode c : kids) {
                walk(c, path + " > " + text(c, "name"), allowed, out);
            }
        }
    }

    static Optional<Element> element(JsonNode n, String path, Set<Fact> allowed) {
        String type = text(n, "type");
        if (hidden(n) || SHAPES.contains(type)) {
            return Optional.empty();
        }
        Map<Fact, Object> v = new EnumMap<>(Fact.class);
        if ("TEXT".equals(type)) {
            String chars = text(n, "characters").strip();
            if (!chars.isEmpty()) {
                v.put(Fact.TEXT, chars.length() > 80 ? chars.substring(0, 80) : chars);
            }
            solid(n.get("fills"), n.get("opacity")).ifPresent(c -> v.put(Fact.COLOR, c));
            JsonNode style = n.get("style");
            number(style, "fontWeight").ifPresent(w -> v.put(Fact.FONT_WEIGHT, w));
            number(style, "fontSize").ifPresent(s -> v.put(Fact.FONT_SIZE, s));
        } else {
            solid(n.get("fills"), n.get("opacity")).ifPresent(c -> v.put(Fact.BACKGROUND, c));
            radius(n).ifPresent(r -> v.put(Fact.RADIUS, r));
            String layout = text(n, "layoutMode");
            if ("HORIZONTAL".equals(layout) || "VERTICAL".equals(layout)) {
                double[] pad = {num(n, "paddingTop"), num(n, "paddingRight"), num(n, "paddingBottom"), num(n, "paddingLeft")};
                if (pad[0] != 0 || pad[1] != 0 || pad[2] != 0 || pad[3] != 0) {
                    v.put(Fact.PADDING, pad);
                }
                if (num(n, "itemSpacing") != 0) {
                    v.put(Fact.GAP, num(n, "itemSpacing"));
                }
            }
            if (num(n, "strokeWeight") > 0) {
                solid(n.get("strokes"), null).ifPresent(c -> v.put(Fact.BORDER, c));
            }
        }
        v.keySet().retainAll(allowed);
        if (v.isEmpty()) {
            return Optional.empty();
        }
        JsonNode box = n.get("absoluteBoundingBox");
        Double w = box == null ? null : number(box, "width").orElse(null);
        Double h = box == null ? null : number(box, "height").orElse(null);
        return Optional.of(new Element(text(n, "id"), text(n, "name"), type, path, v, w, h, lead(n)));
    }

    /** 가로 오토 레이아웃의 첫 자식이 글자가 아니면(아이콘 등) 그 너비 + gap */
    private static Double lead(JsonNode n) {
        if (!"HORIZONTAL".equals(text(n, "layoutMode"))) {
            return null;
        }
        JsonNode kids = n.get("children");
        if (kids == null || !kids.isArray()) {
            return null;
        }
        for (JsonNode c : kids) {
            if (hidden(c)) {
                continue;
            }
            if ("TEXT".equals(text(c, "type"))) {
                return null;
            }
            JsonNode box = c.get("absoluteBoundingBox");
            return box == null ? null : number(box, "width").map(w -> w + num(n, "itemSpacing")).orElse(null);
        }
        return null;
    }

    /** 보이는 첫 칠이 불투명 단색일 때만 — 겹친 결과를 추정하지 않는다 */
    private static Optional<String> solid(JsonNode paints, JsonNode nodeOpacity) {
        if (nodeOpacity != null && nodeOpacity.isNumber() && nodeOpacity.asDouble() < 0.999) {
            return Optional.empty();
        }
        if (paints == null || !paints.isArray()) {
            return Optional.empty();
        }
        for (JsonNode p : paints) {
            JsonNode visible = p.get("visible");
            if (visible != null && visible.isBoolean() && !visible.asBoolean()) {
                continue;
            }
            JsonNode op = p.get("opacity");
            JsonNode c = p.get("color");
            if (!"SOLID".equals(text(p, "type")) || (op != null && op.isNumber() && op.asDouble() < 0.999) || c == null || !c.isObject()) {
                return Optional.empty();
            }
            JsonNode a = c.get("a");
            if (a != null && a.isNumber() && a.asDouble() < 0.999) {
                return Optional.empty();
            }
            return Optional.of(String.format("#%02x%02x%02x", channel(c, "r"), channel(c, "g"), channel(c, "b")));
        }
        return Optional.empty();
    }

    private static Optional<Double> radius(JsonNode n) {
        JsonNode corners = n.get("rectangleCornerRadii");
        if (corners != null && corners.isArray() && corners.size() == 4) {
            double first = corners.get(0).asDouble();
            for (JsonNode c : corners) {
                if (c.asDouble() != first) {
                    return Optional.empty(); // 모서리마다 다르면 하나의 값으로 비교하지 않는다
                }
            }
            return first > 0 ? Optional.of(first) : Optional.empty();
        }
        return number(n, "cornerRadius").filter(r -> r > 0);
    }

    static String name(JsonNode n) {
        return text(n, "name");
    }

    private static boolean hidden(JsonNode n) {
        JsonNode v = n.get("visible");
        return v != null && v.isBoolean() && !v.asBoolean();
    }

    private static int channel(JsonNode c, String k) {
        JsonNode v = c.get(k);
        return v == null || !v.isNumber() ? 0 : (int) Math.round(Math.max(0, Math.min(1, v.asDouble())) * 255);
    }

    private static Optional<Double> number(JsonNode n, String k) {
        JsonNode v = n == null ? null : n.get(k);
        return v != null && v.isNumber() ? Optional.of(v.asDouble()) : Optional.empty();
    }

    private static double num(JsonNode n, String k) {
        return number(n, k).orElse(0.0);
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n == null ? null : n.get(k);
        return v != null && v.isString() ? v.asString() : "";
    }
}
