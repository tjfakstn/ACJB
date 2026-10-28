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

/** 수정 후 재확인 1회. verified_by·verified_at은 요청에서 받지 않고 로그인 사용자와 서버 시각을 쓴다(N8) */
@Entity
@Table(name = "verifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Verification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "qa_issue_id", updatable = false)
    private QAIssue issue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private VerificationResult result;

    private String scope;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "verified_by_id", updatable = false)
    private Member verifiedBy;

    @Column(nullable = false, updatable = false)
    private Instant verifiedAt;

    public Verification(QAIssue issue, VerificationResult result, String scope, Member verifiedBy) {
        this.issue = issue;
        this.result = result;
        this.scope = scope;
        this.verifiedBy = verifiedBy;
    }

    @PrePersist
    void onCreate() {
        this.verifiedAt = Instant.now();
    }
}
