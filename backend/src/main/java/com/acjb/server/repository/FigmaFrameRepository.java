package com.acjb.server.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.FigmaFrame;

public interface FigmaFrameRepository extends JpaRepository<FigmaFrame, Long> {
    // 재동기화 upsert와 scan 요청의 frame_id 검사에 쓴다.
    // 목록(GET /figma/frames)은 openapi에 정렬 기준이 아직 없어 메서드를 만들지 않았다(AGENTS.md 5절)
    Optional<FigmaFrame> findByProjectIdAndFrameId(Long projectId, String frameId);
}
