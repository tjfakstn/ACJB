package com.acjb.server.dto;

/** PUT /projects/{projectId}/connections/github — AC21. branch를 빼면 저장소 기본 브랜치(2026-11-07) */
public record GithubConnectRequest(String url, String branch) {
}
