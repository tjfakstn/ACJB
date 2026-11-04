package com.acjb.server.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.acjb.server.domain.Member;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
    boolean existsByProjectIdAndMemberId(Long projectId, Long memberId);

    boolean existsByProjectIdAndMemberEmail(Long projectId, String email);

    /** AC15 GET /projects — 이 이메일의 멤버가 속한 프로젝트만, name asc·id asc 고정 */
    @Query("select pm.project from ProjectMember pm where pm.member.email = :email order by pm.project.name asc, pm.project.id asc")
    List<Project> findProjectsOfMemberEmail(@Param("email") String email);

    /** AC15 GET /projects/{projectId}/members — name asc·id asc 고정 */
    @Query("select pm.member from ProjectMember pm where pm.project.id = :projectId order by pm.member.name asc, pm.member.id asc")
    List<Member> findMembersOfProject(@Param("projectId") Long projectId);
}
