package com.acjb.server.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.acjb.server.domain.IssueSource;
import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.QAIssue;

import jakarta.persistence.LockModeType;

public interface QAIssueRepository extends JpaRepository<QAIssue, Long> {
    // 목록 API는 정렬 기준을 고정한다(AGENTS.md 5절): created_at desc, 같으면 id desc
    List<QAIssue> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);

    List<QAIssue> findByProjectIdAndStatusOrderByCreatedAtDescIdDesc(Long projectId, IssueStatus status);

    /** GET /qa-issues 목록. status·source·assigned_team은 없으면 거르지 않는다. 정렬 고정(created_at desc, id desc) */
    @Query("""
        select i from QAIssue i
        where i.project.id = :projectId
          and (:status is null or i.status = :status)
          and (:source is null or i.source = :source)
          and (:team is null or i.assignedTeam = :team)
        order by i.createdAt desc, i.id desc
        """)
    List<QAIssue> search(@Param("projectId") Long projectId, @Param("status") IssueStatus status,
                         @Param("source") IssueSource source, @Param("team") MemberRole team);

    /** Re-QA 대상 — fixed인 첫 QA 이슈(AC28). 정해진 순서(id asc) */
    List<QAIssue> findByProjectIdAndStatusAndSourceOrderByIdAsc(Long projectId, IssueStatus status, IssueSource source);

    long countByProjectIdAndStatusAndSource(Long projectId, IssueStatus status, IssueSource source);

    /** 작업공간 홈의 상태별 이슈 수(AC25) — [status, count] 행. 없는 상태는 행이 없다 */
    @Query("select i.status, count(i) from QAIssue i where i.project.id = :projectId group by i.status")
    List<Object[]> countByStatus(@Param("projectId") Long projectId);


    /** 경로의 projectId와 함께 찾아, 다른 프로젝트 이슈가 섞이지 않게 한다(N10) */
    Optional<QAIssue> findByIdAndProjectId(Long id, Long projectId);

    /**
     * 상태를 바꾸는 요청(PATCH fixed, 재검증 등록)용 — 이슈 행에 쓰기 잠금(SELECT … FOR UPDATE)을 건다.
     * 같은 이슈에 요청이 동시에 오면 차례로 처리돼, 늦은 쪽은 바뀐 상태를 보고 409를 받는다.
     * 잠금 없이 읽으면 두 요청이 같은 상태를 보고 진행하다 MySQL 데드락으로 500이 났다(2026-11-04 재현)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from QAIssue i where i.id = :id and i.project.id = :projectId")
    Optional<QAIssue> findForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);

    /** 목록 카드(AC15) — 전체 이슈 수와 미해결(resolved가 아닌) 이슈 수 */
    long countByProjectId(Long projectId);

    long countByProjectIdAndStatusNot(Long projectId, IssueStatus status);

    /** 같은 Figma 요소에서 나온 첫 QA 이슈(AC34 코드 추적의 related) — id 오름차순 */
    @org.springframework.data.jpa.repository.Query("select i from QAIssue i join i.styleMismatch m where i.project.id = :projectId "
        + "and i.source = com.acjb.server.domain.IssueSource.FIRST_QA and m.nodeId = :nodeId order by i.id asc")
    List<QAIssue> findFirstQaByElement(@org.springframework.data.repository.query.Param("projectId") Long projectId,
                                       @org.springframework.data.repository.query.Param("nodeId") String nodeId);
}
