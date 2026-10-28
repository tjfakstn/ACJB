package com.acjb.server.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
@Table(name = "qa_issues")
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
    @Column(name = "step", nullable = false, length = 1000)
    private List<String> reproSteps = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlatformScope platformScope;

    @Embedded
    private Environment environment;

    @ElementCollection
    @CollectionTable(name = "qa_issue_screenshots", joinColumns = @JoinColumn(name = "qa_issue_id"))
    @OrderColumn(name = "shot_order")
    @Column(name = "url", nullable = false, length = 2000)
    private List<String> screenshots = new ArrayList<>();

    private String figmaLink;

    @Embedded
    private StyleMismatch styleMismatch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IssueStatus status = IssueStatus.NEEDS_CHECK;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id")
    private Member assignedTo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reported_by_id", updatable = false)
    private Member reportedBy;

    @Column(nullable = false, updatable = false)
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
