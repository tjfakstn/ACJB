package com.acjb.server.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * POST /projects body — 이름(필수)과 설명(선택)만 받는다(시안 PRJ-02, 2026-11-07). 소유자는 항상 요청자이고,
 * 역할은 받지 않는다 — 만든 사람은 역할 미지정으로 시작해 멤버 관리에서 정한다(AC24)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateProjectRequest(String name, String description) {
}
