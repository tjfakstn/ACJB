package com.acjb.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.ProjectMember;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
    boolean existsByProjectIdAndMemberId(Long projectId, Long memberId);

    boolean existsByProjectIdAndMemberEmail(Long projectId, String email);
}
