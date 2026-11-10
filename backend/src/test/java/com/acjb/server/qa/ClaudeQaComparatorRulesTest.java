package com.acjb.server.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.acjb.server.domain.StyleProperty;
import com.acjb.server.qa.ClaudeQaComparator.Binding;
import com.acjb.server.qa.FigmaFacts.Element;
import com.acjb.server.qa.FigmaFacts.Fact;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 첫 QA 실제 비교기의 결정적 부분(2026-11-10 스파이크 규칙) — Figma 값 추출, Tailwind 변환, 비교 규칙, 줄·토큰 검증.
 * LLM 호출은 여기서 부르지 않는다(응답 대신 Binding을 직접 넣는다)
 */
class ClaudeQaComparatorRulesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String THEME = "@theme { --color-qa-primary: #007fff; --color-qa-text-sub: #5f5f5f; --color-qa-border: #e2e8f0; }";

    private static JsonNode json(String s) {
        return JSON.readTree(s);
    }

    private static final String NODE = """
        {"id":"1:1","name":"Card","type":"FRAME","layoutMode":"HORIZONTAL","paddingTop":16,"paddingRight":24,"paddingBottom":16,"paddingLeft":24,
         "itemSpacing":8,"cornerRadius":12,"strokeWeight":1,"strokes":[{"type":"SOLID","color":{"r":0.886,"g":0.910,"b":0.941,"a":1}}],
         "fills":[{"type":"SOLID","color":{"r":1,"g":1,"b":1,"a":1}}],"absoluteBoundingBox":{"width":300,"height":60},
         "children":[
          {"id":"1:2","name":"Title","type":"TEXT","characters":"첫 QA 결과","style":{"fontWeight":700,"fontSize":16},
           "fills":[{"type":"SOLID","color":{"r":0,"g":0,"b":0,"a":1}}]},
          {"id":"1:3","name":"Hidden","type":"TEXT","visible":false,"characters":"숨김","style":{"fontWeight":400,"fontSize":12}},
          {"id":"1:4","name":"Icon","type":"VECTOR","strokeWeight":2,"strokes":[{"type":"SOLID","color":{"r":1,"g":0,"b":0,"a":1}}]},
          {"id":"1:5","name":"Tag","type":"FRAME","cornerRadius":999,"fills":[{"type":"SOLID","opacity":0.1,"color":{"r":0,"g":0,"b":1,"a":1}}],
           "absoluteBoundingBox":{"width":40,"height":20}}
         ]}""";

    @Test
    void Figma_값은_노드_JSON에서_그대로_읽고_숨김_도형_투명_칠은_뺀다() {
        List<Element> all = FigmaFacts.collect(json(NODE), EnumSet.allOf(Fact.class));
        assertThat(all).extracting(Element::id).containsExactly("1:1", "1:2", "1:5");
        Element card = all.get(0);
        assertThat(card.values()).containsEntry(Fact.BACKGROUND, "#ffffff").containsEntry(Fact.RADIUS, 12.0).containsEntry(Fact.GAP, 8.0)
            .containsEntry(Fact.BORDER, "#e2e8f0");
        assertThat((double[]) card.values().get(Fact.PADDING)).containsExactly(16, 24, 16, 24);
        assertThat(all.get(1).values()).containsEntry(Fact.TEXT, "첫 QA 결과").containsEntry(Fact.COLOR, "#000000")
            .containsEntry(Fact.FONT_WEIGHT, 700.0).containsEntry(Fact.FONT_SIZE, 16.0);
        // 투명도 있는 칠(opacity 0.1)은 배경색으로 비교하지 않는다 — 반지름만 남는다
        assertThat(all.get(2).values()).containsOnlyKeys(Fact.RADIUS);
    }

    @Test
    void 고른_범위의_값만_모은다() {
        List<Element> radiusOnly = FigmaFacts.collect(json(NODE), EnumSet.of(Fact.RADIUS));
        assertThat(radiusOnly).extracting(Element::id).containsExactly("1:1", "1:5");
        assertThat(radiusOnly.get(0).values()).containsOnlyKeys(Fact.RADIUS);
    }

    @Test
    void Tailwind_클래스는_테마로_값을_바꾸고_읽을_수_없으면_null() {
        TailwindValues tw = new TailwindValues(List.of(THEME));
        assertThat(tw.resolve(Fact.COLOR, "text-qa-text-sub")).isEqualTo("#5f5f5f");
        assertThat(tw.resolve(Fact.COLOR, "text-gray-900")).isEqualTo("#101828"); // 기본 팔레트 oklch → sRGB
        assertThat(tw.resolve(Fact.BACKGROUND, "bg-[#c8161a]")).isEqualTo("#c8161a");
        assertThat(tw.resolve(Fact.BACKGROUND, "bg-qa-primary/10")).isNull(); // 투명도
        assertThat(tw.resolve(Fact.BACKGROUND, "hover:bg-white")).isNull(); // 변형뿐
        assertThat(tw.resolve(Fact.RADIUS, "rounded-xl")).isEqualTo(12.0);
        assertThat(tw.resolve(Fact.RADIUS, "rounded-full")).isEqualTo("full");
        assertThat((double[]) tw.resolve(Fact.PADDING, "px-6 py-4")).containsExactly(16, 24, 16, 24);
        assertThat(tw.resolve(Fact.GAP, "gap-1.5")).isEqualTo(6.0);
        assertThat(tw.resolve(Fact.FONT_WEIGHT, "font-semibold")).isEqualTo(600.0);
        assertThat(tw.resolve(Fact.FONT_SIZE, "text-[28px]")).isEqualTo(28.0);
        assertThat(tw.resolve(Fact.FONT_SIZE, "text-sm")).isEqualTo(14.0);
    }

    private static Element el(Map<Fact, Object> v, Double w, Double h, Double lead) {
        return new Element("1:1", "E", "FRAME", "E", v, w, h, lead);
    }

    @Test
    void 비교_규칙_v2() {
        // 고정 높이로 세로를 맞춘 요소는 가로만 본다
        Element btn = el(Map.of(Fact.PADDING, new double[] {8, 12, 8, 12}), 100.0, 40.0, null);
        assertThat(StyleRules.same(Fact.PADDING, btn, new double[] {0, 12, 0, 12}, "className=\"h-10 px-3\"")).contains(true);
        assertThat(StyleRules.same(Fact.PADDING, btn, new double[] {0, 12, 0, 12}, "className=\"px-3\"")).contains(false);
        // 아이콘을 absolute로 둔 입력창 — 12 + 아이콘 16 + gap 8 = 36
        Element field = el(Map.of(Fact.PADDING, new double[] {0, 12, 0, 12}), 200.0, 40.0, 24.0);
        assertThat(StyleRules.same(Fact.PADDING, field, new double[] {0, 12, 0, 36}, "h-10 pl-9 pr-3")).contains(true);
        // 높이 2px 막대 — 반지름 4도 1로 그려진다
        Element bar = el(Map.of(Fact.RADIUS, 1.0), 32.0, 2.0, null);
        assertThat(StyleRules.same(Fact.RADIUS, bar, 4.0, "rounded-sm")).contains(true);
        Element chip = el(Map.of(Fact.RADIUS, 999.0), 40.0, 20.0, null);
        assertThat(StyleRules.same(Fact.RADIUS, chip, "full", "rounded-full")).contains(true);
        assertThat(StyleRules.same(Fact.RADIUS, chip, 6.0, "rounded-md")).contains(false);
        // 색은 채널 ±3
        Element text = el(Map.of(Fact.COLOR, "#101828"), null, null, null);
        assertThat(StyleRules.same(Fact.COLOR, text, "#11192a", "")).contains(true);
        assertThat(StyleRules.same(Fact.COLOR, text, "#000000", "")).contains(false);
        // 읽을 수 없는 값은 비교하지 않는다
        assertThat(StyleRules.same(Fact.COLOR, text, null, "")).isEmpty();
    }

    @Test
    void 값_표기는_Figma와_코드가_같은_규칙이고_Re_QA가_종류를_되찾는다() {
        assertThat(StyleRules.format(Fact.PADDING, new double[] {16, 24, 16, 24}, false)).isEqualTo("padding 16px 24px");
        assertThat(StyleRules.format(Fact.PADDING, new double[] {0, 12, 0, 36}, true)).isEqualTo("padding 좌우 36px 12px");
        assertThat(StyleRules.format(Fact.RADIUS, 999.0, false)).isEqualTo("full");
        assertThat(StyleRules.format(Fact.GAP, 2.5, false)).isEqualTo("gap 2.5px");
        assertThat(StyleRules.format(Fact.BACKGROUND, "#ffffff", false)).isEqualTo("배경 #ffffff");
        assertThat(StyleRules.format(Fact.FONT_WEIGHT, 700.0, false)).isEqualTo("700");
        assertThat(StyleRules.factOf(StyleProperty.COLOR, "배경 #ffffff")).contains(Fact.BACKGROUND);
        assertThat(StyleRules.factOf(StyleProperty.COLOR, "테두리 #e2e8f0")).contains(Fact.BORDER);
        assertThat(StyleRules.factOf(StyleProperty.SPACING, "gap 8px")).contains(Fact.GAP);
        assertThat(StyleRules.factOf(StyleProperty.SPACING, "12px")).isEmpty(); // 형식을 모르면 다시 읽지 않는다(N18)
    }

    @Test
    void 토큰이_그_줄에_없으면_버리고_다르면_차이로_돌려준다() {
        List<Element> elements = FigmaFacts.collect(json(NODE), EnumSet.allOf(Fact.class));
        Map<String, String> code = Map.of("src/Card.tsx", String.join("\n",
            "export function Card() {",
            "  return <div className=\"flex gap-2 rounded-lg border border-qa-border bg-white px-6 py-4\">",
            "    <h2 className=\"text-base font-bold text-black\">첫 QA 결과</h2>",
            "    <span className=\"rounded-full bg-qa-primary/10\">{label}</span>",
            "  </div>",
            "}"));
        QaComparator.Target t = new QaComparator.Target("F", "홈", "1:1", "Card", "src/Card.tsx", "Card");
        List<Binding> bindings = List.of(
            new Binding("1:1", "radius", "src/Card.tsx", 2, "rounded-lg"),        // 12 vs 8 → 차이
            new Binding("1:1", "padding", "src/Card.tsx", 2, "px-6 py-4"),        // 같음
            new Binding("1:1", "gap", "src/Card.tsx", 3, "gap-2"),                // 그 줄에 없음 → 버림
            new Binding("1:2", "font_weight", "src/Card.tsx", 3, "font-bold"),    // 같음
            new Binding("1:2", "text", "src/Card.tsx", 3, "첫 QA 결과"),          // 같음
            new Binding("1:2", "font_weight", "src/Card.tsx", 3, "font-bold"),    // 같은 요소·property 두 번째 → 버림
            new Binding("9:9", "color", "src/Card.tsx", 3, "text-black"),         // 묻지 않은 요소 → 버림
            new Binding("1:5", "background", "src/Card.tsx", 4, "bg-qa-primary/10")); // Figma에 배경 값 없음(투명) → 버림
        var r = ClaudeQaComparator.judgeWith(t, elements, code, bindings, new TailwindValues(List.of(THEME)));
        assertThat(r.findings()).hasSize(1);
        QaComparator.Finding f = r.findings().get(0);
        assertThat(f.nodeId()).isEqualTo("1:1");
        assertThat(f.property()).isEqualTo(StyleProperty.RADIUS);
        assertThat(f.expectedValue()).isEqualTo("12px");
        assertThat(f.actualValue()).isEqualTo("8px");
        assertThat(f.codeLine()).isEqualTo(2);
        assertThat(r.dropped()).isEqualTo(4);
    }

    @Test
    void 검증_범위는_측정할_수_있는_4개이고_예전_값은_건너뛴다() {
        assertThat(QaScope.values()).extracting(QaScope::value).containsExactly("size_spacing", "color_typography", "border_radius", "text");
        assertThat(QaScope.propertiesOf(Set.of(QaScope.COLOR_TYPOGRAPHY)))
            .containsExactly(StyleProperty.COLOR, StyleProperty.FONT_WEIGHT, StyleProperty.FONT_SIZE);
        assertThat(QaScope.parse("layout")).isEmpty();
        assertThat(QaScope.parse("presence")).isEmpty();
    }
}
