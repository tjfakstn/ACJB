package com.acjb.server.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.StatusHistory;

public interface StatusHistoryRepository extends JpaRepository<StatusHistory, Long> {
    List<StatusHistory> findByIssueIdOrderByChangedAtAscIdAsc(Long issueId);
}
