package com.acjb.server.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.acjb.server.domain.StyleProperty;
import com.acjb.server.qa.ClaudeQaComparator.Binding;
import com.acjb.server.qa.FigmaFacts.Element;
import com.acjb.server.qa.FigmaFacts.Fact;

import tools.jackson.databind.json.JsonMapper;

/**
 * 첫 QA가 Tailwind 밖의 스타일을 읽는 규칙(2026-11-12) — CSS·SCSS·CSS modules 선언, styled-components 템플릿 줄, 인라인 style·CSS-in-JS 객체.
 * 값은 선언 글자에서만 읽고, 못 읽는 값(보간·변수·저장소에 없는 var()·투명도·한 변 padding)은 비교하지 않는다(N17)
 */
class CssValuesTest {

    private static final String ROOT_CSS = ":root { --brand: #007fff; --space-m: 16px; --alias: var(--brand); }";
    private static final TailwindValues VALUES = new TailwindValues(List.of(), List.of(ROOT_CSS));

    private static Object css(Fact fact, String token) {
        return VALUES.value(fact, token, "src/Button.module.css", "  " + token + ";");
    }

    private static Object inline(Fact fact, String token) {
        return VALUES.value(fact, token, "src/Button.tsx", "<div style={{ " + token + " }}>");
    }

    @Test
    void 스타일시트_선언에서_값을_읽는다() {
        assertThat(css(Fact.RADIUS, "border-radius: 8px")).isEqualTo(8.0);
        assertThat(css(Fact.RADIUS, "border-radius: 0.5rem")).isEqualTo(8.0);
        assertThat(css(Fact.COLOR, "color: #1A1A1A")).isEqualTo("#1a1a1a");
        assertThat(css(Fact.COLOR, "color: rgb(255, 0, 0)")).isEqualTo("#ff0000");
        assertThat(css(Fact.BACKGROUND, "background-color: white")).isEqualTo("#ffffff");
        assertThat(css(Fact.BACKGROUND, "background: #fff")).isEqualTo("#ffffff");
        assertThat(css(Fact.BORDER, "border: 1px solid #e2e8f0")).isEqualTo("#e2e8f0");
        assertThat(css(Fact.FONT_WEIGHT, "font-weight: bold")).isEqualTo(700.0);
        assertThat(css(Fact.FONT_WEIGHT, "font-weight: 600")).isEqualTo(600.0);
        assertThat(css(Fact.FONT_SIZE, "font-size: 14px")).isEqualTo(14.0);
        assertThat(css(Fact.GAP, "gap: 8px")).isEqualTo(8.0);
        assertThat((double[]) css(Fact.PADDING, "padding: 12px 16px")).containsExactly(12, 16, 12, 16);
        assertThat((double[]) css(Fact.PADDING, "padding: 4px 8px 6px")).containsExactly(4, 8, 6, 8);
        assertThat(css(Fact.RADIUS, "border-radius: 8px !important")).isEqualTo(8.0);
    }

    @Test
    void 저장소_CSS에_적힌_var만_푼다() {
        assertThat(css(Fact.COLOR, "color: var(--brand)")).isEqualTo("#007fff");
        assertThat(css(Fact.COLOR, "color: var(--alias)")).isEqualTo("#007fff");
        assertThat((double[]) css(Fact.PADDING, "padding: var(--space-m)")).containsExactly(16, 16, 16, 16);
        assertThat(css(Fact.COLOR, "color: var(--unknown, #000)")).isNull(); // 런타임 값을 지어내지 않는다
    }

    @Test
    void 인라인_style과_CSS_in_JS_객체는_단위_없는_숫자를_px로_읽는다() {
        assertThat(inline(Fact.RADIUS, "borderRadius: 8")).isEqualTo(8.0);
        assertThat((double[]) inline(Fact.PADDING, "padding: '12px 16px'")).containsExactly(12, 16, 12, 16);
        assertThat((double[]) inline(Fact.PADDING, "padding: 12")).containsExactly(12, 12, 12, 12);
        assertThat(inline(Fact.COLOR, "color: \"#333\"")).isEqualTo("#333333");
        assertThat(inline(Fact.BACKGROUND, "backgroundColor: '#ffffff'")).isEqualTo("#ffffff");
        assertThat(inline(Fact.FONT_WEIGHT, "fontWeight: 700")).isEqualTo(700.0);
        // styled-components 템플릿 줄은 CSS다 — 단위 없는 숫자는 읽지 않는다
        assertThat(VALUES.value(Fact.RADIUS, "border-radius: 8", "src/Button.styles.ts", "  border-radius: 8;")).isNull();
        assertThat(VALUES.value(Fact.RADIUS, "border-radius: 8px", "src/Button.styles.ts", "  border-radius: 8px;")).isEqualTo(8.0);
    }

