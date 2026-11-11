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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 받는 사람에게 알릴 일 한 건. 같은 사건에 정확히 1건만 만든다(AC3·AC4·AC11).
 * 실행 실패(RUN_FAILED)는 이슈가 없으므로 이슈 대신 실행(run)을 가리킨다 — 그래서 프로젝트를 직접 갖는다(V18, 2026-11-11)
 */
@Entity
@Table(name = "notifications", indexes = {
    // AC14: 이 프로젝트에서 내 알림 created_at desc, id desc
    @Index(name = "idx_notifications_project_recipient_created", columnList = "project_id, recipient_id, created_at DESC, id DESC")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", updatable = false)
    private Project project;

    /** RUN_FAILED가 아니면 항상 있다 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "qa_issue_id", updatable = false)
    private QAIssue issue;

    /** RUN_FAILED일 때만 있다 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", updatable = false)
    private ProjectRun run;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20, updatable = false)
    private NotificationEvent event;

    /** ISSUE_CREATED일 때만 — 등록 때 생겼는지, 나중에 팀을 지정해서 생겼는지 */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20, updatable = false)
    private NotificationCause cause;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", updatable = false)
    private Member recipient;

    /** 이 알림을 일으킨 사람(등록자·팀 지정자·수정한 사람·재검증한 사람·실행한 사람). 예전 알림은 비어 있다 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", updatable = false)
    private Member actor;

    /** 앱 내 알림이면 "in_app". 이메일·Slack 같은 외부 채널은 아직 연동하지 않았다 */
    @Column(nullable = false, length = 20, updatable = false)
    private String channel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 받는 사람이 읽은 시각. null이면 읽지 않음 */
    @Column(name = "read_at")
    private Instant readAt;

    public Notification(QAIssue issue, NotificationEvent event, Member recipient, String channel) {
        this(issue, event, null, recipient, null, channel);
    }

    public Notification(QAIssue issue, NotificationEvent event, NotificationCause cause, Member recipient, Member actor, String channel) {
        this.project = issue.getProject();
        this.issue = issue;
        this.event = event;
        this.cause = cause;
        this.recipient = recipient;
        this.actor = actor;
        this.channel = channel;
    }

    /** 실행 실패 알림 — 실행을 요청한 사람에게 */
    public static Notification runFailed(ProjectRun run, String channel) {
        Notification n = new Notification();
        n.project = run.getProject();
        n.run = run;
        n.event = NotificationEvent.RUN_FAILED;
        n.recipient = run.getRequestedBy();
        n.actor = run.getRequestedBy();
        n.channel = channel;
        return n;
    }

    /** 처음 읽은 시각만 남긴다 — 다시 읽어도 바꾸지 않는다 */
    public boolean markRead(Instant now) {
        if (readAt != null) {
            return false;
        }
        this.readAt = now;
        return true;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
