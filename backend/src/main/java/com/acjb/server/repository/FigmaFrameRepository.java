package com.acjb.server.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.FigmaFrame;

public interface FigmaFrameRepository extends JpaRepository<FigmaFrame, Long> {
    // 재동기화 upsert와 scan 요청의 frame_id 검사에 쓴다
    Optional<FigmaFrame> findByProjectIdAndFrameId(Long projectId, String frameId);

    /** GET /figma/frames — 정렬 name asc·id asc 고정(2026-11-04 설만수 결정, 프로젝트·멤버 목록과 같은 규칙) */
    List<FigmaFrame> findByProjectIdOrderByNameAscIdAsc(Long projectId);
}
