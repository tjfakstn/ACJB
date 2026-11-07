package com.acjb.server.service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import com.acjb.server.domain.InvitationStatus;
import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.RunKind;
import com.acjb.server.dto.ProjectDetailResponse;
import com.acjb.server.repository.InvitationRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;
import com.acjb.server.repository.QAIssueRepository;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.NotificationEvent;
import com.acjb.server.dto.ItemsResponse;
import com.acjb.server.dto.MemberResponse;
import com.acjb.server.dto.NotificationListResponse;
import com.acjb.server.dto.NotificationResponse;
import com.acjb.server.dto.ProjectResponse;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;

/** 조회 전용 API — 프로젝트 목록·멤버 목록(AC15), 내 알림(AC14). 아무것도 쓰지 않는다 */
@Service
@Transactional(readOnly = true)
public class ProjectQueryService {

    private final ProjectMemberRepository projectMembers;
    private final NotificationRepository notifications;
    private final ProjectRepository projects;
    private final ProjectRunRepository runs;
    private final InvitationRepository invitations;
    private final QAIssueRepository issues;
    private final Clock clock;

    public ProjectQueryService(ProjectMemberRepository projectMembers, NotificationRepository notifications, ProjectRepository projects,
                               ProjectRunRepository runs, InvitationRepository invitations, QAIssueRepository issues, Clock clock) {
        this.projectMembers = projectMembers;
        this.notifications = notifications;
        this.projects = projects;
        this.runs = runs;
        this.invitations = invitations;
        this.issues = issues;
        this.clock = clock;
    }

    /**
     * AC25 — 작업공간 홈. 프로젝트 멤버 여부는 Security 단계(AC12)에서 확인했다.
     * 실행 상태는 kind별 최근 실행(없으면 none), 이슈 수는 5개 상태 모두(0 포함)
     */
    public ProjectDetailResponse detail(Long projectId, Member me) {
        Project p = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
        ProjectMember mine = projectMembers.findByProjectIdAndMemberId(projectId, me.getId())
            .orElseThrow(() -> ApiException.forbidden("이 프로젝트의 멤버가 아닙니다."));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (IssueStatus s : IssueStatus.values()) {
            counts.put(lower(s), 0L);
        }
        for (Object[] row : issues.countByStatus(projectId)) {
            counts.put(lower((IssueStatus) row[0]), (Long) row[1]);
        }
        ProjectDetailResponse.FigmaConnection figma = p.getFigmaFileKey() == null ? null
            : new ProjectDetailResponse.FigmaConnection(p.getFigmaUrl(), p.getFigmaFileKey(), p.getFigmaFileName(), p.getFigmaConnectedAt());
        ProjectDetailResponse.GithubConnection github = p.getGithubRepo() == null ? null
            : new ProjectDetailResponse.GithubConnection(p.getGithubUrl(), p.getGithubRepo(), p.getGithubDefaultBranch(), p.getGithubConnectedAt());
        return new ProjectDetailResponse(String.valueOf(p.getId()), p.getName(), String.valueOf(p.getOwner().getId()),
            lower(mine.getRole()), figma, github, runStatus(projectId, RunKind.ANALYSIS), runStatus(projectId, RunKind.FIRST_QA),
            projectMembers.countByProjectId(projectId),
            invitations.countByProjectIdAndStatusAndExpiresAtAfter(projectId, InvitationStatus.PENDING, clock.instant()),
            counts);
    }

    private String runStatus(Long projectId, RunKind kind) {
        return runs.findFirstByProjectIdAndKindOrderByCreatedAtDescIdDesc(projectId, kind).map(r -> lower(r.getStatus())).orElse("none");
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    /**
     * AC15 — 로그인한 구글 이메일이 Member인 프로젝트만. Member로 등록되지 않은 이메일이면 빈 목록이다
     * (403이 아니다 — "속한 프로젝트가 없다"는 정상 응답이고, 다른 프로젝트의 존재도 드러내지 않는다)
     */
    public ItemsResponse<ProjectResponse> myProjects(String email) {
        if (email == null || email.isBlank()) {
            return new ItemsResponse<>(List.of());
        }
        return new ItemsResponse<>(projectMembers.findProjectsOfMemberEmail(email).stream().map(ProjectResponse::of).toList());
    }

    /** AC15 — 프로젝트 멤버 여부는 Security 단계(AC12)에서 이미 확인했다 */
    public ItemsResponse<MemberResponse> members(Long projectId) {
        return new ItemsResponse<>(projectMembers.findMembersOfProject(projectId).stream().map(MemberResponse::of).toList());
    }

    /** AC14 — 요청자에게 온 이 프로젝트의 알림만. event는 정해진 3개 값만 받는다 */
    public NotificationListResponse myNotifications(Long projectId, Member me, String event) {
        NotificationEvent filter = event == null ? null : parseEvent(event);
        List<NotificationResponse> items = notifications.findInbox(projectId, me.getId(), filter).stream()
            .map(NotificationResponse::of).toList();
        return new NotificationListResponse(items, items.size());
    }

    private static NotificationEvent parseEvent(String raw) {
        for (NotificationEvent e : NotificationEvent.values()) {
            if (e.name().toLowerCase(Locale.ROOT).equals(raw)) {
                return e;
            }
        }
        throw ApiException.badRequest("event 값이 올바르지 않습니다: " + raw + " (issue_created, fixed, recheck_failed)");
    }
}
