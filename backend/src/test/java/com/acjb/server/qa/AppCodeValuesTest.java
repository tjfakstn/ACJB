package com.acjb.server.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.acjb.server.domain.StyleProperty;
import com.acjb.server.qa.ClaudeQaComparator.Binding;
import com.acjb.server.qa.FigmaFacts.Element;
import com.acjb.server.qa.FigmaFacts.Fact;
import com.acjb.server.qa.QaComparator.Finding;
import com.acjb.server.qa.QaComparator.Target;

/**
 * 앱 코드의 값 읽기(2026-11-11) — Flutter 위젯 인자(DartValues)와 React Native 스타일 객체(CssValues의 JS 값).
 * 근거가 코드에 리터럴(또는 저장소 상수)로 있을 때만 값을 읽고, 아니면 null이라 비교하지 않는다(N17)
 */
class AppCodeValuesTest {

    private static final Map<String, String> NONE = Map.of();

    @Test
    void Flutter_색은_불투명한_리터럴과_흰색_검정만_읽는다() {
        assertThat(DartValues.value(Fact.COLOR, "color: Color(0xFF1A1A1A),", NONE)).isEqualTo("#1a1a1a");
        assertThat(DartValues.value(Fact.BACKGROUND, "color: const Color(0xff007FFF)", NONE)).isEqualTo("#007fff");
        assertThat(DartValues.value(Fact.BACKGROUND, "backgroundColor: Colors.white", NONE)).isEqualTo("#ffffff");
        assertThat(DartValues.value(Fact.COLOR, "color: Color.fromARGB(255, 16, 24, 40)", NONE)).isEqualTo("#101828");
        assertThat(DartValues.value(Fact.COLOR, "color: Color.fromRGBO(16, 24, 40, 1.0)", NONE)).isEqualTo("#101828");
        // 투명도·팔레트·테마·계산식은 읽지 않는다
        assertThat(DartValues.value(Fact.COLOR, "color: Color(0x80000000)", NONE)).isNull();
        assertThat(DartValues.value(Fact.COLOR, "color: Color.fromRGBO(0, 0, 0, 0.5)", NONE)).isNull();
        assertThat(DartValues.value(Fact.COLOR, "color: Colors.blue", NONE)).isNull();
        assertThat(DartValues.value(Fact.COLOR, "color: Theme.of(context).primaryColor", NONE)).isNull();
        assertThat(DartValues.value(Fact.COLOR, "color: Colors.black.withOpacity(0.5)", NONE)).isNull();
        // 다른 속성의 인자는 근거로 쓰지 않는다
        assertThat(DartValues.value(Fact.COLOR, "backgroundColor: Colors.white", NONE)).isNull();
        assertThat(DartValues.value(Fact.FONT_SIZE, "height: 14", NONE)).isNull();
    }

    @Test
    void Flutter_테두리_색은_색이_하나만_적힌_Border에서_읽는다() {
        assertThat(DartValues.value(Fact.BORDER, "border: Border.all(color: Color(0xFFE2E8F0), width: 1)", NONE)).isEqualTo("#e2e8f0");
        assertThat(DartValues.value(Fact.BORDER, "side: BorderSide(color: Colors.black)", NONE)).isEqualTo("#000000");
        assertThat(DartValues.value(Fact.BORDER, "border: Border(bottom: BorderSide(color: Colors.black))", NONE)).isNull();
    }

    @Test
    void Flutter_글자_굵기_크기_반지름_간격() {
        assertThat(DartValues.value(Fact.FONT_WEIGHT, "fontWeight: FontWeight.w600", NONE)).isEqualTo(600.0);
        assertThat(DartValues.value(Fact.FONT_WEIGHT, "fontWeight: FontWeight.bold,", NONE)).isEqualTo(700.0);
        assertThat(DartValues.value(Fact.FONT_SIZE, "fontSize: 14.0", NONE)).isEqualTo(14.0);
        assertThat(DartValues.value(Fact.RADIUS, "borderRadius: BorderRadius.circular(12)", NONE)).isEqualTo(12.0);
        assertThat(DartValues.value(Fact.RADIUS, "borderRadius: const BorderRadius.all(Radius.circular(8))", NONE)).isEqualTo(8.0);
        assertThat(DartValues.value(Fact.RADIUS, "borderRadius: BorderRadius.only(topLeft: Radius.circular(8))", NONE)).isNull();
        assertThat(DartValues.value(Fact.GAP, "spacing: 8", NONE)).isEqualTo(8.0);
    }

    @Test
    void Flutter_EdgeInsets는_위_오른쪽_아래_왼쪽으로_적지_않은_변은_0() {
        assertThat((double[]) DartValues.value(Fact.PADDING, "padding: const EdgeInsets.all(16)", NONE)).containsExactly(16, 16, 16, 16);
        assertThat((double[]) DartValues.value(Fact.PADDING, "padding: EdgeInsets.symmetric(horizontal: 24, vertical: 12),", NONE))
            .containsExactly(12, 24, 12, 24);
        assertThat((double[]) DartValues.value(Fact.PADDING, "padding: EdgeInsets.symmetric(horizontal: 16)", NONE)).containsExactly(0, 16, 0, 16);
        assertThat((double[]) DartValues.value(Fact.PADDING, "padding: EdgeInsets.fromLTRB(4, 8, 12, 16)", NONE)).containsExactly(8, 12, 16, 4);
        assertThat((double[]) DartValues.value(Fact.PADDING, "padding: EdgeInsets.only(top: 8, left: 4)", NONE)).containsExactly(8, 0, 0, 4);
        assertThat(DartValues.value(Fact.PADDING, "padding: EdgeInsets.all(gap * 2)", NONE)).isNull();
    }

