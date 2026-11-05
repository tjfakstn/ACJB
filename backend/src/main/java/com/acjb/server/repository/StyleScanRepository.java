package com.acjb.server.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.StyleScan;

public interface StyleScanRepository extends JpaRepository<StyleScan, Long> {

    Optional<StyleScan> findByIdAndProjectId(Long id, Long projectId);
}
