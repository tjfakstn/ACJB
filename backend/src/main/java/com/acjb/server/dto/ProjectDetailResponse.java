package com.acjb.server.dto;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * openapi ProjectDetail — 작업공간 홈(AC25). figma·github는 연결 전이면 null로 내려간다(키는 항상 있다).
 * issue_counts는 5개 상태를 모두 담는다(0 포함)
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProjectDetailResponse(
    String id,
    String name,
    String description,
    String ownerId,
    /** 만든 시각 — 시안 WS "프로젝트 정보" 생성일(2026-11-07 추가) */
    Instant createdAt,
    /** null=미지정 */
    String myRole,
    FigmaConnection figma,
    GithubConnection github,
    String analysisStatus,
    String firstQaStatus,
    long memberCount,
    long pendingInvitationCount,
    Map<String, Long> issueCounts
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    /** usesOwnToken·tokenHint: 소유자 본인 Figma 토큰으로 읽는지와 그 끝 4자리(2026-11-11) — 토큰 값은 내려주지 않는다 */
    public record FigmaConnection(String url, String fileKey, String fileName, String pageId, String pageName, String scope, Instant connectedAt,
                                  boolean usesOwnToken, String tokenHint) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GithubConnection(String url, String repo, String defaultBranch, String branch, Instant connectedAt) {
    }
}
