package com.acjb.server.domain;

import java.time.Instant;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 이슈 등록·조치·재검증 실패를 상대에게 알리는 일 한 건. 같은 사건에 정확히 1건만 만든다(AC3·AC4·AC11) */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "qa_issue_id", updatable = false)
    private QAIssue issue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationEvent event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", updatable = false)
    private Member recipient;

    /** 앱 내 알림이면 "in_app". 이메일·Slack 같은 외부 채널은 아직 연동하지 않았다 */
    @Column(nullable = false, updatable = false)
    private String channel;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public Notification(QAIssue issue, NotificationEvent event, Member recipient, String channel) {
        this.issue = issue;
        this.event = event;
        this.recipient = recipient;
        this.channel = channel;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
