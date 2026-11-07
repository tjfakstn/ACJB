package com.acjb.server.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.Invitation;
import com.acjb.server.domain.InvitationStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.dto.InvitationDtos;
import com.acjb.server.dto.ProjectResponse;
import com.acjb.server.invite.InvitationCreated;
import com.acjb.server.invite.InvitationMail;
import com.acjb.server.invite.InvitationTokens;
import com.acjb.server.repository.InvitationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;

/**
 * 초대(AC17)·수락(AC18)·취소·조회. 초대는 멤버 누구나, 취소는 초대한 사람 또는 소유자.
 * 수락은 로그인 이메일이 초대 이메일과 같을 때만, 대기 중이고 만료 전일 때만 된다. 거부되면 아무것도 만들지 않는다(N14).
 */
@Service
public class InvitationService {

    /** 실용적인 이메일 형식 검사 — 실제 존재 여부는 메일·로그인으로 확인된다 */
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final int EMAIL_MAX = 255;

    private final InvitationRepository invitations;
    private final ProjectRepository projects;
    private final ProjectMemberRepository projectMembers;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String acceptUrlTemplate;

    public InvitationService(InvitationRepository invitations, ProjectRepository projects, ProjectMemberRepository projectMembers,
                             ApplicationEventPublisher events, Clock clock,
                             @Value("${app.invite.accept-url:http://localhost:5173/invitations/{token}}") String acceptUrlTemplate) {
        this.invitations = invitations;
        this.projects = projects;
        this.projectMembers = projectMembers;
        this.events = events;
        this.clock = clock;
        this.acceptUrlTemplate = acceptUrlTemplate;
    }

    /** invite 결과 — renewed면 기존 대기 초대를 연장한 것(200), 아니면 새로 만든 것(201) */
    public record InviteResult(InvitationDtos.View view, boolean renewed) {
    }

