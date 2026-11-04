package com.acjb.server.dto;

import java.util.List;

import com.acjb.server.dto.CreateQaIssueRequest.EnvironmentInput;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** POST /style-mismatches/{mismatchId}/issues body (openapi). 정의되지 않은 필드는 무시한다(reported_by 등, N8) */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateIssueFromMismatchRequest(
    String description,
    String screenLocation,
    List<String> reproSteps,
    String platformScope,
    EnvironmentInput environment
) {
}
