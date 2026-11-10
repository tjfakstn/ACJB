package com.acjb.server.qa;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.acjb.server.domain.DesignCodeMapping;
import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.domain.StatusHistory;
import com.acjb.server.domain.StyleMismatch;
import com.acjb.server.domain.StyleProperty;
import com.acjb.server.repository.DesignCodeMappingRepository;
import com.acjb.server.repository.ProjectRunRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StatusHistoryRepository;
import com.acjb.server.repository.StyleMismatchRepository;

/**
 * 매핑 전체를 고른 범위로 비교해 이슈로 저장하는 부분 — 첫 QA(AC23)와 Re-QA가 새로 더한 범위(AC28, 2026-11-10)가 같이 쓴다.
 * 근거 규칙(N17)이 두 곳에서 갈라지지 않게 한 곳에 둔다. 호출하는 쪽의 트랜잭션 안에서 돈다
 */
@Component
class FirstQaSupport {

    private final ProjectRunRepository runs;
    private final DesignCodeMappingRepository mappings;
    private final QAIssueRepository issues;
    private final StyleMismatchRepository mismatches;
    private final StatusHistoryRepository histories;

    FirstQaSupport(ProjectRunRepository runs, DesignCodeMappingRepository mappings, QAIssueRepository issues,
                   StyleMismatchRepository mismatches, StatusHistoryRepository histories) {
        this.runs = runs;
        this.mappings = mappings;
        this.issues = issues;
        this.mismatches = mismatches;
        this.histories = histories;
    }

    /** 만든 수·버린 수 */
    record Saved(int created, int dropped) {
    }

    /**
     * 최근 성공한 비교 분석의 노드 짝을 비교 입력으로 만든다. commitSha가 null이면 그 분석의 커밋을 읽는다(첫 QA),
     * Re-QA는 자기가 읽은 브랜치 최신 커밋을 넘긴다
     */
    QaComparator.FirstQaInput input(Project p, Set<QaScope> scope, String commitSha) {
        ProjectRun analysis = runs.findFirstByProjectIdAndKindAndStatusOrderByFinishedAtDescIdDesc(p.getId(), RunKind.ANALYSIS,
            RunStatus.SUCCEEDED).orElseThrow(() -> new QaFailedException("성공한 비교 분석이 없어요. 비교 분석을 먼저 마쳐 주세요."));
        // 노드 짝이 있으면 노드 단위로 비교한다. 노드 짝이 하나도 없으면 프레임 짝을 그 프레임 전체로 본다
        List<QaComparator.Target> nodes = new ArrayList<>();
        List<QaComparator.Target> frames = new ArrayList<>();
        for (DesignCodeMapping m : mappings.findOrdered(analysis.getId())) {
            if (m.getNodeId() == null) {
                frames.add(new QaComparator.Target(m.getFrameId(), m.getFrameName(), m.getFrameId(), m.getFrameName(), m.getCodePath(), null));
            } else {
                nodes.add(new QaComparator.Target(m.getFrameId(), m.getFrameName(), m.getNodeId(), m.getNodeName(), m.getCodePath(),
                    m.getCodeSymbol()));
            }
        }
        List<QaComparator.Target> targets = nodes.isEmpty() ? frames : nodes;
        if (targets.isEmpty()) {
            throw new QaFailedException("비교할 디자인-코드 매핑이 없어요. 연결한 Figma 페이지·섹션과 GitHub 브랜치를 확인해 주세요.");
        }
        return new QaComparator.FirstQaInput(p.getGithubRepo(), commitSha != null ? commitSha : analysis.getCommitSha(), p.getFigmaFileKey(),
            scope, QaScope.propertiesOf(scope), targets);
    }

    /** 근거가 둘 다 있는 차이만 이슈로 저장한다. 이슈의 실행은 run(첫 QA 또는 범위를 더한 Re-QA) */
    Saved save(ProjectRun run, QaComparator.FirstQaInput input, QaComparator.FirstQaResult result, boolean demo) {
        Project project = run.getProject();
        Map<String, QaComparator.Target> byNode = new LinkedHashMap<>();
        input.targets().forEach(t -> byNode.put(t.nodeId(), t));
        Set<StyleProperty> allowed = EnumSet.noneOf(StyleProperty.class);
        allowed.addAll(input.properties());
        int created = 0;
        int dropped = result.dropped();
        for (QaComparator.Finding f : result.findings()) {
            QaComparator.Target t = byNode.get(f.nodeId());
            // N17 — 입력에 없는 노드, 고른 범위 밖 property, 값이 하나라도 빈 차이, 같은 값은 버린다
            if (t == null || f.property() == null || !allowed.contains(f.property()) || isBlank(f.expectedValue())
                || isBlank(f.actualValue()) || isBlank(f.codePath()) || f.expectedValue().equals(f.actualValue())) {
                dropped++;
                continue;
            }
            // 비교한 하위 요소가 노드 자신이 아니면 "노드 > 요소"로 적는다 — 어느 요소인지 다시 찾지 않게
            String elementId = isBlank(f.elementId()) ? t.nodeId() : f.elementId();
            String where = isBlank(f.elementName()) || elementId.equals(t.nodeId()) ? t.nodeName() : t.nodeName() + " > " + f.elementName();
            StyleMismatch m = mismatches.save(StyleMismatch.firstQa(project, t.frameId(), t.frameName(), elementId, f.property(),
                f.expectedValue(), f.actualValue(), f.codePath(), f.codeLine()));
            QAIssue issue = issues.save(QAIssue.firstQa(project, run,
                QaText.description(where, f.property(), f.expectedValue(), f.actualValue(), demo), t.frameName(), where,
                m, run.getRequestedBy()));
            histories.save(new StatusHistory(issue, IssueStatus.NEEDS_CHECK, run.getRequestedBy()));
            created++;
        }
        return new Saved(created, dropped);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
