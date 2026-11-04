package com.acjb.server.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.domain.DeployStage;
import com.acjb.server.domain.EnvPlatform;
import com.acjb.server.domain.Environment;
import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Notification;
import com.acjb.server.domain.NotificationEvent;
import com.acjb.server.domain.PlatformScope;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.StatusHistory;
import com.acjb.server.domain.StyleMismatch;
import com.acjb.server.domain.StyleProperty;
import com.acjb.server.dto.CreateIssueFromMismatchRequest;
import com.acjb.server.dto.CreateQaIssueRequest;
import com.acjb.server.dto.CreateQaIssueRequest.EnvironmentInput;
import com.acjb.server.dto.CreateQaIssueRequest.StyleMismatchInput;
import com.acjb.server.dto.QaIssueListResponse;
import com.acjb.server.dto.QaIssueResponse;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StatusHistoryRepository;
import com.acjb.server.repository.StyleMismatchRepository;

/**
 * QA 이슈 등록·조회. 프로젝트 멤버 여부(AC12)는 Security 단계에서 이미 걸렀다.
 * 등록은 검사를 전부 끝낸 뒤에 저장을 시작하고, 한 트랜잭션으로 묶어 일부만 저장되는 일이 없게 한다(N2).
 */
@Service
public class QaIssueService {

    /** 컬럼 길이(docs/db-schema.md). 넘으면 DB 오류(500) 대신 400으로 거부한다 */
    private static final int SCREEN_LOCATION_MAX = 255;
    private static final int REPRO_STEP_MAX = 500;
    private static final int SCREENSHOT_URL_MAX = 1000;
    private static final int FIGMA_LINK_MAX = 500;
    private static final int ENV_TEXT_MAX = 100;
    private static final int MISMATCH_VALUE_MAX = 255;
    private static final int FRAME_ID_MAX = 50;
    private static final int MISMATCH_SCREEN_MAX = 1000;

    private static final String IN_APP = "in_app";

    private final QAIssueRepository issues;
    private final ProjectRepository projects;
    private final MemberRepository members;
    private final ProjectMemberRepository projectMembers;
    private final StyleMismatchRepository mismatches;
    private final StatusHistoryRepository histories;
    private final NotificationRepository notifications;

    public QaIssueService(QAIssueRepository issues, ProjectRepository projects, MemberRepository members,
                          ProjectMemberRepository projectMembers, StyleMismatchRepository mismatches,
                          StatusHistoryRepository histories, NotificationRepository notifications) {
        this.issues = issues;
        this.projects = projects;
        this.members = members;
        this.projectMembers = projectMembers;
        this.mismatches = mismatches;
        this.histories = histories;
        this.notifications = notifications;
    }

    @Transactional
    public QaIssueResponse create(Long projectId, CreateQaIssueRequest req, Member reporter) {
        return createInternal(projectId, req, reporter, null);
    }

    /**
     * POST /style-mismatches/{mismatchId}/issues — 이미 기록된 StyleMismatch를 연결해 이슈를 만든다.
     * 재현 정보는 요청에서 받고(AC1, description도 SPEC AC1대로 필수), 검사·저장 규칙은 일반 등록과 같다.
     * 명세 body에 없는 assigned_to·screenshots·figma_link는 받지 않는다 — 담당자는 등록 후 PATCH로 지정한다.
     */
    @Transactional
    public QaIssueResponse createFromMismatch(Long projectId, String mismatchId, CreateIssueFromMismatchRequest req, Member reporter) {
        CreateQaIssueRequest asIssue = new CreateQaIssueRequest(req.description(), req.screenLocation(), req.reproSteps(),
            req.platformScope(), req.environment(), null, null, null, null);
        Long id = parseIdOrNull(mismatchId);
        if (id == null) {
            throw ApiException.notFound("스타일 차이를 찾을 수 없습니다.");
        }
        return createInternal(projectId, asIssue, reporter, id);
    }

