package com.acjb.server.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.QAIssue;

public interface QAIssueRepository extends JpaRepository<QAIssue, Long> {
    // 목록 API는 정렬 기준을 고정한다(AGENTS.md 5절): created_at desc, 같으면 id desc
    List<QAIssue> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);

    List<QAIssue> findByProjectIdAndStatusOrderByCreatedAtDescIdDesc(Long projectId, IssueStatus status);

    /** GET /qa-issues 목록. status·assigned_to는 없으면 거르지 않는다. 정렬 고정(created_at desc, id desc) */
    @Query("""
        select i from QAIssue i
        where i.project.id = :projectId
          and (:status is null or i.status = :status)
          and (:assignedTo is null or i.assignedTo.id = :assignedTo)
        order by i.createdAt desc, i.id desc
        """)
    List<QAIssue> search(@Param("projectId") Long projectId, @Param("status") IssueStatus status,
                         @Param("assignedTo") Long assignedTo);

    /** 경로의 projectId와 함께 찾아, 다른 프로젝트 이슈가 섞이지 않게 한다(N10) */
    Optional<QAIssue> findByIdAndProjectId(Long id, Long projectId);
}
