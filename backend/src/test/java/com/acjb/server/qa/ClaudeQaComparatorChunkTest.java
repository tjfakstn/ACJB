package com.acjb.server.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.acjb.server.qa.ClaudeQaComparator.Unit;
import com.acjb.server.qa.FigmaFacts.Element;
import com.acjb.server.qa.FigmaFacts.Fact;

/** 첫 QA를 파일 단위로 묶어 묻는 규칙(2026-11-10) — 노드는 가능하면 나누지 않고, 한 번에 묻는 요소 수는 한도를 넘지 않는다 */
class ClaudeQaComparatorChunkTest {

    private static final int MAX = ClaudeQaComparator.MAX_ELEMENTS_PER_CALL;

    private static Unit unit(String nodeId, int elements) {
        List<Element> list = new ArrayList<>();
        for (int i = 0; i < elements; i++) {
            list.add(new Element(nodeId + ":" + i, "e" + i, "TEXT", nodeId, Map.of(Fact.TEXT, "t"), null, null, null));
        }
        return new Unit(new QaComparator.Target("F1", "홈", nodeId, nodeId, "src/A.tsx", null), list);
    }

    @Test
    void 노드를_순서대로_한도까지_채우고_넘치는_노드는_다음_묶음으로() {
        List<List<Unit>> chunks = ClaudeQaComparator.chunk(List.of(unit("A", 20), unit("B", 30), unit("C", 20)));
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).extracting(u -> u.target().nodeId()).containsExactly("A", "B");
        assertThat(chunks.get(1)).extracting(u -> u.target().nodeId()).containsExactly("C");
    }

    @Test
    void 혼자_한도를_넘는_노드만_요소를_나누고_요소는_빠지지_않는다() {
        List<List<Unit>> chunks = ClaudeQaComparator.chunk(List.of(unit("A", 10), unit("B", MAX + 15)));
        assertThat(chunks).allSatisfy(c -> assertThat(c.stream().mapToInt(u -> u.elements().size()).sum()).isLessThanOrEqualTo(MAX));
        assertThat(chunks.stream().flatMap(List::stream).mapToInt(u -> u.elements().size()).sum()).isEqualTo(10 + MAX + 15);
        assertThat(chunks.get(0)).extracting(u -> u.target().nodeId()).containsExactly("A");
        assertThat(chunks.stream().flatMap(List::stream).flatMap(u -> u.elements().stream()).map(Element::id).distinct().count())
            .isEqualTo(10 + MAX + 15);
    }

    @Test
    void 프레임마다_반복되는_같은_컴포넌트는_Figma_id만_달라도_같은_입력으로_본다() {
        Element header1 = new Element("I1:2;3:4", "로고", "TEXT", "Header > 로고", Map.of(Fact.TEXT, "QAting"), 80.0, 24.0, null);
        Element header2 = new Element("I9:8;3:4", "로고", "TEXT", "Header > 로고", Map.of(Fact.TEXT, "QAting"), 80.0, 24.0, null);
        Element otherText = new Element("I9:8;3:5", "로고", "TEXT", "Header > 로고", Map.of(Fact.TEXT, "QA"), 80.0, 24.0, null);
        assertThat(ClaudeQaComparator.sameInput("src/Header.tsx", header1)).isEqualTo(ClaudeQaComparator.sameInput("src/Header.tsx", header2));
        // 값이 다르거나 다른 파일이면 따로 묻는다
        assertThat(ClaudeQaComparator.sameInput("src/Header.tsx", header1)).isNotEqualTo(ClaudeQaComparator.sameInput("src/Header.tsx", otherText));
        assertThat(ClaudeQaComparator.sameInput("src/Header.tsx", header1)).isNotEqualTo(ClaudeQaComparator.sameInput("src/Nav.tsx", header1));
    }

    @Test
    void 빈_목록은_묶음이_없다() {
        assertThat(ClaudeQaComparator.chunk(List.of())).isEmpty();
    }
}
