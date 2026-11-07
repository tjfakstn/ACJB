package com.acjb.server.dto;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * openapi ProjectSummary — 프로젝트 목록 카드(시안 PRJ-01, SPEC AC15).
 * issue_count="QA 결과 n건"(전체 이슈), open_issue_count="FAIL n건"(resolved가 아닌 이슈)
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectSummaryResponse(
    String id,
    String name,
    String description,
    String ownerId,
    boolean figmaConnected,
    boolean githubConnected,
    String analysisStatus,
    Instant lastAnalyzedAt,
    long issueCount,
    long openIssueCount,
    long memberCount,
    List<MemberPreview> membersPreview
) {

    public record MemberPreview(String id, String name) {
    }
}
