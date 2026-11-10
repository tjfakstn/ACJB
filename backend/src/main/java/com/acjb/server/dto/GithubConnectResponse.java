package com.acjb.server.dto;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** GitHub 연결 응답 — 작업공간의 GithubConnection에 고를 수 있는 브랜치 목록(이름순, 최대 100개)을 더한다 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GithubConnectResponse(String url, String repo, String defaultBranch, String branch, List<String> branches, Instant connectedAt) {
}
