package com.acjb.server.scan;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * Figma 노드 JSON(GET /v1/files/{key}/nodes의 document)에서 비교할 값을 꺼낸다. 규칙은 스파이크
 * (docs/spikes/2026-09-28_style-mismatch-auto-extraction.md)를 따른다.
 * <ul>
 *   <li>color: 보이는 첫 fill이 SOLID이고 불투명할 때 r·g·b(0~1)를 ×255 반올림. 그라디언트·이미지·투명도는 범위 밖 → null</li>
 *   <li>font_weight: 노드가 TEXT면 그 style.fontWeight, 아니면 첫 TEXT 자손의 값</li>
 *   <li>radius: cornerRadius, 또는 rectangleCornerRadii 네 값이 모두 같을 때 그 값. 값이 없으면 추정하지 않고 null</li>
 * </ul>
 */
public final class FigmaStyleExtractor {

    private FigmaStyleExtractor() {
    }

    /** root 자신 또는 그 자손 중 id가 nodeId인 노드. node_id가 frame_id 안에 있는지 확인하는 데도 쓴다 */
    public static Optional<JsonNode> find(JsonNode root, String nodeId) {
        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            JsonNode n = stack.pop();
            if (nodeId.equals(text(n, "id"))) {
                return Optional.of(n);
            }
            pushChildren(stack, n);
        }
        return Optional.empty();
    }

    public static boolean isText(JsonNode node) {
        return "TEXT".equals(text(node, "type"));
    }

    public static StyleValues extract(JsonNode node) {
        return new StyleValues(color(node), fontWeight(node), radius(node));
    }

    static StyleValues.Rgb color(JsonNode node) {
        if (!opaque(node.get("opacity"))) {
            return null;
        }
        JsonNode fills = node.get("fills");
        if (fills == null || !fills.isArray()) {
            return null;
        }
        for (JsonNode paint : fills) {
            JsonNode visible = paint.get("visible");
            if (visible != null && visible.isBoolean() && !visible.asBoolean()) {
                continue;
            }
            // 보이는 첫 fill만 본다 — 그 fill이 단색·불투명이 아니면 겹친 결과를 추정하지 않는다
            if (!"SOLID".equals(text(paint, "type")) || !opaque(paint.get("opacity"))) {
                return null;
            }
            JsonNode c = paint.get("color");
            if (c == null || !c.isObject() || !opaque(c.get("a"))) {
                return null;
            }
            Integer r = channel(c.get("r"));
            Integer g = channel(c.get("g"));
            Integer b = channel(c.get("b"));
            return r == null || g == null || b == null ? null : new StyleValues.Rgb(r, g, b);
        }
        return null;
    }

    static Integer fontWeight(JsonNode node) {
        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(node);
        while (!stack.isEmpty()) {
            JsonNode n = stack.pop();
            if (isText(n)) {
                JsonNode style = n.get("style");
                JsonNode w = style == null ? null : style.get("fontWeight");
                if (w != null && w.isNumber() && w.asDouble() == Math.rint(w.asDouble()) && w.asDouble() >= 1 && w.asDouble() <= 1000) {
                    return (int) w.asDouble();
                }
                return null;
            }
            pushChildren(stack, n);
        }
        return null;
    }

    static Double radius(JsonNode node) {
        JsonNode corners = node.get("rectangleCornerRadii");
        if (corners != null && corners.isArray()) {
            if (corners.size() != 4) {
                return null;
            }
            Double first = null;
            for (JsonNode v : corners) {
                if (!v.isNumber() || v.asDouble() < 0) {
                    return null;
                }
                if (first == null) {
                    first = v.asDouble();
                } else if (first != v.asDouble()) {
                    return null; // 모서리마다 다르면 단일 값으로 비교하지 않는다
                }
            }
            return first;
        }
        JsonNode r = node.get("cornerRadius");
        return r != null && r.isNumber() && r.asDouble() >= 0 ? r.asDouble() : null;
    }

    /** 값이 없으면 1(불투명)로 본다 — Figma는 기본값을 생략한다 */
    private static boolean opaque(JsonNode alpha) {
        return alpha == null || (alpha.isNumber() && alpha.asDouble() >= 1.0);
    }

    private static Integer channel(JsonNode v) {
        if (v == null || !v.isNumber() || v.asDouble() < 0 || v.asDouble() > 1) {
            return null;
        }
        return (int) Math.round(v.asDouble() * 255);
    }

    private static void pushChildren(Deque<JsonNode> stack, JsonNode n) {
        JsonNode children = n.get("children");
        if (children != null && children.isArray()) {
            // 앞쪽 자식부터 보도록 뒤에서부터 쌓는다(문서 순서대로 "첫 TEXT 자손")
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }
}
