package com.acjb.server.qa;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.acjb.server.domain.StyleProperty;

/**
 * 첫 QA(AC23)·Re-QA(AC28)의 "값 비교" 부분. 실행·저장·상태·알림은 FirstQaJob·ReQaJob이 하고, 여기서는 값만 돌려준다.
 * 실제 구현은 ClaudeQaComparator(2026-11-10 스파이크 방식 — Figma 값은 노드 JSON, 코드 값은 LLM이 고른 줄의 토큰을 서버가 변환).
 * 개발용 가짜 비교기(app.qa.fake-comparator=true)는 시연용 값을 만든다
 */
public interface QaComparator {

    /** 개발용 가짜 비교기면 true — 실행에 demo, 이슈 앞에 [시연용]을 남긴다 */
    boolean demo();

    FirstQaResult compare(FirstQaInput input);

    ReQaResult recheck(ReQaInput input);

    /**
     * 연결된 브랜치의 최신 커밋 — Re-QA가 판정할 fixed 이슈 없이 새 범위만 비교할 때 그 커밋을 읽는다(AC28, 2026-11-10).
     * null이면 비교 분석의 커밋을 읽는다(코드를 다시 읽지 않는 가짜 비교기)
     */
    default String latestCommit(String repo, String branch) {
        return null;
    }

    /** 매핑된 노드 하나(최근 성공한 비교 분석의 노드 짝) */
    record Target(String frameId, String frameName, String nodeId, String nodeName, String codePath, String codeSymbol) {
    }

    /** 진행률 — 비교한 Figma 요소 수/전체 요소 수(2026-11-10). 실행 화면이 "N/M개 비교"로 보여 준다 */
    @FunctionalInterface
    interface Progress {
        Progress NONE = (done, total) -> { };

        void update(int done, int total);
    }

    record FirstQaInput(String repo, String commitSha, String figmaFileKey, Set<QaScope> scope, List<StyleProperty> properties,
                        List<Target> targets, Progress progress) {

        public FirstQaInput(String repo, String commitSha, String figmaFileKey, Set<QaScope> scope, List<StyleProperty> properties,
                            List<Target> targets) {
            this(repo, commitSha, figmaFileKey, scope, properties, targets, Progress.NONE);
        }

        /** Figma 파일을 읽지 않는 비교기(가짜 비교기)용 */
        public FirstQaInput(String repo, String commitSha, Set<QaScope> scope, List<StyleProperty> properties, List<Target> targets) {
            this(repo, commitSha, null, scope, properties, targets);
        }

        public FirstQaInput withCommit(String sha) {
            return new FirstQaInput(repo, sha, figmaFileKey, scope, properties, targets, progress);
        }

        public FirstQaInput withProgress(Progress p) {
            return new FirstQaInput(repo, commitSha, figmaFileKey, scope, properties, targets, p == null ? Progress.NONE : p);
        }
    }

    /**
     * 찾은 차이 하나 — Figma 값(expected)과 코드 값(actual), 코드 값을 확인한 파일·줄.
     * nodeId는 입력 Target의 노드, elementId·elementName은 그 노드 안에서 실제로 비교한 하위 요소(노드 자신이면 같은 값)
     */
    record Finding(String nodeId, String elementId, String elementName, StyleProperty property, String expectedValue, String actualValue,
                   String codePath, Integer codeLine) {

        /** 노드 자신을 비교한 차이 */
        public Finding(String nodeId, StyleProperty property, String expectedValue, String actualValue, String codePath, Integer codeLine) {
            this(nodeId, nodeId, null, property, expectedValue, actualValue, codePath, codeLine);
        }
    }

    /** dropped=근거가 모자라 버린 수(N17) */
    record FirstQaResult(String commitSha, List<Finding> findings, int dropped) {
    }

    /** 다시 볼 이슈 하나 — 첫 QA 때의 노드·파일·property·Figma 값. priorReQa=지금까지 Re-QA로 판정된 횟수 */
    record ReQaItem(Long issueId, String frameId, String nodeId, String codePath, Integer codeLine, StyleProperty property,
                    String expectedValue, String previousActualValue, int priorReQa) {
    }

    /** branch=연결된 브랜치 — 실제 비교기는 그 브랜치의 최신 커밋을 다시 읽는다. commitSha는 첫 QA 때 읽은 커밋(참고) */
    record ReQaInput(String repo, String branch, String commitSha, String figmaFileKey, List<ReQaItem> items) {

        /** 코드를 다시 읽지 않는 비교기(가짜 비교기)용 */
        public ReQaInput(String repo, String commitSha, List<ReQaItem> items) {
            this(repo, null, commitSha, null, items);
        }
    }

    /** observed: issueId → 다시 읽은 코드 값. 맵에 없으면 다시 읽지 못한 것(N18 — 판정하지 않는다) */
    record ReQaResult(String commitSha, Map<Long, String> observed) {
    }
}
