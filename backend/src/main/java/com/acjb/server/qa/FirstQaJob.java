package com.acjb.server.qa;

import java.time.Clock;
import java.time.Instant;
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

import com.acjb.server.domain.ProjectRun;
import com.acjb.server.recheck.EmbeddingSourcesChanged;
import com.acjb.server.repository.ProjectRunRepository;

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
    private final FirstQaSupport support;
    private final QaComparator comparator;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public FirstQaJob(ProjectRunRepository runs, FirstQaSupport support, QaComparator comparator,
                      ApplicationEventPublisher events, PlatformTransactionManager txManager, Clock clock) {
        this.runs = runs;
        this.support = support;
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
        return support.input(run.getProject(), parseScope(run.getScope()), null);
    }

    private void save(Long runId, QaComparator.FirstQaInput input, QaComparator.FirstQaResult result, boolean demo) {
        ProjectRun run = runs.findById(runId).orElseThrow();
        FirstQaSupport.Saved saved = support.save(run, input, result, demo);
        if (demo) {
            run.markDemo();
        }
        run.succeed(result.commitSha(), saved.created(), saved.dropped(), clock.instant());
        if (saved.created() > 0) {
            events.publishEvent(new EmbeddingSourcesChanged(run.getProject().getId()));
        }
    }

    private void fail(Long runId, String message) {
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> runs.findById(runId).ifPresent(r -> r.fail(message, now)));
    }

    static Set<QaScope> parseScope(String csv) {
        return QaScope.parseCsv(csv);
    }
}
