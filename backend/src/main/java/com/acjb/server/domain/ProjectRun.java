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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 비교 분석(AC22)·첫 QA(AC23) 실행 한 건(V4). kind별 최근 행의 status가 작업공간의 analysis_status·first_qa_status다(AC25).
 * 실행 로직은 스파이크 후 구현한다 — 지금은 상태 조회에만 쓴다
 */
@Entity
@Table(name = "project_runs", indexes = {
    @Index(name = "idx_project_runs_project_kind", columnList = "project_id, kind, created_at DESC, id DESC")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10, updatable = false)
    private RunKind kind;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private RunStatus status = RunStatus.QUEUED;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_id", nullable = false, updatable = false)
    private Member requestedBy;

    @Column(name = "commit_sha", length = 40)
    private String commitSha;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(name = "dropped_count", nullable = false)
    private int droppedCount;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 실행이 끝났을 때(성공·실패) — 실행 로직(AC22·AC23)이 부른다 */
    public void finish(RunStatus result, Instant now) {
        this.status = result;
        this.finishedAt = now;
    }

    public ProjectRun(Project project, RunKind kind, Member requestedBy, Instant now) {
        this.project = project;
        this.kind = kind;
        this.requestedBy = requestedBy;
        this.createdAt = now;
    }
}
