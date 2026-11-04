package com.acjb.server.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
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
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * QA 중 발견되어 등록·전달되는 수정 요청 한 건.
 * 등록 후 repro_steps·screen_location·environment는 바꾸지 않는다(SPEC 6.1 N6) — setter를 두지 않았다.
 * 상태 전이·알림 생성 같은 규칙은 서비스에서 AC6·AC13에 맞춰 구현한다.
 */
@Entity
@Table(name = "qa_issues", indexes = {
    // 목록 고정 정렬(created_at desc, id desc)과 상태·담당자·등록자 필터 — docs/db-schema.md 4절
    @Index(name = "idx_qa_issues_project_created", columnList = "project_id, created_at DESC, id DESC"),
    @Index(name = "idx_qa_issues_project_status", columnList = "project_id, status"),
    @Index(name = "idx_qa_issues_assigned_to", columnList = "assigned_to_id"),
    @Index(name = "idx_qa_issues_reported_by", columnList = "reported_by_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QAIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private String screenLocation;

    @ElementCollection
    @CollectionTable(name = "qa_issue_repro_steps", joinColumns = @JoinColumn(name = "qa_issue_id"))
    @OrderColumn(name = "step_order")
    @Column(name = "step", nullable = false, length = 500)
    private List<String> reproSteps = new ArrayList<>();

    @Enumerated(EnumType.STRING)

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private PlatformScope platformScope;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "platform", column = @Column(name = "env_platform", length = 10)),
        @AttributeOverride(name = "device", column = @Column(name = "env_device", length = 100)),
        @AttributeOverride(name = "browser", column = @Column(name = "env_browser", length = 100)),
        @AttributeOverride(name = "deployStage", column = @Column(name = "env_deploy_stage", length = 10))
    })
    private Environment environment;

    @ElementCollection
    @CollectionTable(name = "qa_issue_screenshots", joinColumns = @JoinColumn(name = "qa_issue_id"))
    @OrderColumn(name = "position")
    @Column(name = "url", nullable = false, length = 1000)
    private List<String> screenshots = new ArrayList<>();

    @Column(length = 500)
    private String figmaLink;

    /** 스타일 차이 이슈일 때만. 사람이 입력한 차이도 style_mismatches 행을 먼저 만들어 연결한다 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "style_mismatch_id", updatable = false)
    private StyleMismatch styleMismatch;

    @Enumerated(EnumType.STRING)

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private IssueStatus status = IssueStatus.NEEDS_CHECK;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id")
    private Member assignedTo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reported_by_id", updatable = false)
    private Member reportedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public QAIssue(Project project, String description, String screenLocation, List<String> reproSteps,
                   PlatformScope platformScope, Environment environment, List<String> screenshots,
                   String figmaLink, StyleMismatch styleMismatch, Member assignedTo, Member reportedBy) {
        this.project = project;
        this.description = description;
        this.screenLocation = screenLocation;
        this.reproSteps = new ArrayList<>(reproSteps);
        this.platformScope = platformScope;
        this.environment = environment;
        this.screenshots = screenshots == null ? new ArrayList<>() : new ArrayList<>(screenshots);
        this.figmaLink = figmaLink;
        this.styleMismatch = styleMismatch;
        this.assignedTo = assignedTo;
        this.reportedBy = reportedBy;
    }

    /** 상태 변경은 서비스에서 전이 규칙(AC6)을 검사한 뒤에만 호출한다 */
    public void changeStatus(IssueStatus next) {
        this.status = next;
    }

    public void assignTo(Member member) {
        this.assignedTo = member;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
