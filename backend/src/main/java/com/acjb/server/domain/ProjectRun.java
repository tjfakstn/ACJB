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
 * 비교 분석은 analysis 패키지, 첫 QA·Re-QA는 qa 패키지가 실행한다
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

    /** re_qa에서 여전히 달라 fail로 판정한 수. 다른 kind는 null */
    @Column(name = "failed_count")
    private Integer failedCount;

    /** first_qa 검증 범위 — 쉼표로 이은 소문자 값(size_spacing 등) */
    @Column(name = "scope", length = 100)
    private String scope;

    /** 개발용 가짜 비교기로 돈 실행(시연용 값, 2026-11-08) */
    /** re_qa에서 새로 더해 비교한 범위(쉼표 구분, 2026-11-10). 다른 kind는 null */
    @Column(name = "added_scope", length = 100)
    private String addedScope;

    /** re_qa가 새로 더한 범위에서 만든 이슈 수·근거가 모자라 버린 수(N17). 다른 kind는 null */
    @Column(name = "found_count")
    private Integer foundCount;

    @Column(name = "found_dropped_count")
    private Integer foundDroppedCount;

    /** analysis에서 코드 파일을 찾지 못한 프레임 — JSON 배열 [{frame_id, frame_name, section}](2026-11-10). 다른 kind는 null */
    @Column(name = "unmapped_frames", columnDefinition = "mediumtext")
    private String unmappedFrames;

    /** 첫 QA·Re-QA 비교 진행률 — 비교한 Figma 요소 수/전체(2026-11-10). 비교를 시작하기 전·다른 kind는 null */
    @Column(name = "progress_done")
    private Integer progressDone;

    @Column(name = "progress_total")
    private Integer progressTotal;

    @Column(name = "demo", nullable = false)
    private boolean demo;

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

    public void start() {
        this.status = RunStatus.RUNNING;
    }

    /** 성공 — 저장한 수·버린 수와 읽은 커밋을 함께 남긴다 */
    public void succeed(String commitSha, int created, int dropped, Instant now) {
        this.commitSha = commitSha;
        this.createdCount = created;
        this.droppedCount = dropped;
        this.errorMessage = null;
        finish(RunStatus.SUCCEEDED, now);
    }

    /** Re-QA 성공 — pass 수(created)·fail 수·건너뛴 수(dropped, N18) */
    public void succeedReQa(String commitSha, int passed, int failed, int skipped, Instant now) {
        succeedReQa(commitSha, passed, failed, skipped, 0, 0, now);
    }

    /** Re-QA 성공 — 새로 더한 범위에서 만든 이슈 수(found)·버린 수(foundDropped)도 함께 */
    public void succeedReQa(String commitSha, int passed, int failed, int skipped, int found, int foundDropped, Instant now) {
        this.failedCount = failed;
        this.foundCount = found;
        this.foundDroppedCount = foundDropped;
        succeed(commitSha, passed, skipped, now);
    }

    /** 진행률은 줄지 않는다 — 동시에 끝난 묶음의 기록 순서가 뒤바뀌어도 */
    public void progress(int done, int total) {
        this.progressTotal = total;
        this.progressDone = progressDone == null ? done : Math.max(progressDone, done);
    }

    public void setUnmappedFrames(String unmappedFrames) {
        this.unmappedFrames = unmappedFrames;
    }

    public void setAddedScope(String addedScope) {
        this.addedScope = addedScope;
    }

    public void markDemo() {
        this.demo = true;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    /** 실패 — 사용자에게 보여 줄 사유만(내부 상세·토큰은 담지 않는다) */
    public void fail(String message, Instant now) {
        this.errorMessage = message == null ? null : message.length() > 500 ? message.substring(0, 500) : message;
        finish(RunStatus.FAILED, now);
    }

    public ProjectRun(Project project, RunKind kind, Member requestedBy, Instant now) {
        this.project = project;
        this.kind = kind;
        this.requestedBy = requestedBy;
        this.createdAt = now;
    }
}
