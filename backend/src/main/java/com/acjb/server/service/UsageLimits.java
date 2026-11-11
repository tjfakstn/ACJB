package com.acjb.server.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.RunKind;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;

/**
 * 사용량 한도(2026-11-11) — 다른 수업 팀들이 서버 한 대와 팀 공용 AI·Figma·Gmail 계정을 같이 쓰게 되면서,
 * 한 사람·한 프로젝트가 비용과 호출 한도를 다 쓰지 못하게 막는다. 한도를 넘으면 429(RATE_LIMITED).
 * 실행은 DB에 남은 기록(실패 포함)으로 세고, 기록이 남지 않는 AI 호출·초대 메일은 서버 메모리에서 센다(재시작하면 0부터).
 * 값이 0 이하이면 그 한도는 끈다.
 */
@Component
public class UsageLimits {

    private static final Duration DAY = Duration.ofHours(24);

    public enum Counter {
        PARSE("문장 정리"), SUGGESTION("재검증 범위 제안"), INVITATION("초대 메일");

        private final String label;

        Counter(String label) {
            this.label = label;
        }
    }

    private final ProjectRepository projects;
    private final ProjectRunRepository runs;
    private final Clock clock;
    private final int projectsPerOwner;
    private final Map<RunKind, Integer> runsPerDay;
    private final Map<Counter, Integer> callsPerDay;
    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

    public UsageLimits(ProjectRepository projects, ProjectRunRepository runs, Clock clock,
                       @Value("${app.limits.projects-per-owner:3}") int projectsPerOwner,
                       @Value("${app.limits.analysis-per-day:5}") int analysisPerDay,
                       @Value("${app.limits.first-qa-per-day:5}") int firstQaPerDay,
                       @Value("${app.limits.re-qa-per-day:10}") int reQaPerDay,
                       @Value("${app.limits.parse-per-day:100}") int parsePerDay,
                       @Value("${app.limits.suggestions-per-day:50}") int suggestionsPerDay,
                       @Value("${app.limits.invitations-per-day:30}") int invitationsPerDay) {
        this.projects = projects;
        this.runs = runs;
        this.clock = clock;
        this.projectsPerOwner = projectsPerOwner;
        this.runsPerDay = Map.of(RunKind.ANALYSIS, analysisPerDay, RunKind.FIRST_QA, firstQaPerDay, RunKind.RE_QA, reQaPerDay);
        this.callsPerDay = Map.of(Counter.PARSE, parsePerDay, Counter.SUGGESTION, suggestionsPerDay, Counter.INVITATION, invitationsPerDay);
    }

    /** 새 프로젝트를 만들기 전 — 내가 소유한 프로젝트 수 */
    public void checkProjectCreate(Member owner) {
        if (projectsPerOwner > 0 && projects.countByOwnerId(owner.getId()) >= projectsPerOwner) {
            throw limited("프로젝트는 한 사람당 " + projectsPerOwner + "개까지 만들 수 있어요. 쓰지 않는 프로젝트를 삭제한 뒤 다시 만들어 주세요.");
        }
    }

    /** 실행을 저장하기 전 — 이 프로젝트에서 최근 24시간 동안 시작한 같은 종류의 실행 수(실패 포함) */
    public void checkRunStart(Long projectId, RunKind kind) {
        int limit = runsPerDay.getOrDefault(kind, 0);
        if (limit <= 0) {
            return;
        }
        long recent = runs.countByProjectIdAndKindAndCreatedAtAfter(projectId, kind, clock.instant().minus(DAY));
        if (recent >= limit) {
            throw limited(label(kind) + "은 프로젝트마다 하루 " + limit + "번까지 시작할 수 있어요. 내일 다시 시도해 주세요.");
        }
    }

    /** 기록이 남지 않는 호출 — 한도 안이면 1번 센다 */
    public void hit(Counter counter, Long projectId) {
        int limit = callsPerDay.getOrDefault(counter, 0);
        if (limit <= 0) {
            return;
        }
        Instant now = clock.instant();
        Instant since = now.minus(DAY);
        Deque<Instant> window = windows.computeIfAbsent(counter + ":" + projectId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && !window.peekFirst().isAfter(since)) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                throw limited(counter.label + "은 프로젝트마다 하루 " + limit + "번까지 쓸 수 있어요. 내일 다시 시도해 주세요.");
            }
            window.addLast(now);
        }
    }

    private static String label(RunKind kind) {
        return switch (kind) {
            case ANALYSIS -> "비교 분석";
            case FIRST_QA -> "첫 QA";
            case RE_QA -> "Re-QA";
        };
    }

    public static ApiException limited(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED, message);
    }
}
