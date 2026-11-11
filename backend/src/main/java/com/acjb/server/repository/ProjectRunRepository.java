package com.acjb.server.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;

public interface ProjectRunRepository extends JpaRepository<ProjectRun, Long> {

    /** kind별 최근 실행 — 작업공간의 analysis_status·first_qa_status(AC25) */
    Optional<ProjectRun> findFirstByProjectIdAndKindOrderByCreatedAtDescIdDesc(Long projectId, RunKind kind);

    /** 진행 중(queued·running) 실행이 있는지 */
    boolean existsByProjectIdAndKindAndStatusIn(Long projectId, RunKind kind, java.util.Collection<RunStatus> statuses);

    boolean existsByProjectIdAndKindAndStatus(Long projectId, RunKind kind, RunStatus status);

    /** 목록 카드의 "마지막 분석" — 마지막으로 성공한 실행(AC15) */
    Optional<ProjectRun> findFirstByProjectIdAndKindAndStatusOrderByFinishedAtDescIdDesc(Long projectId, RunKind kind, RunStatus status);

    /** 성공한 첫 QA·Re-QA — 지금까지 검사한 범위(covered_scope, AC28) */
    java.util.List<ProjectRun> findByProjectIdAndKindInAndStatus(Long projectId, java.util.Collection<RunKind> kinds, RunStatus status);

    /** 최근 24시간 실행 수(실패 포함) — 하루 실행 한도(2026-11-11) */
    long countByProjectIdAndKindAndCreatedAtAfter(Long projectId, RunKind kind, java.time.Instant after);

    /** 서버가 다시 시작될 때 끝나지 못한 실행을 찾는다(2026-11-11) */
    java.util.List<ProjectRun> findByStatusIn(java.util.Collection<RunStatus> statuses);
}
