package com.acjb.server.qa;

import java.time.Clock;
import java.time.Instant;
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

import com.acjb.server.domain.IssueSource;
import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.NotificationEvent;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.domain.StatusHistory;
import com.acjb.server.domain.StyleMismatch;
import com.acjb.server.domain.Verification;
import com.acjb.server.domain.VerificationResult;
import com.acjb.server.domain.VerificationSource;
import com.acjb.server.recheck.EmbeddingSourcesChanged;
import com.acjb.server.repository.ProjectRunRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StatusHistoryRepository;
import com.acjb.server.repository.VerificationRepository;
import com.acjb.server.service.TeamNotifier;

/**
 * 자동 Re-QA(AC28) 실행. fixed인 첫 QA 이슈마다 같은 노드·같은 파일을 다시 비교해
 * 같으면 pass → resolved, 다르면 fail → needs_recheck(담당 팀 recheck_failed 알림, AC11). verified_by는 실행한 소유자.
 * 다시 읽지 못한 이슈·그사이 상태가 바뀐 이슈는 바꾸지 않고 건너뛴다(N18). 실패하면 아무 이슈도 바꾸지 않는다(한 트랜잭션).
 * 새로 더한 검증 범위(added_scope, 2026-11-10)가 있으면 같은 커밋에서 그 범위만 매핑 전체를 비교해 새 이슈로 저장한다
 */
@Component
public class ReQaJob {

    private static final Logger log = LoggerFactory.getLogger(ReQaJob.class);

