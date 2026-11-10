package com.acjb.server.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.ScreenFrameLink;

public interface ScreenFrameLinkRepository extends JpaRepository<ScreenFrameLink, Long> {

    /** AC33 고정 정렬 — 경로, 프레임 이름, 프레임 id */
    List<ScreenFrameLink> findByProjectIdOrderByPagePathAscFrameNameAscFrameIdAsc(Long projectId);

    Optional<ScreenFrameLink> findByProjectIdAndFrameId(Long projectId, String frameId);
}
