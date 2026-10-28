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

/** AC6: 허용된 모든 상태 변경의 시각·변경자. 수정·삭제하는 경로를 만들지 않는다(SPEC 6.1 N7) */
@Entity
@Table(name = "status_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "qa_issue_id", updatable = false)
    private QAIssue issue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private IssueStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "changed_by_id", updatable = false)
    private Member changedBy;

    @Column(nullable = false, updatable = false)
    private Instant changedAt;

    public StatusHistory(QAIssue issue, IssueStatus status, Member changedBy) {
        this.issue = issue;
        this.status = status;
        this.changedBy = changedBy;
    }

    @PrePersist
    void onCreate() {
        this.changedAt = Instant.now();
    }
}
