package com.acjb.server.domain;

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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AC12: /projects/{projectId}/** 는 이 관계가 있는 멤버만 호출할 수 있다.
 * role은 이 프로젝트에서의 역할 — 프로젝트를 만들 때(AC16)·초대를 수락할 때(AC18) 본인이 고르고, 이후에는 소유자만 바꾼다(AC24)
 */
@Entity
@Table(name = "project_members",
    uniqueConstraints = @UniqueConstraint(name = "uk_project_members_project_member", columnNames = {"project_id", "member_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    /** null=미지정 — 만든 사람은 역할 없이 시작한다(2026-11-07, V5) */
    @Column(length = 20)
    private MemberRole role;

    @Column(nullable = false, updatable = false)
    private Instant joinedAt;

    /** AC24 — 권한 검사는 서비스에서 한다 */
    public void changeRole(MemberRole role) {
        this.role = role;
    }

    public ProjectMember(Project project, Member member, MemberRole role) {
        this.project = project;
        this.member = member;
        this.role = role;
    }

    @PrePersist
    void onCreate() {
        this.joinedAt = Instant.now();
    }
}
