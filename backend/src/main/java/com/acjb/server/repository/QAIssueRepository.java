package com.acjb.server.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.QAIssue;

public interface QAIssueRepository extends JpaRepository<QAIssue, Long> {
    // 목록 API는 정렬 기준을 고정한다(AGENTS.md 5절): created_at desc, 같으면 id desc
    List<QAIssue> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);

    List<QAIssue> findByProjectIdAndStatusOrderByCreatedAtDescIdDesc(Long projectId, IssueStatus status);
}