    /** existingMismatchId가 있으면 style_mismatch 입력 대신 그 행을 연결한다(다른 프로젝트 것이면 404, N10) */
    private QaIssueResponse createInternal(Long projectId, CreateQaIssueRequest req, Member reporter, Long existingMismatchId) {
        // AC1: 필수 4개 중 빠진 것을 전부 모아 이름을 돌려준다. 공백만 있는 값도 빈 값이다(N1)
        List<String> missing = new ArrayList<>();
        if (isBlank(req.description())) missing.add("description");
        if (isBlank(req.screenLocation())) missing.add("screen_location");
        if (req.reproSteps() == null || req.reproSteps().isEmpty() || req.reproSteps().stream().anyMatch(QaIssueService::isBlank)) {
            missing.add("repro_steps");
        }
        if (isBlank(req.platformScope())) missing.add("platform_scope");
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("필수 항목이 비어 있습니다: " + String.join(", ", missing));
        }

        PlatformScope platformScope = parseEnum(PlatformScope.class, req.platformScope(), "platform_scope");
        checkMax(req.screenLocation(), SCREEN_LOCATION_MAX, "screen_location");
        req.reproSteps().forEach(step -> checkMax(step, REPRO_STEP_MAX, "repro_steps"));
        List<String> screenshots = validateScreenshots(req.screenshots());
        String figmaLink = blankToNull(req.figmaLink());
        checkMax(figmaLink, FIGMA_LINK_MAX, "figma_link");
        Environment environment = toEnvironment(req.environment());
        StyleMismatchInput mismatchInput = validateMismatch(req.styleMismatch());

        Project project = projects.findById(projectId)
            .orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
        Member assignee = resolveAssignee(projectId, req.assignedTo());

        StyleMismatch existing = existingMismatchId == null ? null
            : mismatches.findByIdAndProjectId(existingMismatchId, projectId)
                .orElseThrow(() -> ApiException.notFound("스타일 차이를 찾을 수 없습니다."));

        // 여기부터 저장. 위 검사를 모두 통과했을 때만 도달한다(N2)
        StyleMismatch mismatch = existing != null ? existing : mismatchInput == null ? null : mismatches.save(StyleMismatch.manual(project,
            blankToNull(mismatchInput.frameId()), blankToNull(mismatchInput.screen()),
            parseEnum(StyleProperty.class, mismatchInput.property(), "style_mismatch.property"),
            mismatchInput.expectedValue(), mismatchInput.actualValue()));

