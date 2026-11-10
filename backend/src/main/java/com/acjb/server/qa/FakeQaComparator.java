package com.acjb.server.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.acjb.server.domain.StyleProperty;

/**
 * 개발용 가짜 비교기(2026-11-08, Claude API 크레딧 충전 전) — app.qa.fake-comparator=true일 때만 쓴다. 운영에서 켜지 않는다.
 * 결과는 정해진 규칙이라 같은 입력이면 항상 같다:
 *  - 첫 QA: 노드 id 해시가 3으로 나누어떨어지는 노드만 "차이 있음". property는 고른 범위 안에서 해시로 고르고, 값은 property별 고정 예시
 *  - Re-QA: 처음 판정할 때는 이슈 id가 홀수면 여전히 다름(fail), 짝수면 고쳐짐(pass). 두 번째부터는 고쳐짐
 */
@Component
@ConditionalOnProperty(name = "app.qa.fake-comparator", havingValue = "true")
public class FakeQaComparator implements QaComparator {

    /** property별 [Figma 값, 코드 값] 예시 */
    private static final Map<StyleProperty, String[]> SAMPLES = Map.of(
        StyleProperty.COLOR, new String[] {"#0072DC", "#1A73E8"},
        StyleProperty.FONT_WEIGHT, new String[] {"700", "600"},
        StyleProperty.FONT_FAMILY, new String[] {"Pretendard", "Noto Sans KR"},
        StyleProperty.RADIUS, new String[] {"8px", "4px"},
        StyleProperty.SHADOW, new String[] {"0 2px 8px rgba(0,0,0,0.12)", "none"},
        StyleProperty.SPACING, new String[] {"padding 12px 24px", "padding 8px 20px"},
        StyleProperty.TEXT, new String[] {"확인", "OK"});

    @Override
    public boolean demo() {
        return true;
    }

    @Override
    public FirstQaResult compare(FirstQaInput input) {
        List<Finding> findings = new ArrayList<>();
        if (!input.properties().isEmpty()) {
            for (Target t : input.targets()) {
                int h = Math.floorMod(t.nodeId().hashCode(), 1_000_003);
                if (h % 3 != 0) {
                    continue;
                }
                StyleProperty p = input.properties().get(h % input.properties().size());
                String[] v = SAMPLES.get(p);
                findings.add(new Finding(t.nodeId(), t.nodeId(), t.nodeName(), p, v[0], v[1], t.codePath(), 1 + h % 120));
            }
        }
        return new FirstQaResult(input.commitSha(), findings, 0);
    }

    @Override
    public ReQaResult recheck(ReQaInput input) {
        Map<Long, String> observed = new LinkedHashMap<>();
        for (ReQaItem item : input.items()) {
            boolean stillWrong = item.priorReQa() == 0 && item.issueId() % 2 == 1;
            observed.put(item.issueId(), stillWrong ? item.previousActualValue() : item.expectedValue());
        }
        return new ReQaResult(input.commitSha(), observed);
    }
}
