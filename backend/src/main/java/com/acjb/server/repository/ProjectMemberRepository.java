package com.acjb.server.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
    boolean existsByProjectIdAndMemberId(Long projectId, Long memberId);

    boolean existsByProjectIdAndMemberEmail(Long projectId, String email);

    /** AC15 GET /projects — 이 이메일의 멤버가 속한 프로젝트만, name asc·id asc 고정 */
    @Query("select pm.project from ProjectMember pm where pm.member.email = :email order by pm.project.name asc, pm.project.id asc")
    List<Project> findProjectsOfMemberEmail(@Param("email") String email);

    /** AC15 GET /projects/{projectId}/members — 프로젝트별 role이 필요해 관계 행을 돌려준다. name asc·id asc 고정 */
    @Query("select pm from ProjectMember pm join fetch pm.member m where pm.project.id = :projectId order by m.name asc, m.id asc")
    List<ProjectMember> findMembersOfProject(@Param("projectId") Long projectId);

    Optional<ProjectMember> findByProjectIdAndMemberId(Long projectId, Long memberId);

    long countByProjectId(Long projectId);
}
