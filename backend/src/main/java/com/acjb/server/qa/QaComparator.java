package com.acjb.server.qa;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.acjb.server.domain.StyleProperty;

/**
 * 첫 QA(AC23)·Re-QA(AC28)의 "값 비교" 부분. 실행·저장·상태·알림은 FirstQaJob·ReQaJob이 하고, 여기서는 값만 돌려준다.
 * 실제 구현(Figma 노드 값 + LLM이 인용한 코드 줄 확인)은 첫 QA 스파이크 뒤에 만든다.
 * 지금은 개발용 가짜 비교기(app.qa.fake-comparator=true)와, 설정이 없을 때 실패시키는 기본 구현만 있다
 */
public interface QaComparator {

    /** 개발용 가짜 비교기면 true — 실행에 demo, 이슈 앞에 [시연용]을 남긴다 */
    boolean demo();

    FirstQaResult compare(FirstQaInput input);

    ReQaResult recheck(ReQaInput input);

    /** 매핑된 노드 하나(최근 성공한 비교 분석의 노드 짝) */
    record Target(String frameId, String frameName, String nodeId, String nodeName, String codePath, String codeSymbol) {
    }

    record FirstQaInput(String repo, String commitSha, Set<QaScope> scope, List<StyleProperty> properties, List<Target> targets) {
    }

    /** 찾은 차이 하나 — Figma 값(expected)과 코드 값(actual), 코드 값을 확인한 파일·줄 */
    record Finding(String nodeId, StyleProperty property, String expectedValue, String actualValue, String codePath, Integer codeLine) {
    }

    /** dropped=근거가 모자라 버린 수(N17) */
    record FirstQaResult(String commitSha, List<Finding> findings, int dropped) {
    }

    /** 다시 볼 이슈 하나 — 첫 QA 때의 노드·파일·property·Figma 값. priorReQa=지금까지 Re-QA로 판정된 횟수 */
    record ReQaItem(Long issueId, String frameId, String nodeId, String codePath, Integer codeLine, StyleProperty property,
                    String expectedValue, String previousActualValue, int priorReQa) {
    }

    record ReQaInput(String repo, String commitSha, List<ReQaItem> items) {
    }

    /** observed: issueId → 다시 읽은 코드 값. 맵에 없으면 다시 읽지 못한 것(N18 — 판정하지 않는다) */
    record ReQaResult(String commitSha, Map<Long, String> observed) {
    }
}
