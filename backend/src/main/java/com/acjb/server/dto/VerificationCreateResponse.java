package com.acjb.server.dto;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi VerificationCreateResponse.data — repro_steps는 이슈에 처음 기록된 그대로(AC5) */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VerificationCreateResponse(VerificationResponse verification, String issueStatus, List<String> reproSteps) {
}
