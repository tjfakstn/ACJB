package com.acjb.server.qa;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

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
import com.acjb.server.recheck.EmbeddingSourcesChanged;
import com.acjb.server.repository.DesignCodeMappingRepository;
import com.acjb.server.repository.ProjectRunRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StatusHistoryRepository;
import com.acjb.server.repository.StyleMismatchRepository;

/**
 * 첫 QA(AC23) 실행. 요청 트랜잭션이 커밋된 뒤 별도 스레드에서 돈다.
 * 순서: running 표시 → 최근 성공한 비교 분석의 노드 짝을 모은다 → 비교기 → 근거 검사 → 한 트랜잭션으로 이슈 저장 + succeeded.
 * 입력에 없는 노드, 고른 범위 밖 property, 값이 빈 차이는 버리고 센다(N17). 실패하면 이슈를 하나도 남기지 않는다.
 * 담당 팀이 없으므로 알림은 만들지 않는다(AC3)
 */
@Component
public class FirstQaJob {

    private static final Logger log = LoggerFactory.getLogger(FirstQaJob.class);

    private final ProjectRunRepository runs;
    private final DesignCodeMappingRepository mappings;
    private final QAIssueRepository issues;
    private final StyleMismatchRepository mismatches;
    private final StatusHistoryRepository histories;
    private final QaComparator comparator;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public FirstQaJob(ProjectRunRepository runs, DesignCodeMappingRepository mappings, QAIssueRepository issues,
                      StyleMismatchRepository mismatches, StatusHistoryRepository histories, QaComparator comparator,
                      ApplicationEventPublisher events, PlatformTransactionManager txManager, Clock clock) {
        this.runs = runs;
        this.mappings = mappings;
        this.issues = issues;
        this.mismatches = mismatches;
        this.histories = histories;
        this.comparator = comparator;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(FirstQaRequested event) {
        run(event.runId());
    }

    /** 테스트에서도 직접 부른다. 결과는 run 행의 status·created_count·dropped_count로 남는다 */
    public void run(Long runId) {
        QaComparator.FirstQaInput input;
        try {
            input = tx.execute(s -> {
                ProjectRun run = runs.findById(runId).orElse(null);
                if (run == null) {
                    return null;
                }
                run.start();
                return inputs(run);
            });
        } catch (QaFailedException e) {
            fail(runId, e.getMessage());
            return;
        }
        if (input == null) {
            return;
        }
        try {
            QaComparator.FirstQaResult result = comparator.compare(input);
            boolean demo = comparator.demo();
            tx.executeWithoutResult(s -> save(runId, input, result, demo));
        } catch (QaFailedException e) {
            fail(runId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("첫 QA 중 예상치 못한 오류 run_id={}", runId, e);
            fail(runId, "첫 QA 중 오류가 발생했어요. 다시 시도해 주세요.");
        }
    }

    private QaComparator.FirstQaInput inputs(ProjectRun run) {
        Project p = run.getProject();
        ProjectRun analysis = runs.findFirstByProjectIdAndKindAndStatusOrderByFinishedAtDescIdDesc(p.getId(), RunKind.ANALYSIS,
            RunStatus.SUCCEEDED).orElseThrow(() -> new QaFailedException("성공한 비교 분석이 없어요. 비교 분석을 먼저 마쳐 주세요."));
        Set<QaScope> scope = parseScope(run.getScope());
        // 노드 짝이 있으면 노드 단위로 비교한다. 노드 짝이 하나도 없으면 프레임 짝을 그 프레임 전체로 본다
        Map<String, String> frameNames = new LinkedHashMap<>();
        List<QaComparator.Target> nodes = new ArrayList<>();
        List<QaComparator.Target> frames = new ArrayList<>();
        for (DesignCodeMapping m : mappings.findOrdered(analysis.getId())) {
            frameNames.put(m.getFrameId(), m.getFrameName());
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
        return new QaComparator.FirstQaInput(p.getGithubRepo(), analysis.getCommitSha(), scope, QaScope.propertiesOf(scope), targets);
    }

    private void save(Long runId, QaComparator.FirstQaInput input, QaComparator.FirstQaResult result, boolean demo) {
        ProjectRun run = runs.findById(runId).orElseThrow();
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
            StyleMismatch m = mismatches.save(StyleMismatch.firstQa(project, t.frameId(), t.frameName(), t.nodeId(), f.property(),
                f.expectedValue(), f.actualValue(), f.codePath(), f.codeLine()));
            QAIssue issue = issues.save(QAIssue.firstQa(project, run,
                QaText.description(t.nodeName(), f.property(), f.expectedValue(), f.actualValue(), demo), t.frameName(), t.nodeName(),
                m, run.getRequestedBy()));
            histories.save(new StatusHistory(issue, IssueStatus.NEEDS_CHECK, run.getRequestedBy()));
            created++;
        }
        if (demo) {
            run.markDemo();
        }
        run.succeed(result.commitSha(), created, dropped, clock.instant());
        if (created > 0) {
            events.publishEvent(new EmbeddingSourcesChanged(project.getId()));
        }
    }

    private void fail(Long runId, String message) {
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> runs.findById(runId).ifPresent(r -> r.fail(message, now)));
    }

    static Set<QaScope> parseScope(String csv) {
        Set<QaScope> scope = EnumSet.noneOf(QaScope.class);
        if (csv != null && !csv.isBlank()) {
            Arrays.stream(csv.split(",")).map(v -> QaScope.valueOf(v.trim().toUpperCase(Locale.ROOT))).forEach(scope::add);
        }
        return scope;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