    /**
     * AC17 — 같은 이메일에 대기 중(만료 전) 초대가 있으면 새로 만들지 않고 그 초대를 연장해 다시 보낸다(2026-11-07).
     * 만료됐지만 상태가 pending으로 남은 행은 취소하고 새로 만든다 — 대기 초대가 이메일당 하나만 남게. 메일은 커밋 뒤 보낸다
     */
    @Transactional
    public InviteResult invite(Long projectId, String rawEmail, Member inviter) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            throw ApiException.badRequest("필수 항목이 비어 있습니다: email");
        }
        if (email.length() > EMAIL_MAX || !EMAIL.matcher(email).matches()) {
            throw ApiException.badRequest("email 형식이 올바르지 않습니다.");
        }
        Project project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
        if (projectMembers.existsByProjectIdAndMemberEmail(projectId, email)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.ALREADY_MEMBER, "이미 이 프로젝트의 멤버입니다.");
        }
        Instant now = clock.instant();
        Invitation open = null;
        for (Invitation pending : invitations.findByProjectIdAndEmailAndStatus(projectId, email, InvitationStatus.PENDING)) {
            if (open == null && pending.isOpen(now)) {
                open = pending;
            } else {
                pending.cancel();
            }
        }
        if (open != null) {
            return new InviteResult(renewAndSend(open, inviter, now), true);
        }
        String token = InvitationTokens.newToken();
        Invitation invitation = invitations.save(new Invitation(project, email, InvitationTokens.hash(token), inviter, now));
        sendMail(invitation, token, inviter);
        return new InviteResult(InvitationDtos.View.of(invitation, now), false);
    }

    /** AC17 재전송 — 프로젝트 멤버 누구나(멤버 여부는 Security 단계, AC12). 대기 중이 아니면 409 */
    @Transactional
    public InvitationDtos.View resend(Long projectId, String invitationId, Member requester) {
        Long id = parseIdOrNull(invitationId);
        Invitation invitation = id == null ? null : invitations.findByIdForUpdate(id).orElse(null);
        if (invitation == null || !invitation.getProject().getId().equals(projectId)) {
            throw ApiException.notFound("초대를 찾을 수 없습니다.");
        }
        Instant now = clock.instant();
        if (!invitation.isOpen(now)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.INVITATION_CLOSED, "대기 중인 초대가 아닙니다.");
        }
        return renewAndSend(invitation, requester, now);
    }

    /** 새 토큰을 발급해 만료를 7일 늘리고 메일을 다시 보낸다. 이전 링크는 무효(N14 — 토큰은 메일에만) */
    private InvitationDtos.View renewAndSend(Invitation invitation, Member sender, Instant now) {
        String token = InvitationTokens.newToken();
        invitation.renew(InvitationTokens.hash(token), now);
        sendMail(invitation, token, sender);
        return InvitationDtos.View.of(invitation, now);
    }

    private void sendMail(Invitation invitation, String token, Member sender) {
        events.publishEvent(new InvitationCreated(new InvitationMail(invitation.getEmail(), invitation.getProject().getName(),
            sender.getName(), acceptUrlTemplate.replace("{token}", token), invitation.getExpiresAt())));
    }

    /** 이 프로젝트의 대기 중(만료 전) 초대 */
    @Transactional(readOnly = true)
    public InvitationDtos.ViewList pending(Long projectId) {
        Instant now = clock.instant();
        return new InvitationDtos.ViewList(invitations.findByProjectIdAndStatusOrderByCreatedAtDescIdDesc(projectId, InvitationStatus.PENDING)
            .stream().filter(i -> i.isOpen(now)).map(i -> InvitationDtos.View.of(i, now)).toList());
    }

    /** 초대한 사람 또는 소유자만. 대기 중이 아니면 409 */
    @Transactional
    public void cancel(Long projectId, String invitationId, Member requester) {
        Long id = parseIdOrNull(invitationId);
        Invitation invitation = id == null ? null : invitations.findByIdForUpdate(id).orElse(null);
        if (invitation == null || !invitation.getProject().getId().equals(projectId)) {
            throw ApiException.notFound("초대를 찾을 수 없습니다.");
        }
        boolean isInviter = invitation.getInvitedBy().getId().equals(requester.getId());
        boolean isOwner = invitation.getProject().getOwner().getId().equals(requester.getId());
        if (!isInviter && !isOwner) {
            throw ApiException.forbidden("초대는 초대한 사람이나 소유자만 취소할 수 있습니다.");
        }
        if (!invitation.isOpen(clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.INVITATION_CLOSED, "대기 중인 초대가 아닙니다.");
        }
        invitation.cancel();
    }

    /** 앱 안 초대함 — 로그인 이메일로 온 대기 중(만료 전) 초대 */
    @Transactional(readOnly = true)
    public InvitationDtos.ViewList mine(String email) {
        Instant now = clock.instant();
        return new InvitationDtos.ViewList(invitations.findByEmailAndStatusOrderByCreatedAtDescIdDesc(email, InvitationStatus.PENDING)
            .stream().filter(i -> i.isOpen(now)).map(i -> InvitationDtos.View.of(i, now)).toList());
    }

    @Transactional(readOnly = true)
    public InvitationDtos.Preview preview(String token) {
        Invitation invitation = token == null ? null : invitations.findByTokenHash(InvitationTokens.hash(token)).orElse(null);
        if (invitation == null) {
            throw ApiException.notFound("초대를 찾을 수 없습니다.");
        }
        return InvitationDtos.Preview.of(invitation, clock.instant());
    }

    /** AC18 — 메일 링크로 수락 */
    @Transactional
    public ProjectResponse acceptByToken(String token, String role, Member me) {
        MemberRole memberRole = ProjectCommandService.parseRole(role);
        Invitation invitation = token == null ? null : invitations.findByTokenHashForUpdate(InvitationTokens.hash(token)).orElse(null);
        if (invitation == null) {
            throw ApiException.notFound("초대를 찾을 수 없습니다.");
        }
        return accept(invitation, memberRole, me);
    }

    /** AC18 — 앱 안 초대함에서 수락. 규칙은 메일 링크와 같다 */
    @Transactional
    public ProjectResponse acceptById(String invitationId, String role, Member me) {
        MemberRole memberRole = ProjectCommandService.parseRole(role);
        Long id = parseIdOrNull(invitationId);
        Invitation invitation = id == null ? null : invitations.findByIdForUpdate(id).orElse(null);
        if (invitation == null) {
            throw ApiException.notFound("초대를 찾을 수 없습니다.");
        }
        return accept(invitation, memberRole, me);
    }

    private ProjectResponse accept(Invitation invitation, MemberRole role, Member me) {
        if (!invitation.getEmail().equals(me.getEmail())) {
            throw ApiException.forbidden("초대받은 구글 계정으로 로그인해야 수락할 수 있습니다.");
        }
        Instant now = clock.instant();
        if (!invitation.isOpen(now)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.INVITATION_CLOSED,
                "수락할 수 없는 초대입니다(" + invitation.effectiveStatus(now).name().toLowerCase(Locale.ROOT) + ").");
        }
        Project project = invitation.getProject();
        if (projectMembers.existsByProjectIdAndMemberId(project.getId(), me.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.ALREADY_MEMBER, "이미 이 프로젝트의 멤버입니다.");
        }
        projectMembers.save(new ProjectMember(project, me, role));
        invitation.accept(me, now);
        return ProjectResponse.of(project);
    }

    private static Long parseIdOrNull(String raw) {
        try {
            return raw == null ? null : Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
