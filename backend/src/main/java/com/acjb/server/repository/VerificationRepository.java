package com.acjb.server.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.Verification;

public interface VerificationRepository extends JpaRepository<Verification, Long> {
    List<Verification> findByIssueIdOrderByVerifiedAtDescIdDesc(Long issueId);

    /** Re-QA로 판정된 횟수(가짜 비교기의 첫 판정·재판정 구분, AC28) */
    long countByIssueIdAndSource(Long issueId, com.acjb.server.domain.VerificationSource source);
}