    @Test
    void 읽을_수_없는_값은_null() {
        assertThat(inline(Fact.COLOR, "color: theme.primary")).isNull();             // JS 변수
        assertThat(css(Fact.COLOR, "color: ${({ theme }) => theme.primary}")).isNull(); // 템플릿 보간
        assertThat(css(Fact.COLOR, "color: $primary")).isNull();                     // SCSS 변수
        assertThat(css(Fact.BACKGROUND, "background: rgba(0, 0, 0, 0.5)")).isNull(); // 투명도
        assertThat(css(Fact.BACKGROUND, "background: #fff url(bg.png)")).isNull();   // 색 하나가 아니다
        assertThat(css(Fact.FONT_SIZE, "font-size: 1.2em")).isNull();
        assertThat(css(Fact.RADIUS, "border-radius: 50%")).isNull();
        assertThat(css(Fact.RADIUS, "border-radius: 4px 8px")).isNull();             // 모서리마다 다르다
        assertThat(css(Fact.PADDING, "padding-left: 8px")).isNull();                // 한 변만 — 나머지를 모른다
        assertThat(css(Fact.GAP, "gap: calc(1rem + 2px)")).isNull();
        assertThat(css(Fact.FONT_SIZE, "font-size: 14")).isNull();                   // CSS의 단위 없는 숫자
        // 선언의 속성이 묻는 값과 다르면 근거로 쓰지 않는다
        assertThat(css(Fact.BACKGROUND, "color: #fff")).isNull();
        assertThat(css(Fact.COLOR, "border-color: #fff")).isNull();
    }

    @Test
    void Tailwind_토큰과_문구는_그대로_예전_방식으로_읽는다() {
        TailwindValues tw = new TailwindValues(List.of("@theme { --color-qa-primary: #007fff; }"), List.of());
        assertThat(tw.value(Fact.BACKGROUND, "bg-qa-primary", "src/A.tsx", "className=\"bg-qa-primary\"")).isEqualTo("#007fff");
        assertThat(tw.value(Fact.BACKGROUND, "hover:bg-white", "src/A.tsx", "className=\"hover:bg-white\"")).isNull();
        assertThat(tw.value(Fact.TEXT, "이메일: 필수", "src/A.tsx", "<p>이메일: 필수</p>")).isEqualTo("이메일: 필수");
    }

    @Test
    void CSS_modules_선언으로_차이를_찾고_줄에_없는_선언은_버린다() {
        String node = """
            {"id":"1:1","name":"Button","type":"FRAME","layoutMode":"HORIZONTAL","paddingTop":12,"paddingRight":16,"paddingBottom":12,
             "paddingLeft":16,"cornerRadius":8,"fills":[{"type":"SOLID","color":{"r":0,"g":0.498,"b":1,"a":1}}],
             "absoluteBoundingBox":{"width":120,"height":44}}""";
        List<Element> elements = FigmaFacts.collect(JsonMapper.builder().build().readTree(node), EnumSet.allOf(Fact.class));
        Map<String, String> code = Map.of(
            "src/Button.tsx", "import styles from './Button.module.css'\nexport const Button = () => <button className={styles.root}>저장</button>",
            "src/Button.module.css", String.join("\n",
                ".root {",
                "  padding: 12px 16px;",
                "  border-radius: 6px;",
                "  background-color: var(--brand);",
                "}"));
        QaComparator.Target t = new QaComparator.Target("F", "홈", "1:1", "Button", "src/Button.tsx", "Button");
        List<Binding> bindings = List.of(
            new Binding("1:1", "padding", "src/Button.module.css", 2, "padding: 12px 16px"),          // 같음
            new Binding("1:1", "radius", "src/Button.module.css", 3, "border-radius: 6px"),           // 8 vs 6 → 차이
            new Binding("1:1", "background", "src/Button.module.css", 4, "background-color: var(--brand)"), // 같음(#007fff)
            new Binding("1:1", "gap", "src/Button.module.css", 2, "gap: 8px"));                       // 그 줄에 없음 → 버림
        var r = ClaudeQaComparator.judgeWith(t, elements, code, bindings, VALUES);
        assertThat(r.findings()).hasSize(1);
        QaComparator.Finding f = r.findings().get(0);
        assertThat(f.property()).isEqualTo(StyleProperty.RADIUS);
        assertThat(f.expectedValue()).isEqualTo("8px");
        assertThat(f.actualValue()).isEqualTo("6px");
        assertThat(f.codePath()).isEqualTo("src/Button.module.css");
        assertThat(f.codeLine()).isEqualTo(3);
        assertThat(r.dropped()).isEqualTo(1);
    }
}