    private final ProjectRunRepository runs;
    private final QAIssueRepository issues;
    private final VerificationRepository verifications;
    private final StatusHistoryRepository histories;
    private final TeamNotifier teamNotifier;
    private final QaComparator comparator;
    private final FirstQaSupport support;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReQaJob(ProjectRunRepository runs, QAIssueRepository issues, VerificationRepository verifications,
                   StatusHistoryRepository histories, TeamNotifier teamNotifier, QaComparator comparator, FirstQaSupport support,
                   ApplicationEventPublisher events, PlatformTransactionManager txManager, Clock clock) {
        this.runs = runs;
        this.issues = issues;
        this.verifications = verifications;
        this.histories = histories;
        this.teamNotifier = teamNotifier;
        this.comparator = comparator;
        this.support = support;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(ReQaRequested event) {
        run(event.runId());
    }

    /** 실행 준비 — 판정할 fixed 이슈와, 새로 더한 범위가 있으면 그 비교 입력(커밋은 아직 비교 분석의 것) */
    private record Prepared(QaComparator.ReQaInput recheck, QaComparator.FirstQaInput added) {
    }

    public void run(Long runId) {
        Prepared prepared;
        try {
            prepared = tx.execute(s -> {
                ProjectRun run = runs.findById(runId).orElse(null);
                if (run == null) {
                    return null;
                }
                run.start();
                Set<QaScope> added = FirstQaJob.parseScope(run.getAddedScope());
                return new Prepared(inputs(run), added.isEmpty() ? null : support.input(run.getProject(), added, null));
            });
        } catch (QaFailedException e) {
            fail(runId, e.getMessage());
            return;
        }
        if (prepared == null) {
            return;
        }
        try {
            QaComparator.ReQaInput input = prepared.recheck();
            QaComparator.ReQaResult result = input.items().isEmpty() ? new QaComparator.ReQaResult(null, Map.of())
                : comparator.recheck(input);
            String sha = result.commitSha();
            QaComparator.FirstQaInput addedInput = null;
            QaComparator.FirstQaResult addedResult = null;
            if (prepared.added() != null) {
                // 새 범위는 판정과 같은 커밋(브랜치 최신)에서 비교한다 — 이미 고친 차이가 다시 이슈가 되지 않게
                String head = sha != null ? sha : comparator.latestCommit(input.repo(), input.branch());
                QaComparator.FirstQaInput a = prepared.added();
                addedInput = head == null ? a
                    : new QaComparator.FirstQaInput(a.repo(), head, a.figmaFileKey(), a.scope(), a.properties(), a.targets());
                addedResult = comparator.compare(addedInput);
                if (sha == null) {
                    sha = addedResult.commitSha();
                }
            }
            boolean demo = comparator.demo();
            String commitSha = sha;
            QaComparator.FirstQaInput finalAddedInput = addedInput;
            QaComparator.FirstQaResult finalAddedResult = addedResult;
            tx.executeWithoutResult(s -> save(runId, input, result, commitSha, finalAddedInput, finalAddedResult, demo));
        } catch (QaFailedException e) {
            fail(runId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Re-QA 중 예상치 못한 오류 run_id={}", runId, e);
            fail(runId, "Re-QA 중 오류가 발생했어요. 다시 시도해 주세요.");
        }
    }

    private QaComparator.ReQaInput inputs(ProjectRun run) {
        Project p = run.getProject();
        String sha = runs.findFirstByProjectIdAndKindAndStatusOrderByFinishedAtDescIdDesc(p.getId(), RunKind.ANALYSIS, RunStatus.SUCCEEDED)
            .map(ProjectRun::getCommitSha).orElse(null);
        List<QaComparator.ReQaItem> items = issues.findByProjectIdAndStatusAndSourceOrderByIdAsc(p.getId(), IssueStatus.FIXED,
                IssueSource.FIRST_QA).stream()
            .filter(i -> i.getStyleMismatch() != null)
            .map(i -> {
                StyleMismatch m = i.getStyleMismatch();
                return new QaComparator.ReQaItem(i.getId(), m.getFrameId(), m.getNodeId(), m.getCodePath(), m.getCodeLine(), m.getProperty(),
                    m.getExpectedValue(), m.getActualValue(), (int) verifications.countByIssueIdAndSource(i.getId(), VerificationSource.RE_QA));
            })
            .toList();
        return new QaComparator.ReQaInput(p.getGithubRepo(), p.getGithubBranch(), sha, p.getFigmaFileKey(), items);
    }

    /** 판정과 새 범위의 이슈를 한 트랜잭션으로 — 실패하면 둘 다 남지 않는다 */
    private void save(Long runId, QaComparator.ReQaInput input, QaComparator.ReQaResult result, String commitSha,
                      QaComparator.FirstQaInput addedInput, QaComparator.FirstQaResult addedResult, boolean demo) {
        ProjectRun run = runs.findById(runId).orElseThrow();
        Long projectId = run.getProject().getId();
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (QaComparator.ReQaItem item : input.items()) {
            String observed = result.observed().get(item.issueId());
            QAIssue issue = issues.findForUpdate(item.issueId(), projectId).orElse(null);
            // N18 — 다시 읽지 못했거나, 그사이 사람이 상태를 바꿨으면 판정하지 않는다
            if (observed == null || observed.isBlank() || issue == null || issue.getStatus() != IssueStatus.FIXED) {
                skipped++;
                continue;
            }
            VerificationResult verdict = same(observed, item.expectedValue()) ? VerificationResult.PASS : VerificationResult.FAIL;
            verifications.save(Verification.reQa(issue, verdict, observed, run.getRequestedBy(), run));
            IssueStatus next = verdict == VerificationResult.PASS ? IssueStatus.RESOLVED : IssueStatus.NEEDS_RECHECK;
            issue.changeStatus(next);
            histories.save(new StatusHistory(issue, next, run.getRequestedBy()));
            if (verdict == VerificationResult.FAIL) {
                teamNotifier.notifyTeam(issue, issue.getAssignedTeam(), NotificationEvent.RECHECK_FAILED);
                failed++;
            } else {
                passed++;
            }
        }
        // 새로 더한 범위(AC28, 2026-11-10) — 첫 QA와 같은 근거 규칙(N17)으로 저장. 담당 팀이 없으므로 알림 없음
        FirstQaSupport.Saved found = addedInput == null ? new FirstQaSupport.Saved(0, 0) : support.save(run, addedInput, addedResult, demo);
        if (demo) {
            run.markDemo();
        }
        run.succeedReQa(commitSha, passed, failed, skipped, found.created(), found.dropped(), clock.instant());
        if (failed > 0 || found.created() > 0) {
            // fail 재검증과 새 이슈는 재검증 범위 제안(AC20)의 검색 문서가 된다
            events.publishEvent(new EmbeddingSourcesChanged(projectId));
        }
    }

    /** 공백·대소문자만 다른 값은 같은 값으로 본다(#1a73e8 = #1A73E8) */
    private static boolean same(String a, String b) {
        return a.trim().toLowerCase(Locale.ROOT).equals(b == null ? null : b.trim().toLowerCase(Locale.ROOT));
    }

    private void fail(Long runId, String message) {
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> runs.findById(runId).ifPresent(r -> r.fail(message, now)));
    }
}
