package com.acjb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.acjb.server.domain.StyleProperty;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Figma 값 추출·화면 값 해석·건별 판정 규칙(스파이크 docs/spikes/2026-09-28_style-mismatch-auto-extraction.md) */
class StyleRulesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 스파이크의 "시작하기" 버튼 프레임(84:759)과 같은 구조 — 색·반경은 프레임, 굵기는 자식 TEXT(84:760) */
    static final String SPIKE_FRAME = """
        {"id":"84:759","name":"시작하기","type":"FRAME","cornerRadius":8,
         "fills":[{"type":"SOLID","color":{"r":0,"g":0.4470588,"b":0.8627451,"a":1}}],
         "children":[{"id":"84:760","name":"label","type":"TEXT","style":{"fontWeight":600},
                      "fills":[{"type":"SOLID","color":{"r":1,"g":1,"b":1,"a":1}}]}]}""";

    static JsonNode json(String s) {
        return JSON.readTree(s);
    }

    @Test
    void 스파이크_프레임에서_color_font_weight_radius를_꺼낸다() {
        StyleValues v = FigmaStyleExtractor.extract(json(SPIKE_FRAME));
        assertThat(v.color()).isEqualTo(new StyleValues.Rgb(0, 114, 220));
        assertThat(v.fontWeight()).isEqualTo(600);
        assertThat(v.radiusPx()).isEqualTo(8.0);
    }

    @Test
    void 프레임_안의_자손_노드를_id로_찾고_없으면_비어_있다() {
        JsonNode frame = json(SPIKE_FRAME);
        assertThat(FigmaStyleExtractor.find(frame, "84:760")).get().satisfies(n -> assertThat(FigmaStyleExtractor.isText(n)).isTrue());
        assertThat(FigmaStyleExtractor.find(frame, "84:759")).isPresent();
        assertThat(FigmaStyleExtractor.find(frame, "99:1")).isEmpty();
    }

    @Test
    void 범위_밖_값은_추정하지_않고_null() {
        // 투명도 있는 fill, 그라디언트, 노드 opacity, 모서리마다 다른 반경, 반경 없음, TEXT 없음
        assertThat(FigmaStyleExtractor.color(json("""
            {"fills":[{"type":"SOLID","color":{"r":0,"g":0,"b":0,"a":0.5}}]}"""))).isNull();
        assertThat(FigmaStyleExtractor.color(json("""
            {"fills":[{"type":"SOLID","opacity":0.4,"color":{"r":0,"g":0,"b":0,"a":1}}]}"""))).isNull();
        assertThat(FigmaStyleExtractor.color(json("""
            {"fills":[{"type":"GRADIENT_LINEAR"}]}"""))).isNull();
        assertThat(FigmaStyleExtractor.color(json("""
            {"opacity":0.8,"fills":[{"type":"SOLID","color":{"r":0,"g":0,"b":0,"a":1}}]}"""))).isNull();
        assertThat(FigmaStyleExtractor.radius(json("""
            {"cornerRadius":8,"rectangleCornerRadii":[8,8,0,0]}"""))).isNull();
        assertThat(FigmaStyleExtractor.radius(json("{}"))).isNull();
        assertThat(FigmaStyleExtractor.fontWeight(json("""
            {"type":"FRAME","children":[{"type":"RECTANGLE"}]}"""))).isNull();
    }

    @Test
    void 숨긴_fill은_건너뛰고_네_모서리가_같은_반경은_받는다() {
        assertThat(FigmaStyleExtractor.color(json("""
            {"fills":[{"type":"SOLID","visible":false,"color":{"r":1,"g":0,"b":0}},
                      {"type":"SOLID","color":{"r":0,"g":0,"b":1}}]}"""))).isEqualTo(new StyleValues.Rgb(0, 0, 255));
        assertThat(FigmaStyleExtractor.radius(json("""
            {"rectangleCornerRadii":[12,12,12,12]}"""))).isEqualTo(12.0);
    }

    @Test
    void 스파이크_6개_샘플을_모두_맞게_판정한다() {
        StyleValues figma = new StyleValues(new StyleValues.Rgb(0, 114, 220), 600, 8.0);
        // 일치 3건: Figma 값을 보고 같게 만든 테스트 버튼
        List<StyleComparator.Result> same = StyleComparator.compare(figma, new StyleValues(new StyleValues.Rgb(0, 114, 220), 600, 8.0));
        assertThat(same).hasSize(3).allMatch(StyleComparator.Result::matches);
        // 불일치 3건: 실제 Button primary(bg-gray-800 → oklch → (30,41,57), font-medium 500, rounded-md 6px)
        List<StyleComparator.Result> diff = StyleComparator.compare(figma, new StyleValues(new StyleValues.Rgb(30, 41, 57), 500, 6.0));
        assertThat(diff).hasSize(3).noneMatch(StyleComparator.Result::matches);
        assertThat(diff).extracting(StyleComparator.Result::property, StyleComparator.Result::expected, StyleComparator.Result::actual)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(StyleProperty.COLOR, "#0072DC", "#1E2939"),
                org.assertj.core.groups.Tuple.tuple(StyleProperty.FONT_WEIGHT, "600", "500"),
                org.assertj.core.groups.Tuple.tuple(StyleProperty.RADIUS, "8px", "6px"));
    }

    @Test
    void 허용_오차_경계() {
        StyleValues figma = new StyleValues(new StyleValues.Rgb(100, 100, 100), 400, 8.0);
        assertThat(StyleComparator.compare(figma, new StyleValues(new StyleValues.Rgb(102, 98, 100), 400, 9.0)))
            .allMatch(StyleComparator.Result::matches);
        assertThat(StyleComparator.compare(figma, new StyleValues(new StyleValues.Rgb(103, 100, 100), 401, 9.5)))
            .noneMatch(StyleComparator.Result::matches);
        assertThat(StyleComparator.px(7.5)).isEqualTo("7.5px");
    }

    @Test
    void 한쪽만_있는_property는_비교하지_않는다_N3() {
        StyleValues figma = new StyleValues(new StyleValues.Rgb(0, 0, 0), null, 8.0);
        StyleValues impl = new StyleValues(null, 700, 4.0);
        assertThat(StyleComparator.compare(figma, impl)).singleElement()
            .satisfies(r -> assertThat(r.property()).isEqualTo(StyleProperty.RADIUS));
    }

    @Test
    void 화면_값_해석_투명도_타원반경_모서리별_반경은_null() {
        ComputedStyleReader.ComputedStyle ok = PlaywrightComputedStyleReader.parse(Map.of(
            "background", List.of(0, 114, 220, 255), "text", List.of(255, 255, 255, 255),
            "opacity", "1", "fontWeight", "600", "radii", List.of("8px", "8px", "8px", "8px")));
        assertThat(ok.forFigmaNode(false)).isEqualTo(new StyleValues(new StyleValues.Rgb(0, 114, 220), 600, 8.0));
        assertThat(ok.forFigmaNode(true).color()).isEqualTo(new StyleValues.Rgb(255, 255, 255));

        ComputedStyleReader.ComputedStyle partial = PlaywrightComputedStyleReader.parse(Map.of(
            "background", List.of(0, 0, 0, 0), "text", List.of(0, 0, 0, 128),
            "opacity", "1", "fontWeight", "400", "radii", List.of("8px 4px", "8px", "8px", "8px")));
        assertThat(partial.backgroundColor()).isNull();
        assertThat(partial.textColor()).isNull();
        assertThat(partial.radiusPx()).isNull();
        assertThat(partial.fontWeight()).isEqualTo(400);

        ComputedStyleReader.ComputedStyle faded = PlaywrightComputedStyleReader.parse(Map.of(
            "background", List.of(0, 0, 0, 255), "text", List.of(0, 0, 0, 255),
            "opacity", "0.5", "fontWeight", "400", "radii", List.of("8px", "0px", "8px", "8px")));
        assertThat(faded.backgroundColor()).isNull();
        assertThat(faded.radiusPx()).isNull();
    }
}
