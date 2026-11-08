package com.acjb.server.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.acjb.server.domain.StyleProperty;

/** 개발용 가짜 비교기(2026-11-08) — 같은 입력이면 같은 결과, 고른 범위 안의 property만, Re-QA는 해결·재실패 두 경로를 다 낸다 */
class FakeQaComparatorTest {

    private final FakeQaComparator fake = new FakeQaComparator();

    @Test
    void 첫_QA는_정해진_노드만_차이로_내고_고른_범위의_property만_쓴다() {
        List<QaComparator.Target> targets = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            targets.add(new QaComparator.Target("F", "홈", "1:" + i, "노드" + i, "src/A.tsx", null));
        }
        var scope = EnumSet.of(QaScope.BORDER_RADIUS);
        var input = new QaComparator.FirstQaInput("o/r", "sha", scope, QaScope.propertiesOf(scope), targets);

        var first = fake.compare(input);
        var second = fake.compare(input);
        assertThat(first.findings()).isNotEmpty().hasSizeLessThan(30).isEqualTo(second.findings());
        assertThat(first.findings()).allSatisfy(f -> {
            assertThat(f.property()).isIn(StyleProperty.RADIUS, StyleProperty.SHADOW);
            assertThat(f.expectedValue()).isNotEqualTo(f.actualValue());
            assertThat(f.codePath()).isEqualTo("src/A.tsx");
        });
        assertThat(fake.demo()).isTrue();
    }

    @Test
    void Re_QA는_처음엔_홀수_id만_여전히_다르고_두_번째부터는_고쳐진다() {
        var odd = new QaComparator.ReQaItem(7L, "F", "1:1", "src/A.tsx", 1, StyleProperty.COLOR, "#111", "#222", 0);
        var even = new QaComparator.ReQaItem(8L, "F", "1:2", "src/A.tsx", 1, StyleProperty.COLOR, "#111", "#222", 0);
        var oddAgain = new QaComparator.ReQaItem(7L, "F", "1:1", "src/A.tsx", 1, StyleProperty.COLOR, "#111", "#222", 1);

        var result = fake.recheck(new QaComparator.ReQaInput("o/r", "sha", List.of(odd, even)));
        assertThat(result.observed()).containsEntry(7L, "#222").containsEntry(8L, "#111");
        assertThat(fake.recheck(new QaComparator.ReQaInput("o/r", "sha", List.of(oddAgain))).observed()).containsEntry(7L, "#111");
    }
}
