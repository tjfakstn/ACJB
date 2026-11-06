package com.acjb.server.service;

import org.springframework.stereotype.Component;

import com.acjb.server.domain.Member;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.repository.ProjectMemberRepository;

/**
 * 재검증 등록 권한(AC13) — 재검증 등록과 재검증 범위 제안(AC20)이 같은 규칙을 쓴다.
 * 등록자 본인, 또는 등록자가 프로젝트에서 제거됐을 때 소유자(2026-11-05 결정). 등록자가 아직 멤버면 소유자라도 안 된다(au-009).
 */
@Component
public class VerifierPolicy {

    private final ProjectMemberRepository projectMembers;

    public VerifierPolicy(ProjectMemberRepository projectMembers) {
        this.projectMembers = projectMembers;
    }

    public boolean canVerify(QAIssue issue, Member actor) {
        Long reporterId = issue.getReportedBy().getId();
        if (reporterId.equals(actor.getId())) {
            return true;
        }
        Long projectId = issue.getProject().getId();
        return issue.getProject().getOwner().getId().equals(actor.getId())
            && !projectMembers.existsByProjectIdAndMemberId(projectId, reporterId);
    }
}