        QAIssue issue = issues.save(new QAIssue(project, req.description(), req.screenLocation(), req.reproSteps(),
            platformScope, environment, screenshots, figmaLink, mismatch, assignee, reporter));
        // 등록 시점의 needs_check도 이력 1건으로 남긴다(db-schema.md status_histories)
        StatusHistory first = histories.save(new StatusHistory(issue, IssueStatus.NEEDS_CHECK, reporter));
        // AC3: 담당자가 있을 때만 정확히 1건
        if (assignee != null) {
            notifications.save(new Notification(issue, NotificationEvent.ISSUE_CREATED, assignee, IN_APP));
        }
        issues.flush();
        return QaIssueResponse.of(issue, List.of(first));
    }

    @Transactional(readOnly = true)
    public QaIssueListResponse list(Long projectId, String status, String assignedTo) {
        IssueStatus statusFilter = status == null ? null : parseEnum(IssueStatus.class, status, "status");
        Long assigneeFilter = assignedTo == null ? null : parseId(assignedTo, "assigned_to");
        List<QaIssueResponse> items = issues.search(projectId, statusFilter, assigneeFilter).stream()
            .map(issue -> QaIssueResponse.of(issue, histories.findByIssueIdOrderByChangedAtAscIdAsc(issue.getId())))
            .toList();
        return new QaIssueListResponse(items, items.size());
    }

    /** 다른 프로젝트의 이슈는 같은 404로 숨긴다(N10, 골든 케이스 ng-010) */
    @Transactional(readOnly = true)
    public QaIssueResponse get(Long projectId, String issueId) {
        Long id = parseIdOrNull(issueId);
        QAIssue issue = (id == null ? null : issues.findByIdAndProjectId(id, projectId).orElse(null));
        if (issue == null) {
            throw ApiException.notFound("이슈를 찾을 수 없습니다.");
        }
        return QaIssueResponse.of(issue, histories.findByIssueIdOrderByChangedAtAscIdAsc(issue.getId()));
    }

    private Member resolveAssignee(Long projectId, String assignedTo) {
        if (assignedTo == null) {
            return null;
        }
        Long memberId = parseId(assignedTo, "assigned_to");
        // AC13: 그 프로젝트 멤버가 아닌 사람을 지정하면 400
        if (!projectMembers.existsByProjectIdAndMemberId(projectId, memberId)) {
            throw ApiException.badRequest("assigned_to는 이 프로젝트의 멤버여야 합니다.");
        }
        return members.getReferenceById(memberId);
    }

    /** AC2: expected_value와 actual_value가 둘 다 있어야 한다. 빠진 값을 추론하거나 채우지 않는다(N3) */
    private StyleMismatchInput validateMismatch(StyleMismatchInput m) {
        if (m == null) {
            return null;
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(m.property())) missing.add("style_mismatch.property");
        if (isBlank(m.expectedValue())) missing.add("style_mismatch.expected_value");
        if (isBlank(m.actualValue())) missing.add("style_mismatch.actual_value");
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("스타일 차이는 기준값과 구현값이 모두 있어야 합니다. 빠진 항목: " + String.join(", ", missing));
        }
        parseEnum(StyleProperty.class, m.property(), "style_mismatch.property");
        checkMax(m.expectedValue(), MISMATCH_VALUE_MAX, "style_mismatch.expected_value");
        checkMax(m.actualValue(), MISMATCH_VALUE_MAX, "style_mismatch.actual_value");
        checkMax(m.frameId(), FRAME_ID_MAX, "style_mismatch.frame_id");
        checkMax(m.screen(), MISMATCH_SCREEN_MAX, "style_mismatch.screen");
        return m;
    }

    private static Environment toEnvironment(EnvironmentInput env) {
        if (env == null) {
            return null;
        }
        EnvPlatform platform = isBlank(env.platform()) ? null : parseEnum(EnvPlatform.class, env.platform(), "environment.platform");
        DeployStage stage = isBlank(env.deployStage()) ? null : parseEnum(DeployStage.class, env.deployStage(), "environment.deploy_stage");
        String device = blankToNull(env.device());
        String browser = blankToNull(env.browser());
        checkMax(device, ENV_TEXT_MAX, "environment.device");
        checkMax(browser, ENV_TEXT_MAX, "environment.browser");
        return new Environment(platform, device, browser, stage);
    }

    private static List<String> validateScreenshots(List<String> screenshots) {
        if (screenshots == null) {
            return List.of();
        }
        for (String url : screenshots) {
            if (isBlank(url)) {
                throw ApiException.badRequest("screenshots에 빈 값이 있습니다.");
            }
            checkMax(url, SCREENSHOT_URL_MAX, "screenshots");
        }
        return screenshots;
    }

    /** 요청 값은 ontology 대표어(소문자)만 받는다. 대문자·별칭은 허용하지 않는다 */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        for (E value : type.getEnumConstants()) {
            if (value.name().toLowerCase(Locale.ROOT).equals(raw)) {
                return value;
            }
        }
        throw ApiException.badRequest(field + " 값이 올바르지 않습니다: " + raw);
    }

    private static Long parseId(String raw, String field) {
        Long id = parseIdOrNull(raw);
        if (id == null) {
            throw ApiException.badRequest(field + " 값이 올바르지 않습니다: " + raw);
        }
        return id;
    }

    private static Long parseIdOrNull(String raw) {
        try {
            return raw == null ? null : Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void checkMax(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw ApiException.badRequest(field + "는 " + max + "자 이하여야 합니다.");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s;
    }
}
