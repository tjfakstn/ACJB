package com.acjb.server.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByEmail(String email);
}
