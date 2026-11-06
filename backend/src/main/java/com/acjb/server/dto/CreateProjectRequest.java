package com.acjb.server.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST /projects body — 소유자는 받지 않는다(항상 요청자) */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateProjectRequest(String name, String role) {
}
