package com.acjb.server.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.FigmaSync;

public interface FigmaSyncRepository extends JpaRepository<FigmaSync, Long> {
    /** 다른 프로젝트의 작업은 같은 404로 숨긴다(N10) */
    Optional<FigmaSync> findByIdAndProjectId(Long id, Long projectId);
}
