package com.acjb.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * POST /projects/{projectId}/qa-issues body. 값 검사는 서비스에서 한다 — AC1은 "빠진 항목 이름"을 모아 돌려줘야 해서
 * Bean Validation 대신 직접 검사한다. enum은 문자열로 받아 허용 값인지 확인한다.
 * 정의되지 않은 필드(reported_by 등)는 무시한다 — 등록자는 항상 로그인 사용자(N8, 골든 케이스 ng-008).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateQaIssueRequest(
    String description,
    String screenLocation,
    List<String> reproSteps,
    String platformScope,
    EnvironmentInput environment,
    List<String> screenshots,
    String figmaLink,
    StyleMismatchInput styleMismatch,
    String assignedTo
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnvironmentInput(String platform, String device, String browser, String deployStage) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StyleMismatchInput(String property, String expectedValue, String actualValue, String frameId, String screen) {
    }
}
