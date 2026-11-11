package com.acjb.server.dto;

/**
 * PUT /projects/{projectId}/connections/figma — AC21. token은 선택: 우리 서버 계정이 그 파일을 읽지 못할 때만 소유자 본인 Figma 토큰(2026-11-11).
 * 비우면 이미 넣어 둔 토큰(있으면)이나 서버 토큰으로 확인한다
 */
public record ConnectRequest(String url, String token) {
}