    @Test
    void Flutter_저장소_상수는_클래스_이름까지_맞을_때만_푼다() {
        Map<String, String> consts = DartValues.constants(List.of("""
            import 'package:flutter/material.dart';

            const kGap = 8.0;

            class AppColors {
              static const Color primary = Color(0xFF007FFF);
              static const brand = primary;
              static final Color line = Color(0xFFE2E8F0);
            }

            class Other {
              static const Color primary = Color(0xFF000000);
            }
            """));
        assertThat(DartValues.value(Fact.BACKGROUND, "color: AppColors.primary", consts)).isEqualTo("#007fff");
        assertThat(DartValues.value(Fact.BORDER, "border: Border.all(color: AppColors.line)", consts)).isEqualTo("#e2e8f0");
        assertThat(DartValues.value(Fact.GAP, "spacing: kGap", consts)).isEqualTo(8.0);
        assertThat(DartValues.value(Fact.COLOR, "color: Other.primary", consts)).isEqualTo("#000000");
        // 같은 클래스 안의 이름 참조(brand = primary)는 클래스 이름이 없어 풀지 않는다
        assertThat(DartValues.value(Fact.COLOR, "color: AppColors.brand", consts)).isNull();
        assertThat(DartValues.value(Fact.COLOR, "color: AppColors.missing", consts)).isNull();
    }

    @Test
    void React_Native_스타일_객체의_단위_없는_숫자를_읽는다() {
        TailwindValues values = new TailwindValues(List.of());
        String line = "  card: { borderRadius: 12, padding: 16, backgroundColor: '#FFFFFF' },";
        assertThat(values.value(Fact.RADIUS, "borderRadius: 12", "src/screens/Home.tsx", line)).isEqualTo(12.0);
        assertThat((double[]) values.value(Fact.PADDING, "padding: 16", "src/screens/Home.tsx", line)).containsExactly(16, 16, 16, 16);
        assertThat(values.value(Fact.BACKGROUND, "backgroundColor: '#FFFFFF'", "src/screens/Home.tsx", line)).isEqualTo("#ffffff");
        assertThat(values.value(Fact.FONT_WEIGHT, "fontWeight: '600'", "src/screens/Home.tsx", "  title: { fontWeight: '600' },")).isEqualTo(600.0);
        // 한 변만 적은 값은 나머지 변을 몰라 읽지 않는다
        assertThat(values.value(Fact.PADDING, "paddingHorizontal: 16", "src/screens/Home.tsx", "  row: { paddingHorizontal: 16 },")).isNull();
    }

    @Test
    void Flutter_파일의_토큰은_줄에_있을_때만_차이로_남긴다() {
        TailwindValues values = new TailwindValues(List.of());
        Element card = new Element("1:1", "Card", "FRAME", "Card", Map.of(Fact.RADIUS, 12.0, Fact.BACKGROUND, "#ffffff"), 300.0, 60.0, null);
        Target t = new Target("0:1", "홈", "1:1", "Card", "lib/home.dart", "HomeCard");
        Map<String, String> code = Map.of("lib/home.dart", """
            Container(
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(8),
              ),
            )""");
        List<Binding> bindings = List.of(
            new Binding("1:1", "radius", "lib/home.dart", 4, "borderRadius: BorderRadius.circular(8)"),
            new Binding("1:1", "background", "lib/home.dart", 3, "color: Colors.white"),
            new Binding("1:1", "background", "lib/home.dart", 4, "color: Colors.black")); // 그 줄에 없는 토큰 — 버린다
        ClaudeQaComparator.JobResult r = ClaudeQaComparator.judgeWith(t, List.of(card), code, bindings, values);
        assertThat(r.findings()).hasSize(1);
        Finding f = r.findings().get(0);
        assertThat(f.property()).isEqualTo(StyleProperty.RADIUS);
        assertThat(f.expectedValue()).isEqualTo("12px");
        assertThat(f.actualValue()).isEqualTo("8px");
        assertThat(f.codeLine()).isEqualTo(4);
        assertThat(r.dropped()).isEqualTo(1);
    }

    @Test
    void 짝_파일과_저장소_종류로_프롬프트를_고른다() {
        assertThat(ClaudeQaComparator.systemFor("lib/screens/home.dart", false)).isSameAs(ClaudeQaComparator.SYSTEM_FLUTTER);
        assertThat(ClaudeQaComparator.systemFor("src/screens/Home.tsx", true)).isSameAs(ClaudeQaComparator.SYSTEM_RN);
        assertThat(ClaudeQaComparator.systemFor("src/pages/Home.tsx", false)).isSameAs(ClaudeQaComparator.SYSTEM);
    }
}
