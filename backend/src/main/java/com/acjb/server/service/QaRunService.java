package com.acjb.server.service;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.IssueSource;
import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.dto.ProjectRunView;
import com.acjb.server.qa.FirstQaRequested;
import com.acjb.server.qa.QaScope;
import com.acjb.server.qa.ReQaRequested;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;
import com.acjb.server.repository.QAIssueRepository;

import tools.jackson.databind.JsonNode;

/**
 * 첫 QA(AC23)·자동 Re-QA(AC28) 시작·상태. 검사 순서: 소유자(403) → 요청 형태(400) → 준비 상태(409 NOT_READY).
 * 실제 비교는 커밋 뒤 FirstQaJob·ReQaJob이 한다. 첫 QA와 Re-QA는 같은 이슈를 다루므로 서로 겹쳐 돌지 않는다
 */
@Service
public class QaRunService {

    private static final List<RunStatus> ACTIVE = List.of(RunStatus.QUEUED, RunStatus.RUNNING);

    private final ProjectRepository projects;
    private final ProjectRunRepository runs;
    private final QAIssueRepository issues;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public QaRunService(ProjectRepository projects, ProjectRunRepository runs, QAIssueRepository issues,
                        ApplicationEventPublisher events, Clock clock) {
        this.projects = projects;
        this.runs = runs;
        this.issues = issues;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public ProjectRunView startFirstQa(Long projectId, JsonNode body, Member requester) {
        Project project = ownedProject(projectId, requester, "첫 QA는 프로젝트 소유자만 실행할 수 있어요.");
        Set<QaScope> scope = parseScope(body);
        if (!runs.existsByProjectIdAndKindAndStatus(projectId, RunKind.ANALYSIS, RunStatus.SUCCEEDED)) {
            throw notReady("비교 분석을 먼저 마쳐야 첫 QA를 실행할 수 있어요.");
        }
        if (runs.existsByProjectIdAndKindAndStatus(projectId, RunKind.FIRST_QA, RunStatus.SUCCEEDED)) {
            throw notReady("첫 QA는 이미 끝났어요. 이후에는 Re-QA로 다시 확인해요.");
        }
        checkIdle(projectId);
        ProjectRun run = new ProjectRun(project, RunKind.FIRST_QA, requester, clock.instant());
        run.setScope(scope.stream().map(QaScope::value).collect(Collectors.joining(",")));
        run = runs.save(run);
        events.publishEvent(new FirstQaRequested(run.getId()));
        return ProjectRunView.of(run);
    }

    @Transactional
    public ProjectRunView startReQa(Long projectId, Member requester) {
        Project project = ownedProject(projectId, requester, "Re-QA는 프로젝트 소유자만 실행할 수 있어요.");
        checkIdle(projectId);
        if (issues.countByProjectIdAndStatusAndSource(projectId, IssueStatus.FIXED, IssueSource.FIRST_QA) == 0) {
            throw notReady("수정 완료된 첫 QA 문제가 없어요. 담당 팀이 수정 완료로 바꾼 뒤에 실행할 수 있어요.");
        }
        ProjectRun run = runs.save(new ProjectRun(project, RunKind.RE_QA, requester, clock.instant()));
        events.publishEvent(new ReQaRequested(run.getId()));
        return ProjectRunView.of(run);
    }

    @Transactional(readOnly = true)
    public ProjectRunView latest(Long projectId, RunKind kind) {
        return runs.findFirstByProjectIdAndKindOrderByCreatedAtDescIdDesc(projectId, kind).map(ProjectRunView::of).orElse(null);
    }

    private Project ownedProject(Long projectId, Member requester, String forbidden) {
        // 같은 프로젝트의 시작 요청이 동시에 와도 하나만 만들도록 잠근다
        Project project = projects.findByIdForUpdate(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
        if (!project.getOwner().getId().equals(requester.getId())) {
            throw ApiException.forbidden(forbidden);
        }
        return project;
    }

    private void checkIdle(Long projectId) {
        if (runs.existsByProjectIdAndKindAndStatusIn(projectId, RunKind.FIRST_QA, ACTIVE)
            || runs.existsByProjectIdAndKindAndStatusIn(projectId, RunKind.RE_QA, ACTIVE)) {
            throw notReady("첫 QA나 Re-QA가 이미 진행 중이에요.");
        }
    }

    /** scope는 1개 이상, 정해진 4개 값만(AC23·2026-11-10, 골든 케이스 fq-005) */
    private static Set<QaScope> parseScope(JsonNode body) {
        JsonNode node = body == null ? null : body.get("scope");
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw ApiException.badRequest("검증 범위(scope)를 하나 이상 골라 주세요.");
        }
        Set<QaScope> scope = EnumSet.noneOf(QaScope.class);
        for (JsonNode v : node) {
            QaScope match = null;
            if (v.isString()) {
                for (QaScope s : QaScope.values()) {
                    if (s.value().equals(v.asString().toLowerCase(Locale.ROOT))) {
                        match = s;
                    }
                }
            }
            if (match == null) {
                throw ApiException.badRequest("검증 범위는 size_spacing·color_typography·border_radius·text 중에서 골라 주세요.");
            }
            scope.add(match);
        }
        return scope;
    }

    private static ApiException notReady(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.NOT_READY, message);
    }
}
