package com.acjb.server.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Notification;
import com.acjb.server.domain.NotificationEvent;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.StatusHistory;
import com.acjb.server.dto.QaIssueResponse;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StatusHistoryRepository;

import tools.jackson.databind.JsonNode;

/**
 * PATCH /projects/{projectId}/qa-issues/{issueId} — 상태(fixed)·담당자 변경.
 * 검사 순서: 요청 형태(400) → 이슈 존재(404) → 권한(403, AC13) → 전이(409, AC6) → 변경.
 * 어느 단계에서 거부돼도 아무것도 바꾸지 않는다 — 검사를 모두 끝낸 뒤에만 엔티티를 고친다.
 */
@Service
public class QaIssuePatchService {

    private static final Set<String> ALLOWED_FIELDS = Set.of("status", "assigned_to");
    /** PATCH로 fixed가 될 수 있는 상태(AC6). fixed → resolved/needs_recheck는 재검증 등록으로만 */
    private static final Set<IssueStatus> FIXABLE_FROM = Set.of(IssueStatus.NEEDS_CHECK, IssueStatus.NEEDS_RECHECK);
    private static final String IN_APP = "in_app";

    private final QAIssueRepository issues;
    private final MemberRepository members;
    private final ProjectMemberRepository projectMembers;
    private final StatusHistoryRepository histories;
    private final NotificationRepository notifications;

    public QaIssuePatchService(QAIssueRepository issues, MemberRepository members, ProjectMemberRepository projectMembers,
                               StatusHistoryRepository histories, NotificationRepository notifications) {
        this.issues = issues;
        this.members = members;
        this.projectMembers = projectMembers;
        this.histories = histories;
        this.notifications = notifications;
    }

    @Transactional
    public QaIssueResponse patch(Long projectId, String issueId, JsonNode body, Member actor) {
        // 1) 요청 형태 — status·assigned_to 외 필드는 400(N6, 골든 케이스 ng-005)
        if (body == null || !body.isObject()) {
            throw ApiException.badRequest("요청 본문은 JSON 객체여야 합니다.");
        }
        List<String> unknown = new ArrayList<>();
        body.propertyNames().forEach(name -> {
            if (!ALLOWED_FIELDS.contains(name)) unknown.add(name);
        });
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("수정할 수 없는 필드입니다: " + String.join(", ", unknown) + " (status·assigned_to만 가능)");
        }
        if (body.isEmpty()) {
            throw ApiException.badRequest("변경할 값이 없습니다. status 또는 assigned_to를 보내세요.");
        }
        IssueStatus requestedStatus = body.has("status") ? parseStatus(body.get("status")) : null;
        Long requestedAssigneeId = body.has("assigned_to") ? parseAssignee(projectId, body.get("assigned_to")) : null;

        // 2) 이슈 — 다른 프로젝트 이슈는 404로 숨긴다(N10)
        Long id = parseIdOrNull(issueId);
        QAIssue issue = id == null ? null : issues.findByIdAndProjectId(id, projectId).orElse(null);
        if (issue == null) {
            throw ApiException.notFound("이슈를 찾을 수 없습니다.");
        }

        if (requestedStatus != null) {
            // 3) 권한(AC13) — 상태를 바꾸는 요청은 값과 상관없이 "변경 전" 담당자만. 담당자가 없으면 누구도 불가.
            //    전이 검사보다 먼저 해서, 권한 없는 사람의 잘못된 전이 요청도 409가 아니라 403이다(골든 케이스 au-006)
            Member current = issue.getAssignedTo();
            if (current == null || !current.getId().equals(actor.getId())) {
                throw ApiException.forbidden("상태는 이 이슈의 담당자만 바꿀 수 있습니다.");
            }
            // 4) 전이(AC6) — PATCH는 needs_check/needs_recheck → fixed만. 같은 상태로의 전이(fixed → fixed)도 409(N7)
            if (requestedStatus != IssueStatus.FIXED || !FIXABLE_FROM.contains(issue.getStatus())) {
                throw ApiException.conflict("허용되지 않은 상태 전이입니다: " + lower(issue.getStatus()) + " → " + lower(requestedStatus)
                    + ". PATCH는 needs_check·needs_recheck → fixed만 가능하고, resolved·needs_recheck는 재검증으로만 바뀝니다.");
            }
        }

        // 5) 변경 — 여기까지 오면 거부될 일이 없다
        if (requestedAssigneeId != null && !sameMember(issue.getAssignedTo(), requestedAssigneeId)) {
            Member newAssignee = members.getReferenceById(requestedAssigneeId);
            issue.assignTo(newAssignee);
            // AC3: 새로 지정되거나 바뀐 담당자에게 1건. 같은 담당자 재지정은 알림 없음(N5, 골든 케이스 ng-004)
            notifications.save(new Notification(issue, NotificationEvent.ISSUE_CREATED, newAssignee, IN_APP));
        }
        if (requestedStatus != null) {
            issue.changeStatus(IssueStatus.FIXED);
            histories.save(new StatusHistory(issue, IssueStatus.FIXED, actor));
            // AC4: 등록자에게 1건
            notifications.save(new Notification(issue, NotificationEvent.FIXED, issue.getReportedBy(), IN_APP));
        }
        issues.flush();
        return QaIssueResponse.of(issue, histories.findByIssueIdOrderByChangedAtAscIdAsc(issue.getId()));
    }

    private static IssueStatus parseStatus(JsonNode node) {
        if (node == null || !node.isString()) {
            throw ApiException.badRequest("status는 문자열이어야 합니다.");
        }
        String raw = node.asString();
        for (IssueStatus s : IssueStatus.values()) {
            if (lower(s).equals(raw)) {
                return s;
            }
        }
        throw ApiException.badRequest("status 값이 올바르지 않습니다: " + raw);
    }

    /** 담당자는 지정·변경만 가능하다 — null로 비우면 400. 프로젝트 멤버가 아니면 400(AC13, 골든 케이스 au-007) */
    private Long parseAssignee(Long projectId, JsonNode node) {
        if (node == null || node.isNull()) {
            throw ApiException.badRequest("assigned_to는 비울 수 없습니다. 다른 멤버 id로 변경만 가능합니다.");
        }
        Long memberId = node.isString() ? parseIdOrNull(node.asString()) : null;
        if (memberId == null) {
            throw ApiException.badRequest("assigned_to는 멤버 id 문자열이어야 합니다.");
        }
        if (!projectMembers.existsByProjectIdAndMemberId(projectId, memberId)) {
            throw ApiException.badRequest("assigned_to는 이 프로젝트의 멤버여야 합니다.");
        }
        return memberId;
    }

    private static boolean sameMember(Member member, Long id) {
        return member != null && Objects.equals(member.getId(), id);
    }

    private static Long parseIdOrNull(String raw) {
        try {
            return raw == null ? null : Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String lower(IssueStatus s) {
        return s.name().toLowerCase(Locale.ROOT);
    }
}
