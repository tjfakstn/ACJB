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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * POST /style-mismatches/scan 한 번. Figma 노드(node_id)와 배포 화면(target_url의 selector 요소)을 비교한다.
 * 처리 범위는 웹·단일 컴포넌트·color/font_weight/radius뿐이다(SPEC 4절, N4) — 범위 검사는 서비스에서 한다.
 */
@Entity
@Table(name = "style_scans")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StyleScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", updatable = false)
    private Project project;

    /** 동기화된 프레임이어야 하지만 FK는 걸지 않는다 — 검사는 서비스에서 한다(db-schema.md) */
    @Column(name = "frame_id", nullable = false, length = 50, updatable = false)
    private String frameId;

    @Column(nullable = false, length = 50, updatable = false)
    private String nodeId;

    @Column(nullable = false, length = 1000, updatable = false)
    private String targetUrl;

    @Column(nullable = false, length = 500, updatable = false)
    private String selector;

    @Enumerated(EnumType.STRING)

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private JobStatus status = JobStatus.QUEUED;

    @Column(nullable = false)
    private int mismatchCount = 0;

    @Column(length = 500)
    private String errorMessage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_id", updatable = false)
    private Member requestedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant finishedAt;

    public StyleScan(Project project, String frameId, String nodeId, String targetUrl, String selector, Member requestedBy) {
        this.project = project;
        this.frameId = frameId;
        this.nodeId = nodeId;
        this.targetUrl = targetUrl;
        this.selector = selector;
        this.requestedBy = requestedBy;
    }

    public void start() {
        this.status = JobStatus.RUNNING;
    }

    public void complete(int mismatchCount) {
        this.status = JobStatus.DONE;
        this.mismatchCount = mismatchCount;
        this.finishedAt = Instant.now();
    }

    public void fail(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.finishedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
