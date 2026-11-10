package com.acjb.server.domain;

import java.time.Duration;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 프로젝트 초대 한 건(AC17·AC18). 수락 링크 토큰은 SHA-256만 저장한다 — 원문은 초대 메일에만 있다(N14).
 * 만료는 배치 없이 expires_at으로 판정한다: 저장된 status가 PENDING이어도 만료 시각이 지나면 expired로 본다.
 */
@Entity
@Table(name = "invitations", indexes = {
    @Index(name = "uk_invitations_token_hash", columnList = "token_hash", unique = true),
    @Index(name = "idx_invitations_project_status", columnList = "project_id, status, created_at DESC, id DESC"),
    @Index(name = "idx_invitations_email_status", columnList = "email, status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invitation {

    public static final Duration TTL = Duration.ofDays(7);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", updatable = false)
    private Project project;

    /** 소문자로 저장한다. 수락할 때 로그인 이메일과 같아야 한다(AC18) */
    @Column(nullable = false, updatable = false)
    private String email;

    /** 재전송하면 새 토큰의 해시로 바뀐다 — 이전 링크는 더 이상 찾을 수 없다(AC17, 2026-11-07) */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 10)
    private InvitationStatus status = InvitationStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by_id", updatable = false)
    private Member invitedBy;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Instant acceptedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_member_id")
    private Member acceptedMember;

    public Invitation(Project project, String email, String tokenHash, Member invitedBy, Instant now) {
        this.project = project;
        this.email = email;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.createdAt = now;
        this.expiresAt = now.plus(TTL);
    }

    /** 만료 시각을 반영한 상태 — 응답과 수락 판정은 항상 이 값을 쓴다 */
    public InvitationStatus effectiveStatus(Instant now) {
        return status == InvitationStatus.PENDING && !now.isBefore(expiresAt) ? InvitationStatus.EXPIRED : status;
    }

    public boolean isOpen(Instant now) {
        return effectiveStatus(now) == InvitationStatus.PENDING;
    }

    /** AC17 재전송 — 대기 중 초대만(서비스에서 확인). 만료를 지금부터 7일로 늘리고 수락 링크 토큰을 바꾼다 */
    public void renew(String newTokenHash, Instant now) {
        this.tokenHash = newTokenHash;
        this.expiresAt = now.plus(TTL);
    }

    public void cancel() {
        this.status = InvitationStatus.CANCELED;
    }

    public void accept(Member member, Instant now) {
        this.status = InvitationStatus.ACCEPTED;
        this.acceptedMember = member;
        this.acceptedAt = now;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
