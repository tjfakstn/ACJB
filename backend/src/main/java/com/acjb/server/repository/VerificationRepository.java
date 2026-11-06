package com.acjb.server.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.Verification;

public interface VerificationRepository extends JpaRepository<Verification, Long> {
    List<Verification> findByIssueIdOrderByVerifiedAtDescIdDesc(Long issueId);
}
