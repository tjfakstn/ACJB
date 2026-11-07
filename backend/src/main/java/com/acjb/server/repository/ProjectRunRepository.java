package com.acjb.server.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;

public interface ProjectRunRepository extends JpaRepository<ProjectRun, Long> {

    /** kind별 최근 실행 — 작업공간의 analysis_status·first_qa_status(AC25) */
    Optional<ProjectRun> findFirstByProjectIdAndKindOrderByCreatedAtDescIdDesc(Long projectId, RunKind kind);

    /** 목록 카드의 "마지막 분석" — 마지막으로 성공한 실행(AC15) */
    Optional<ProjectRun> findFirstByProjectIdAndKindAndStatusOrderByFinishedAtDescIdDesc(Long projectId, RunKind kind, RunStatus status);
}
