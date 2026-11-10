package com.acjb.server.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.QaCapture;

public interface QaCaptureRepository extends JpaRepository<QaCapture, Long> {

    /** 다른 프로젝트의 캡처는 없는 것으로 본다(N10) */
    Optional<QaCapture> findByIdAndProjectId(Long id, Long projectId);
}
