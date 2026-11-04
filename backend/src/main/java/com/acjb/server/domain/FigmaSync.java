package com.acjb.server.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

/** POST /figma/sync 한 번. 비동기로 처리하므로 요청 시점엔 queued로 저장하고 작업이 상태를 갱신한다 */
@Entity
@Table(name = "figma_syncs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FigmaSync {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", updatable = false)
    private Project project;

    @Column(nullable = false, length = 100, updatable = false)
    private String figmaFileKey;

    /** null이면 파일 전체 프레임을 동기화한다 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<String> frameIds;

    @Enumerated(EnumType.STRING)

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private JobStatus status = JobStatus.QUEUED;

    @Column(nullable = false)
    private int frameCount = 0;

    @Column(length = 500)
    private String errorMessage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_id", updatable = false)
    private Member requestedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant finishedAt;

    public FigmaSync(Project project, String figmaFileKey, List<String> frameIds, Member requestedBy) {
        this.project = project;
        this.figmaFileKey = figmaFileKey;
        this.frameIds = frameIds == null ? null : new ArrayList<>(frameIds);
        this.requestedBy = requestedBy;
    }

    public void start() {
        this.status = JobStatus.RUNNING;
    }

    public void complete(int frameCount) {
        this.status = JobStatus.DONE;
        this.frameCount = frameCount;
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
