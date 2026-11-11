package com.acjb.server.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.StyleScan;

public interface StyleScanRepository extends JpaRepository<StyleScan, Long> {

    Optional<StyleScan> findByIdAndProjectId(Long id, Long projectId);

    /** 서버가 다시 시작될 때 끝나지 못한 작업을 찾는다(2026-11-11) */
    java.util.List<StyleScan> findByStatusIn(java.util.Collection<com.acjb.server.domain.JobStatus> statuses);
}
