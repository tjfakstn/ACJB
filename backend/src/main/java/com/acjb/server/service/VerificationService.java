package com.acjb.server.service;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Notification;
import com.acjb.server.domain.NotificationEvent;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.StatusHistory;
import com.acjb.server.domain.Verification;
import com.acjb.server.domain.VerificationResult;
import com.acjb.server.dto.CreateVerificationRequest;
import com.acjb.server.dto.VerificationCreateResponse;
import com.acjb.server.dto.VerificationListResponse;
import com.acjb.server.dto.VerificationResponse;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StatusHistoryRepository;
import com.acjb.server.repository.VerificationRepository;

/**
 * 재검증 등록·조회. 검사 순서는 PATCH와 같다: 요청 형태(400) → 이슈 존재(404) → 권한(403, AC13) → 전이(409, AC6).
 * 거부되면 Verification·상태·이력·알림 어느 것도 만들거나 바꾸지 않는다.
 */
@Service
public class VerificationService {

    /** verifications.scope 컬럼 길이(docs/db-schema.md) */
    private static final int SCOPE_MAX = 255;
    private static final String IN_APP = "in_app";

    private final QAIssueRepository issues;
    private final VerificationRepository verifications;
    private final StatusHistoryRepository histories;
    private final NotificationRepository notifications;
    private final ProjectMemberRepository projectMembers;

    public VerificationService(QAIssueRepository issues, VerificationRepository verifications,
                               StatusHistoryRepository histories, NotificationRepository notifications,
                               ProjectMemberRepository projectMembers) {
        this.issues = issues;
        this.verifications = verifications;
        this.histories = histories;
        this.notifications = notifications;
        this.projectMembers = projectMembers;
    }

    @Transactional
    public VerificationCreateResponse create(Long projectId, String issueId, CreateVerificationRequest req, Member actor) {
        // 1) 요청 형태
        if (req == null || req.result() == null || req.result().isBlank()) {
            throw ApiException.badRequest("필수 항목이 비어 있습니다: result");
        }
        VerificationResult result = parseResult(req.result());
        String scope = req.scope() == null || req.scope().isBlank() ? null : req.scope();
        if (scope != null && scope.length() > SCOPE_MAX) {
            throw ApiException.badRequest("scope는 " + SCOPE_MAX + "자 이하여야 합니다.");
        }

        // 2) 이슈 — 다른 프로젝트 이슈는 404(N10)
        // 쓰기 잠금으로 읽는다 — 같은 이슈에 재검증이 동시에 와도(버튼 두 번) 하나만 fixed를 보고 진행한다
        QAIssue issue = findIssue(projectId, issueId, true);

        // 3) 권한(AC13) — 재검증은 그 이슈의 등록자만. 전이 검사보다 먼저
        if (!canVerify(issue, actor)) {
            throw ApiException.forbidden("재검증은 이 이슈를 등록한 사람만 할 수 있습니다(등록자가 프로젝트에서 빠졌으면 소유자).");
        }
        // 4) 전이(AC6) — fixed인 이슈만 재검증한다
        if (issue.getStatus() != IssueStatus.FIXED) {
            throw ApiException.conflict("fixed 상태인 이슈만 재검증할 수 있습니다. 현재 상태: "
                + issue.getStatus().name().toLowerCase(Locale.ROOT));
        }

        // 5) 기록 — AC7: pass → resolved, fail → needs_recheck. verified_by·verified_at은 로그인 사용자·서버 시각(N8)
        Verification verification = verifications.save(new Verification(issue, result, scope, actor));
        IssueStatus next = result == VerificationResult.PASS ? IssueStatus.RESOLVED : IssueStatus.NEEDS_RECHECK;
        issue.changeStatus(next);
        histories.save(new StatusHistory(issue, next, actor));
        // AC11: fail이면 담당자에게 1건. 담당자가 없거나 pass면 만들지 않는다
        if (result == VerificationResult.FAIL && issue.getAssignedTo() != null) {
            notifications.save(new Notification(issue, NotificationEvent.RECHECK_FAILED, issue.getAssignedTo(), IN_APP));
        }
        issues.flush();
        return new VerificationCreateResponse(VerificationResponse.of(verification),
            next.name().toLowerCase(Locale.ROOT), List.copyOf(issue.getReproSteps()));
    }

    /**
     * 등록자가 프로젝트에서 제거되면 아무도 재검증할 수 없어 이슈가 fixed에 묶이므로, 그때만 소유자가 대신한다
     * (2026-11-05 결정, SPEC AC13). 등록자가 아직 멤버면 소유자라도 403이다(au-009).
     */
    private boolean canVerify(QAIssue issue, Member actor) {
        Long reporterId = issue.getReportedBy().getId();
        if (reporterId.equals(actor.getId())) {
            return true;
        }
        Long projectId = issue.getProject().getId();
        return issue.getProject().getOwner().getId().equals(actor.getId())
            && !projectMembers.existsByProjectIdAndMemberId(projectId, reporterId);
    }

    /** 최신순(verified_at desc, id desc)으로 고정 */
    @Transactional(readOnly = true)
    public VerificationListResponse list(Long projectId, String issueId) {
        QAIssue issue = findIssue(projectId, issueId, false);
        return new VerificationListResponse(verifications.findByIssueIdOrderByVerifiedAtDescIdDesc(issue.getId()).stream()
            .map(VerificationResponse::of).toList());
    }

    private QAIssue findIssue(Long projectId, String issueId, boolean forUpdate) {
        Long id;
        try {
            id = issueId == null ? null : Long.valueOf(issueId.trim());
        } catch (NumberFormatException e) {
            id = null;
        }
        QAIssue issue = id == null ? null
            : (forUpdate ? issues.findForUpdate(id, projectId) : issues.findByIdAndProjectId(id, projectId)).orElse(null);
        if (issue == null) {
            throw ApiException.notFound("이슈를 찾을 수 없습니다.");
        }
        return issue;
    }

    private static VerificationResult parseResult(String raw) {
        for (VerificationResult r : VerificationResult.values()) {
            if (r.name().toLowerCase(Locale.ROOT).equals(raw)) {
                return r;
            }
        }
        throw ApiException.badRequest("result 값이 올바르지 않습니다: " + raw + " (pass 또는 fail)");
    }
}
