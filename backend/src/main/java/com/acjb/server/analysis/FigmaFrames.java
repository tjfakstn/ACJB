package com.acjb.server.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** Figma 노드 트리를 LLM 입력으로 바꾼다 — 스파이크(docs/spikes/2026-11-07_design-code-mapping.md)와 같은 요약 규칙 */
final class FigmaFrames {

    /** 화면 단위로 보는 노드 종류 */
    static final Set<String> FRAME_TYPES = Set.of("FRAME", "COMPONENT", "COMPONENT_SET");
    private static final int CHILDREN = 8;
    private static final int TEXTS = 12;
    private static final int TREE_DEPTH = 6;

    private FigmaFrames() {
    }

    static DesignCodeMapper.FrameSummary summary(JsonNode frame, String section) {
        List<String> kids = new ArrayList<>();
        for (JsonNode child : children(frame).subList(0, Math.min(CHILDREN, children(frame).size()))) {
            List<String> grand = children(child).stream().limit(CHILDREN).map(FigmaFrames::name).toList();
            kids.add(name(child) + (grand.isEmpty() ? "" : " > " + String.join(", ", grand)));
        }
        Set<String> texts = new LinkedHashSet<>();
        collectTexts(frame, texts);
        return new DesignCodeMapper.FrameSummary(text(frame, "id"), section, name(frame), kids, List.copyOf(texts));
    }

    /** "[id] TYPE 'name'" 줄로 적은 트리와, 그 안 노드 id(프레임 자신 제외) */
    static DesignCodeMapper.FrameDetail detail(JsonNode frame, Set<String> nodeIdsOut) {
        StringBuilder sb = new StringBuilder();
        tree(frame, 0, sb, nodeIdsOut);
        nodeIdsOut.remove(text(frame, "id"));
        return new DesignCodeMapper.FrameDetail(text(frame, "id"), name(frame), sb.toString());
    }

    /** 노드 id → 이름(노드 매핑을 저장할 때 이름을 붙인다) */
    static void names(JsonNode node, java.util.Map<String, String> out) {
        out.put(text(node, "id"), name(node));
        for (JsonNode child : children(node)) {
            names(child, out);
        }
    }

    private static void tree(JsonNode node, int depth, StringBuilder sb, Set<String> ids) {
        String id = text(node, "id");
        ids.add(id);
        sb.append("  ".repeat(depth)).append('[').append(id).append("] ").append(text(node, "type")).append(" '").append(name(node)).append('\'');
        if ("TEXT".equals(text(node, "type"))) {
            String chars = text(node, "characters");
            sb.append(" text=\"").append(chars.length() > 50 ? chars.substring(0, 50) : chars).append('"');
        }
        sb.append('\n');
        if (depth < TREE_DEPTH) {
            for (JsonNode child : children(node)) {
                tree(child, depth + 1, sb, ids);
            }
        }
    }

    private static void collectTexts(JsonNode node, Set<String> out) {
        if (out.size() >= TEXTS) {
            return;
        }
        if ("TEXT".equals(text(node, "type"))) {
            String t = text(node, "characters").strip().replace('\n', ' ');
            if (!t.isEmpty()) {
                out.add(t.length() > 60 ? t.substring(0, 60) : t);
            }
        }
        for (JsonNode child : children(node)) {
            collectTexts(child, out);
        }
    }

    static List<JsonNode> children(JsonNode node) {
        JsonNode c = node.get("children");
        List<JsonNode> list = new ArrayList<>();
        if (c != null && c.isArray()) {
            c.forEach(list::add);
        }
        return list;
    }

    private static String name(JsonNode node) {
        return text(node, "name");
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.asString() : "";
    }
}
