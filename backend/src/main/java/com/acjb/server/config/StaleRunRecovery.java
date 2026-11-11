package com.acjb.server.config;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.FigmaSync;
import com.acjb.server.domain.JobStatus;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.domain.StyleScan;
import com.acjb.server.repository.FigmaSyncRepository;
import com.acjb.server.repository.ProjectRunRepository;
import com.acjb.server.repository.StyleScanRepository;
import com.acjb.server.service.TeamNotifier;

/**
 * 서버가 시작될 때 끝나지 못한 실행을 실패로 정리한다(2026-11-11).
 * 비교 분석·첫 QA·Re-QA·scan·Figma 동기화는 서버 메모리 안에서만 돈다. 재배포·재시작으로 끊기면 상태가 queued·running으로 남아
 * 같은 실행을 다시 시작할 수도(409), 프로젝트를 지울 수도 없었다. 서버는 한 대라 시작 시점에 남은 것은 모두 끊긴 실행이다.
 * 끊긴 실행은 실패한 실행과 같이 실행한 사람에게 알린다(AC35)
 */
@Component
public class StaleRunRecovery {

    private static final Logger log = LoggerFactory.getLogger(StaleRunRecovery.class);
    static final String MESSAGE = "서버가 다시 시작돼 중단됐어요. 다시 시도해 주세요.";

    private final ProjectRunRepository runs;
    private final StyleScanRepository scans;
    private final FigmaSyncRepository syncs;
    private final TeamNotifier notifier;
    private final Clock clock;

    public StaleRunRecovery(ProjectRunRepository runs, StyleScanRepository scans, FigmaSyncRepository syncs, TeamNotifier notifier,
                            Clock clock) {
        this.runs = runs;
        this.scans = scans;
        this.syncs = syncs;
        this.notifier = notifier;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recover() {
        Instant now = clock.instant();
        List<ProjectRun> stale = runs.findByStatusIn(List.of(RunStatus.QUEUED, RunStatus.RUNNING));
        for (ProjectRun run : stale) {
            run.fail(MESSAGE, now);
            notifier.notifyRunFailed(run);
        }
        List<StyleScan> staleScans = scans.findByStatusIn(List.of(JobStatus.QUEUED, JobStatus.RUNNING));
        staleScans.forEach(s -> s.fail(MESSAGE));
        List<FigmaSync> staleSyncs = syncs.findByStatusIn(List.of(JobStatus.QUEUED, JobStatus.RUNNING));
        staleSyncs.forEach(s -> s.fail(MESSAGE));
        if (!stale.isEmpty() || !staleScans.isEmpty() || !staleSyncs.isEmpty()) {
            log.warn("재시작으로 끊긴 실행을 실패로 정리했다 runs={} scans={} syncs={}", stale.size(), staleScans.size(), staleSyncs.size());
        }
    }
}
