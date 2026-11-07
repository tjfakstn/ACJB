package com.acjb.server.service;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.dto.MemberResponse;
import com.acjb.server.dto.ProjectResponse;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;

/** 프로젝트 만들기(AC16)·멤버 제거(AC19). 초대·수락은 InvitationService */
@Service
public class ProjectCommandService {

    /** 이름 규칙(시안 PRJ-02·SPEC AC16): 공백을 뺀 길이 2~40자, 한글·영문·숫자·공백·-·_ */
    private static final int NAME_MIN = 2;
    private static final int NAME_MAX = 40;
    private static final Pattern NAME_CHARS = Pattern.compile("[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9 _-]+");
    private static final int DESCRIPTION_MAX = 200;

    private final ProjectRepository projects;
    private final ProjectMemberRepository projectMembers;
    private final QAIssueRepository issues;

    public ProjectCommandService(ProjectRepository projects, ProjectMemberRepository projectMembers, QAIssueRepository issues) {
        this.projects = projects;
        this.projectMembers = projectMembers;
        this.issues = issues;
    }

    /**
     * AC16 — 요청자가 소유자이자 역할 미지정(null)의 첫 멤버(2026-11-07). 역할은 이후 소유자가 정한다(AC24).
     * 검사를 모두 통과한 뒤에만 저장한다
     */
    @Transactional
    public ProjectResponse create(String name, String description, Member requester) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw ApiException.badRequest("필수 항목이 비어 있습니다: name");
        }
        if (trimmed.length() < NAME_MIN || trimmed.length() > NAME_MAX) {
            throw ApiException.badRequest("name은 " + NAME_MIN + "~" + NAME_MAX + "자여야 합니다.");
        }
        if (!NAME_CHARS.matcher(trimmed).matches()) {
            throw ApiException.badRequest("name에는 한글·영문·숫자·공백·-·_만 쓸 수 있습니다.");
        }
        String desc = description == null || description.isBlank() ? null : description.trim();
        if (desc != null && desc.length() > DESCRIPTION_MAX) {
            throw ApiException.badRequest("description은 " + DESCRIPTION_MAX + "자 이하여야 합니다.");
        }
        Project project = projects.save(new Project(trimmed, desc, requester));
        projectMembers.save(new ProjectMember(project, requester, null));
        return ProjectResponse.of(project);
    }

    /**
     * AC19 — 소유자만, 소유자 자신은 제거 불가. 제거된 멤버가 담당자인 미해결 이슈는 담당자만 비우고
     * 상태·이력·알림·재검증은 그대로 둔다(N15). 프로젝트 멤버 여부(AC12)는 Security 단계에서 확인했다
     */
    @Transactional
    public void removeMember(Long projectId, String memberId, Member requester) {
        Project project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
        if (!project.getOwner().getId().equals(requester.getId())) {
            throw ApiException.forbidden("멤버 제거는 프로젝트 소유자만 할 수 있습니다.");
        }
        Long targetId = parseIdOrNull(memberId);
        if (targetId != null && targetId.equals(project.getOwner().getId())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.OWNER_NOT_REMOVABLE, "소유자는 제거할 수 없습니다.");
        }
        ProjectMember membership = targetId == null ? null : projectMembers.findByProjectIdAndMemberId(projectId, targetId).orElse(null);
        if (membership == null) {
            throw ApiException.notFound("이 프로젝트의 멤버가 아닙니다.");
        }
        for (QAIssue issue : issues.findByProjectIdAndAssignedToIdAndStatusNot(projectId, targetId, IssueStatus.RESOLVED)) {
            issue.assignTo(null);
        }
        projectMembers.delete(membership);
    }

    /**
     * AC24 — 소유자만(본인 역할 포함). 이슈·담당자·알림은 바꾸지 않는다(알림 종류 3개 고정, N5).
     * 검사 순서: 소유자(403) → 역할 값(400) → 대상 멤버(404)
     */
    @Transactional
    public MemberResponse changeRole(Long projectId, String memberId, String role, Member requester) {
        Project project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
        if (!project.getOwner().getId().equals(requester.getId())) {
            throw ApiException.forbidden("역할은 프로젝트 소유자만 바꿀 수 있습니다.");
        }
        MemberRole memberRole = parseRole(role);
        Long targetId = parseIdOrNull(memberId);
        ProjectMember membership = targetId == null ? null : projectMembers.findByProjectIdAndMemberId(projectId, targetId).orElse(null);
        if (membership == null) {
            throw ApiException.notFound("이 프로젝트의 멤버가 아닙니다.");
        }
        membership.changeRole(memberRole);
        return MemberResponse.of(membership);
    }

    static MemberRole parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("필수 항목이 비어 있습니다: role");
        }
        for (MemberRole r : MemberRole.values()) {
            if (r.name().toLowerCase(Locale.ROOT).equals(raw)) {
                return r;
            }
        }
        throw ApiException.badRequest("role 값이 올바르지 않습니다: " + raw + " (designer, frontend, backend, planner)");
    }

    private static Long parseIdOrNull(String raw) {
        try {
            return raw == null ? null : Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
