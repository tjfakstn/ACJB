package com.acjb.server.qa;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

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
 * 다시 읽지 못한 이슈·그사이 상태가 바뀐 이슈는 바꾸지 않고 건너뛴다(N18). 실패하면 아무 이슈도 바꾸지 않는다(한 트랜잭션)
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
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReQaJob(ProjectRunRepository runs, QAIssueRepository issues, VerificationRepository verifications,
                   StatusHistoryRepository histories, TeamNotifier teamNotifier, QaComparator comparator,
                   ApplicationEventPublisher events, PlatformTransactionManager txManager, Clock clock) {
        this.runs = runs;
        this.issues = issues;
        this.verifications = verifications;
        this.histories = histories;
        this.teamNotifier = teamNotifier;
        this.comparator = comparator;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(ReQaRequested event) {
        run(event.runId());
    }

    public void run(Long runId) {
        QaComparator.ReQaInput input = tx.execute(s -> {
            ProjectRun run = runs.findById(runId).orElse(null);
            if (run == null) {
                return null;
            }
            run.start();
            return inputs(run);
        });
        if (input == null) {
            return;
        }
        try {
            QaComparator.ReQaResult result = comparator.recheck(input);
            boolean demo = comparator.demo();
            tx.executeWithoutResult(s -> save(runId, input, result, demo));
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

    private void save(Long runId, QaComparator.ReQaInput input, QaComparator.ReQaResult result, boolean demo) {
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
        if (demo) {
            run.markDemo();
        }
        run.succeedReQa(result.commitSha(), passed, failed, skipped, clock.instant());
        if (failed > 0) {
            // fail 재검증은 재검증 범위 제안(AC20)의 검색 문서가 된다
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
