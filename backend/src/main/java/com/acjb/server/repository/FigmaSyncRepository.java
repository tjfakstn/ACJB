package com.acjb.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.FigmaSync;

public interface FigmaSyncRepository extends JpaRepository<FigmaSync, Long> {
}
