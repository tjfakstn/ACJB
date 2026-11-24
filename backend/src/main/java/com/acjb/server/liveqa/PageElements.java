package com.acjb.server.liveqa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.acjb.server.domain.PageElement;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 캡처에 남기는 요소 목록(JSON)과, 찍은 점의 요소 고르기(2026-11-20, AC31).
 * 점을 담는 요소 중 넓이가 가장 작은 것 — 같으면 문서에서 뒤(더 안쪽)의 것. 사람이 찍은 좌표와 서버가 본 화면만으로 정하고, 지어내지 않는다
 */
final class PageElements {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PageElements() {
    }

    static String write(List<PageElement> elements) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PageElement e : elements) {
            rows.add(Map.of("s", e.selector(), "t", e.text(), "x", e.x(), "y", e.y(), "w", e.width(), "h", e.height()));
        }
        return JSON.writeValueAsString(rows);
    }

    static List<PageElement> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows = JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        List<PageElement> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new PageElement((String) r.get("s"), (String) r.getOrDefault("t", ""), ((Number) r.get("x")).intValue(),
                ((Number) r.get("y")).intValue(), ((Number) r.get("w")).intValue(), ((Number) r.get("h")).intValue()));
        }
        return out;
    }

    /** 점 (x, y)를 담는 가장 작은 요소. 없으면 null */
    static PageElement at(List<PageElement> elements, int x, int y) {
        PageElement best = null;
        for (PageElement e : elements) {
            if (e.contains(x, y) && (best == null || e.area() <= best.area())) {
                best = e;
            }
        }
        return best;
    }
}
