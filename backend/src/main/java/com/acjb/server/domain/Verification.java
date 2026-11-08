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

/** 수정 후 재확인 1회. verified_by·verified_at은 요청에서 받지 않고 로그인 사용자와 서버 시각을 쓴다(N8) */
@Entity
@Table(name = "verifications", indexes = {
    @Index(name = "idx_verifications_issue_verified", columnList = "qa_issue_id, verified_at DESC, id DESC")
})
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

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10, updatable = false)
    private VerificationResult result;

    private String scope;

    /** manual=등록자가 직접, re_qa=자동 Re-QA(AC28) */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10, updatable = false)
    private VerificationSource source = VerificationSource.MANUAL;

    /** Re-QA가 다시 읽은 구현 값(시안 RQA-03 수정 후). manual이면 null */
    @Column(name = "observed_value", updatable = false)
    private String observedValue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "re_qa_run_id", updatable = false)
    private ProjectRun reQaRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "verified_by_id", updatable = false)
    private Member verifiedBy;

    @Column(name = "verified_at", nullable = false, updatable = false)
    private Instant verifiedAt;

    public Verification(QAIssue issue, VerificationResult result, String scope, Member verifiedBy) {
        this.issue = issue;
        this.result = result;
        this.scope = scope;
        this.verifiedBy = verifiedBy;
    }

    /** 자동 Re-QA 판정 — verified_by는 실행한 소유자(AC28) */
    public static Verification reQa(QAIssue issue, VerificationResult result, String observedValue, Member requester, ProjectRun run) {
        Verification v = new Verification(issue, result, null, requester);
        v.source = VerificationSource.RE_QA;
        v.observedValue = observedValue;
        v.reQaRun = run;
        return v;
    }

    @PrePersist
    void onCreate() {
        this.verifiedAt = Instant.now();
    }
}
